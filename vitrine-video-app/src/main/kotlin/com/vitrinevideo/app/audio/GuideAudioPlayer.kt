package com.vitrinevideo.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

data class GuideAudioState(
    val assetPath: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val isPrepared: Boolean = false,
    val errorMessage: String? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("assetPath", assetPath ?: "")
        put("durationMs", durationMs)
        put("positionMs", positionMs)
        put("durationSec", durationMs / 1000.0)
        put("currentSec", positionMs / 1000.0)
        put("playing", isPlaying)
        put("ended", isEnded)
        put("ready", isPrepared)
        put("error", errorMessage ?: "")
    }.toString()
}

interface GuideAudioBackend {
    interface Callbacks {
        fun onCompletion()
        fun onError(message: String)
        fun onAudioFocusChange(focusChange: Int)
    }

    fun setCallbacks(callbacks: Callbacks)
    fun loadAsset(assetPath: String): Long
    fun start()
    fun pause()
    fun seekTo(positionMs: Long)
    fun currentPositionMs(): Long
    fun setVolume(volume: Float)
    fun requestAudioFocus(): Boolean
    fun abandonAudioFocus()
    fun releasePlayer()
}

interface GuideAudioTicker {
    fun start(intervalMs: Long, onTick: () -> Unit)
    fun stop()
}

/**
 * Pure-Kotlin state machine for T3/mockup guide audio playback.
 * Decoupled from Android framework classes so every scenario (full playback, pause/resume,
 * -10s/+10s, seek, completion/replay, audio-focus interruption, screen/scene switch) can be
 * verified deterministically in JVM unit tests as well as on real Android hardware.
 */
class GuideAudioController(
    private val backend: GuideAudioBackend,
    private val ticker: GuideAudioTicker,
    private val onStateChanged: (GuideAudioState) -> Unit
) : GuideAudioBackend.Callbacks {

    companion object {
        private const val TICK_INTERVAL_MS = 150L
        private const val PREFIX_MOCKUP = "mockup/assets/audio/"
        private const val PREFIX_RELATIVE = "assets/audio/"

        fun normalizeAssetPath(rawPath: String?): String? {
            if (rawPath.isNullOrBlank()) return null
            var candidate = rawPath.trim()
            val queryIdx = candidate.indexOf('?')
            if (queryIdx >= 0) candidate = candidate.substring(0, queryIdx)
            val hashIdx = candidate.indexOf('#')
            if (hashIdx >= 0) candidate = candidate.substring(0, hashIdx)

            val schemeIdx = candidate.indexOf("://")
            if (schemeIdx >= 0) {
                val slashIdx = candidate.indexOf('/', schemeIdx + 3)
                candidate = if (slashIdx >= 0) candidate.substring(slashIdx + 1) else ""
            }
            candidate = candidate.trimStart('/')
            if (candidate.startsWith(PREFIX_RELATIVE)) {
                candidate = "mockup/$candidate"
            }
            if (candidate.contains("..")) return null
            if (!candidate.startsWith(PREFIX_MOCKUP) || !candidate.endsWith(".mp3", ignoreCase = true)) {
                return null
            }
            return candidate
        }
    }

    private val lock = Any()
    private var state = GuideAudioState()
    private var resumeOnFocusGain = false
    private var released = false

    init {
        backend.setCallbacks(this)
    }

    fun currentState(): GuideAudioState = synchronized(lock) {
        if (state.isPlaying && state.isPrepared) {
            val pos = backend.currentPositionMs().coerceIn(0L, state.durationMs.coerceAtLeast(0L))
            if (pos != state.positionMs) {
                state = state.copy(positionMs = pos)
            }
        }
        state
    }

    fun prepare(rawAssetPath: String): GuideAudioState = synchronized(lock) {
        if (released) return@synchronized state
        val normalized = normalizeAssetPath(rawAssetPath)
        if (normalized == null) {
            stopInternal(clearAsset = true, error = "Invalid guide audio path")
            return@synchronized state
        }
        if (state.isPrepared && state.assetPath == normalized && state.errorMessage == null) {
            emitState()
            return@synchronized state
        }

        stopInternal(clearAsset = true, emit = false)
        try {
            val duration = backend.loadAsset(normalized).coerceAtLeast(0L)
            backend.setVolume(1.0f)
            state = GuideAudioState(
                assetPath = normalized,
                durationMs = duration,
                positionMs = 0L,
                isPlaying = false,
                isEnded = false,
                isPrepared = true,
                errorMessage = null
            )
            emitState()
            state
        } catch (t: Throwable) {
            stopInternal(clearAsset = true, error = t.message ?: "Unable to load guide audio")
            state
        }
    }

    fun play(): GuideAudioState = synchronized(lock) {
        if (released) return@synchronized state
        val asset = state.assetPath ?: return@synchronized state
        if (!state.isPrepared) {
            prepare(asset)
            if (!state.isPrepared) return@synchronized state
        }
        if (state.isPlaying) return@synchronized state

        if (state.isEnded || (state.durationMs > 0L && state.positionMs >= state.durationMs)) {
            backend.seekTo(0L)
            state = state.copy(positionMs = 0L, isEnded = false)
        }

        resumeOnFocusGain = false
        if (!backend.requestAudioFocus()) {
            state = state.copy(isPlaying = false, errorMessage = "Audio focus unavailable")
            emitState()
            return@synchronized state
        }

        backend.setVolume(1.0f)
        backend.start()
        state = state.copy(isPlaying = true, isEnded = false, errorMessage = null)
        ticker.start(TICK_INTERVAL_MS) { onTick() }
        emitState()
        state
    }

    fun pause(): GuideAudioState = synchronized(lock) {
        resumeOnFocusGain = false
        if (!state.isPrepared) return@synchronized state
        ticker.stop()
        val pos = backend.currentPositionMs().coerceIn(0L, state.durationMs.coerceAtLeast(0L))
        if (state.isPlaying) {
            backend.pause()
        }
        backend.abandonAudioFocus()
        state = state.copy(positionMs = pos, isPlaying = false)
        emitState()
        state
    }

    fun togglePlayPause(): GuideAudioState = synchronized(lock) {
        if (state.isPlaying) pause() else play()
    }

    fun seekTo(targetMs: Long): GuideAudioState = synchronized(lock) {
        if (released || !state.isPrepared) return@synchronized state
        val dur = state.durationMs.coerceAtLeast(0L)
        val clamped = targetMs.coerceIn(0L, dur)

        if (dur > 0L && clamped >= dur) {
            ticker.stop()
            resumeOnFocusGain = false
            if (state.isPlaying) {
                backend.pause()
            }
            backend.seekTo(dur)
            backend.abandonAudioFocus()
            state = state.copy(positionMs = dur, isPlaying = false, isEnded = true, errorMessage = null)
            emitState()
            return@synchronized state
        }

        backend.seekTo(clamped)
        state = state.copy(positionMs = clamped, isEnded = false, errorMessage = null)
        emitState()
        state
    }

    fun seekBy(deltaMs: Long): GuideAudioState = synchronized(lock) {
        if (!state.isPrepared) return@synchronized state
        val current = if (state.isEnded) {
            state.durationMs
        } else if (state.isPlaying) {
            backend.currentPositionMs().coerceIn(0L, state.durationMs.coerceAtLeast(0L))
        } else {
            state.positionMs
        }
        seekTo(current + deltaMs)
    }

    fun onHostPause() = synchronized(lock) {
        if (state.isPlaying) {
            pause()
        }
    }

    fun onHostResume() = synchronized(lock) {
        if (!released && state.isPrepared) {
            emitState()
        }
    }

    fun stopAndReset() = synchronized(lock) {
        stopInternal(clearAsset = true, emit = true)
    }

    fun release() = synchronized(lock) {
        if (released) return@synchronized
        released = true
        stopInternal(clearAsset = true, emit = false)
    }

    override fun onCompletion() = synchronized(lock) {
        if (released || !state.isPrepared) return@synchronized
        ticker.stop()
        resumeOnFocusGain = false
        backend.abandonAudioFocus()
        state = state.copy(
            positionMs = state.durationMs,
            isPlaying = false,
            isEnded = true,
            errorMessage = null
        )
        emitState()
    }

    override fun onError(message: String) = synchronized(lock) {
        if (released) return@synchronized
        stopInternal(clearAsset = false, error = message)
    }

    override fun onAudioFocusChange(focusChange: Int) = synchronized(lock) {
        if (released || !state.isPrepared) return@synchronized
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (state.isPlaying) {
                    ticker.stop()
                    val pos = backend.currentPositionMs().coerceIn(0L, state.durationMs.coerceAtLeast(0L))
                    backend.pause()
                    resumeOnFocusGain = true
                    state = state.copy(positionMs = pos, isPlaying = false)
                    emitState()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (state.isPlaying) {
                    backend.setVolume(0.30f)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                backend.setVolume(1.0f)
                if (resumeOnFocusGain && !state.isPlaying && !state.isEnded) {
                    resumeOnFocusGain = false
                    backend.start()
                    state = state.copy(isPlaying = true)
                    ticker.start(TICK_INTERVAL_MS) { onTick() }
                    emitState()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                if (state.isPlaying) {
                    ticker.stop()
                    val pos = backend.currentPositionMs().coerceIn(0L, state.durationMs.coerceAtLeast(0L))
                    backend.pause()
                    backend.abandonAudioFocus()
                    state = state.copy(positionMs = pos, isPlaying = false)
                    emitState()
                }
            }
        }
    }

    private fun onTick() = synchronized(lock) {
        if (released || !state.isPrepared || !state.isPlaying) return@synchronized
        val pos = backend.currentPositionMs().coerceIn(0L, state.durationMs.coerceAtLeast(0L))
        if (pos != state.positionMs) {
            state = state.copy(positionMs = pos)
            emitState()
        }
    }

    private fun stopInternal(clearAsset: Boolean, emit: Boolean = true, error: String? = null) {
        ticker.stop()
        resumeOnFocusGain = false
        backend.abandonAudioFocus()
        backend.releasePlayer()
        state = if (clearAsset) {
            GuideAudioState(errorMessage = error)
        } else {
            state.copy(
                isPlaying = false,
                isPrepared = false,
                errorMessage = error
            )
        }
        if (emit) emitState()
    }

    private fun emitState() {
        onStateChanged(state)
    }
}

class AndroidMediaPlayerGuideBackend(
    private val context: Context
) : GuideAudioBackend {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var callbacks: GuideAudioBackend.Callbacks? = null
    private var generation = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var cachedAssetFile: File? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        callbacks?.onAudioFocusChange(focusChange)
    }

    override fun setCallbacks(callbacks: GuideAudioBackend.Callbacks) {
        this.callbacks = callbacks
    }

    override fun loadAsset(assetPath: String): Long {
        releasePlayer()
        val gen = ++generation
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnCompletionListener {
                if (gen == generation) {
                    callbacks?.onCompletion()
                }
            }
            setOnErrorListener { _, what, extra ->
                if (gen == generation) {
                    callbacks?.onError("Guide audio playback error ($what/$extra)")
                }
                true
            }
        }

        try {
            try {
                context.assets.openFd(assetPath).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            } catch (_: FileNotFoundException) {
                // Fallback if an asset is compressed in a non-standard packaging build
                val cacheDir = File(context.cacheDir, "guide_audio_cache").apply { mkdirs() }
                val tmp = File(cacheDir, assetPath.substringAfterLast('/'))
                context.assets.open(assetPath).use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                cachedAssetFile = tmp
                mp.setDataSource(tmp.absolutePath)
            }
            mp.prepare()
            mediaPlayer = mp
            return mp.duration.toLong().coerceAtLeast(0L)
        } catch (t: Throwable) {
            runCatching { mp.release() }
            throw t
        }
    }

    override fun start() {
        mediaPlayer?.let { if (!it.isPlaying) it.start() }
    }

    override fun pause() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
    }

    override fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        val target = positionMs.toInt().coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mp.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(target)
        }
    }

    override fun currentPositionMs(): Long {
        return runCatching { mediaPlayer?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    }

    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        runCatching { mediaPlayer?.setVolume(v, v) }
    }

    @Suppress("DEPRECATION")
    override fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    override fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    override fun releasePlayer() {
        generation++
        mediaPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
        cachedAssetFile?.let { runCatching { it.delete() } }
        cachedAssetFile = null
    }
}

class MainLooperGuideAudioTicker(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : GuideAudioTicker {
    private var runnable: Runnable? = null

    override fun start(intervalMs: Long, onTick: () -> Unit) {
        stop()
        val task = object : Runnable {
            override fun run() {
                onTick()
                if (runnable === this) {
                    handler.postDelayed(this, intervalMs)
                }
            }
        }
        runnable = task
        handler.postDelayed(task, intervalMs)
    }

    override fun stop() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }
}

class GuideAudioPlayer(
    context: Context,
    onStateChanged: (GuideAudioState) -> Unit
) {
    private val controller = GuideAudioController(
        backend = AndroidMediaPlayerGuideBackend(context),
        ticker = MainLooperGuideAudioTicker(),
        onStateChanged = onStateChanged
    )

    fun prepare(rawAssetPath: String): GuideAudioState = controller.prepare(rawAssetPath)
    fun play(): GuideAudioState = controller.play()
    fun pause(): GuideAudioState = controller.pause()
    fun togglePlayPause(): GuideAudioState = controller.togglePlayPause()
    fun seekTo(positionMs: Long): GuideAudioState = controller.seekTo(positionMs)
    fun seekBy(deltaMs: Long): GuideAudioState = controller.seekBy(deltaMs)
    fun currentState(): GuideAudioState = controller.currentState()
    fun onHostPause() = controller.onHostPause()
    fun onHostResume() = controller.onHostResume()
    fun stopAndReset() = controller.stopAndReset()
    fun release() = controller.release()
}
