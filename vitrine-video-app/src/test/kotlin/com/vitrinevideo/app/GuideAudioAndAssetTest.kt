package com.vitrinevideo.app

import android.media.AudioManager
import com.vitrinevideo.app.audio.GuideAudioBackend
import com.vitrinevideo.app.audio.GuideAudioController
import com.vitrinevideo.app.audio.GuideAudioState
import com.vitrinevideo.app.audio.GuideAudioTicker
import com.vitrinevideo.app.bridge.AssetDataSource
import com.vitrinevideo.app.bridge.AssetResponseBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class GuideAudioAndAssetTest {

    private class FakeGuideAudioBackend(
        private val durationsByAsset: Map<String, Long> = mapOf(
            "mockup/assets/audio/t3-scene1-guide.mp3" to 46_944L,
            "mockup/assets/audio/t3-scene2-guide.mp3" to 41_664L,
            "mockup/assets/audio/t3-scene3-guide.mp3" to 42_864L,
            "mockup/assets/audio/t3-scene4-guide.mp3" to 48_456L
        )
    ) : GuideAudioBackend {
        var currentCallbacks: GuideAudioBackend.Callbacks? = null
        var currentAsset: String? = null
        var durationMs: Long = 0L
        var positionMs: Long = 0L
        var playing: Boolean = false
        var currentVolume: Float = 1.0f
        var audioFocusGranted: Boolean = true
        var focusRequests: Int = 0
        var focusAbandons: Int = 0
        var releases: Int = 0

        override fun setCallbacks(callbacks: GuideAudioBackend.Callbacks) {
            this.currentCallbacks = callbacks
        }

        override fun loadAsset(assetPath: String): Long {
            val dur = durationsByAsset[assetPath] ?: throw IllegalArgumentException("Missing asset: $assetPath")
            currentAsset = assetPath
            durationMs = dur
            positionMs = 0L
            playing = false
            return dur
        }

        override fun start() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seekTo(positionMs: Long) {
            this.positionMs = positionMs.coerceIn(0L, durationMs)
        }

        override fun currentPositionMs(): Long = positionMs

        override fun setVolume(volume: Float) {
            this.currentVolume = volume
        }

        override fun requestAudioFocus(): Boolean {
            focusRequests++
            return audioFocusGranted
        }

        override fun abandonAudioFocus() {
            focusAbandons++
        }

        override fun releasePlayer() {
            playing = false
            releases++
        }

        fun advanceTo(targetMs: Long) {
            if (!playing) return
            if (targetMs >= durationMs) {
                positionMs = durationMs
                playing = false
                currentCallbacks?.onCompletion()
            } else {
                positionMs = targetMs
            }
        }
    }

    private class FakeGuideAudioTicker : GuideAudioTicker {
        var active = false
        private var tickAction: (() -> Unit)? = null

        override fun start(intervalMs: Long, onTick: () -> Unit) {
            active = true
            tickAction = onTick
        }

        override fun stop() {
            active = false
            tickAction = null
        }

        fun tick() {
            if (active) tickAction?.invoke()
        }
    }

    @Test
    fun normalizeAssetPath_acceptsMockupUrlsAndBlocksTraversal() {
        assertEquals(
            "mockup/assets/audio/t3-scene1-guide.mp3",
            GuideAudioController.normalizeAssetPath("https://appassets.vitrinevideo.local/mockup/assets/audio/t3-scene1-guide.mp3")
        )
        assertEquals(
            "mockup/assets/audio/t3-scene2-guide.mp3",
            GuideAudioController.normalizeAssetPath("assets/audio/t3-scene2-guide.mp3")
        )
        assertNull(GuideAudioController.normalizeAssetPath("https://appassets.vitrinevideo.local/mockup/assets/audio/../secret.mp3"))
        assertNull(GuideAudioController.normalizeAssetPath("assets/images/t3-scene1-portrait.webp"))
    }

    @Test
    fun guideAudio_playsFull46sWithoutStoppingAt14sAndReplaysFromZero() {
        val backend = FakeGuideAudioBackend()
        val ticker = FakeGuideAudioTicker()
        val states = mutableListOf<GuideAudioState>()
        val controller = GuideAudioController(backend, ticker) { states.add(it) }

        val prepared = controller.prepare("https://appassets.vitrinevideo.local/mockup/assets/audio/t3-scene1-guide.mp3")
        assertTrue(prepared.isPrepared)
        assertEquals(46_944L, prepared.durationMs)
        assertEquals(0L, prepared.positionMs)

        val started = controller.play()
        assertTrue(started.isPlaying)
        assertTrue(ticker.active)

        // Pass the critical 13-14s boundary where WebView previously stalled
        backend.advanceTo(14_500L)
        ticker.tick()
        assertTrue(controller.currentState().isPlaying)
        assertEquals(14_500L, controller.currentState().positionMs)

        backend.advanceTo(32_000L)
        ticker.tick()
        assertTrue(controller.currentState().isPlaying)
        assertEquals(32_000L, controller.currentState().positionMs)

        // Reach exact end of file
        backend.advanceTo(46_944L)
        val ended = controller.currentState()
        assertFalse(ended.isPlaying)
        assertTrue(ended.isEnded)
        assertEquals(46_944L, ended.positionMs)
        assertFalse(ticker.active)

        // Pressing Play after completion resets to 0:00 and plays again
        val replayed = controller.play()
        assertTrue(replayed.isPlaying)
        assertFalse(replayed.isEnded)
        assertEquals(0L, replayed.positionMs)
    }

    @Test
    fun guideAudio_pauseResumeSeekAndSkip10sWorkAtAllPositions() {
        val backend = FakeGuideAudioBackend()
        val ticker = FakeGuideAudioTicker()
        val controller = GuideAudioController(backend, ticker) {}

        controller.prepare("assets/audio/t3-scene1-guide.mp3")
        controller.play()

        // Pause at 8s, resume at exact same spot
        backend.advanceTo(8_000L)
        val paused1 = controller.pause()
        assertFalse(paused1.isPlaying)
        assertEquals(8_000L, paused1.positionMs)

        val resumed1 = controller.play()
        assertTrue(resumed1.isPlaying)
        assertEquals(8_000L, backend.positionMs)

        // +10s forward crosses 14s cleanly (8s -> 18s)
        val fwd1 = controller.seekBy(10_000L)
        assertEquals(18_000L, fwd1.positionMs)
        assertTrue(fwd1.isPlaying)

        // Pause at 25s, -10s back -> 15s, still paused
        backend.advanceTo(25_000L)
        controller.pause()
        val back1 = controller.seekBy(-10_000L)
        assertEquals(15_000L, back1.positionMs)
        assertFalse(back1.isPlaying)

        // -10s near start clamps to 0s
        controller.seekTo(4_000L)
        val backClamp = controller.seekBy(-10_000L)
        assertEquals(0L, backClamp.positionMs)

        // +10s near end reaches duration and enters ended state
        controller.play()
        controller.seekTo(41_000L)
        val fwdEnd = controller.seekBy(10_000L)
        assertTrue(fwdEnd.isEnded)
        assertFalse(fwdEnd.isPlaying)
        assertEquals(46_944L, fwdEnd.positionMs)

        // -10s from ended goes back to 36.944s and clears ended
        val backFromEnd = controller.seekBy(-10_000L)
        assertFalse(backFromEnd.isEnded)
        assertEquals(36_944L, backFromEnd.positionMs)
    }

    @Test
    fun guideAudio_handlesAudioFocusAndLifecycleAndSceneSwitching() {
        val backend = FakeGuideAudioBackend()
        val ticker = FakeGuideAudioTicker()
        val controller = GuideAudioController(backend, ticker) {}

        controller.prepare("assets/audio/t3-scene1-guide.mp3")
        controller.play()
        backend.advanceTo(19_000L)
        ticker.tick()

        // Transient audio interruption (e.g. incoming call / voice message)
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(controller.currentState().isPlaying)
        assertEquals(19_000L, controller.currentState().positionMs)

        // Ducking & focus gain resume automatically
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(controller.currentState().isPlaying)
        assertEquals(1.0f, backend.currentVolume)

        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0.30f, backend.currentVolume)
        controller.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1.0f, backend.currentVolume)

        // Host activity pause preserves exact position; resume re-emits state without losing spot
        backend.advanceTo(27_500L)
        controller.onHostPause()
        assertFalse(controller.currentState().isPlaying)
        assertEquals(27_500L, controller.currentState().positionMs)
        controller.onHostResume()
        assertEquals(27_500L, controller.currentState().positionMs)

        // Switching to scene 2, 3, 4 loads each guide cleanly at 0:00 with real duration
        val s2 = controller.prepare("assets/audio/t3-scene2-guide.mp3")
        assertEquals(41_664L, s2.durationMs)
        assertEquals(0L, s2.positionMs)
        assertFalse(s2.isPlaying)

        val s3 = controller.prepare("assets/audio/t3-scene3-guide.mp3")
        assertEquals(42_864L, s3.durationMs)

        val s4 = controller.prepare("assets/audio/t3-scene4-guide.mp3")
        assertEquals(48_456L, s4.durationMs)

        // Leaving guide screen resets cleanly
        controller.stopAndReset()
        assertFalse(controller.currentState().isPrepared)
        assertFalse(controller.currentState().isPlaying)
    }

    @Test
    fun assetResponseBuilder_servesExactContentLengthAndRfc206RangesForMp3() {
        val mp3Bytes = ByteArray(188_013) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val htmlBytes = "<html><body>ok</body></html>".toByteArray()
        val dataSource = object : AssetDataSource {
            override fun openStream(assetPath: String): InputStream = when (assetPath) {
                "mockup/assets/audio/t3-scene1-guide.mp3" -> ByteArrayInputStream(mp3Bytes)
                "mockup/vitrine-video-maquette.html" -> ByteArrayInputStream(htmlBytes)
                else -> throw IllegalArgumentException(assetPath)
            }

            override fun getUncompressedLength(assetPath: String): Long? = when (assetPath) {
                "mockup/assets/audio/t3-scene1-guide.mp3" -> mp3Bytes.size.toLong()
                "mockup/vitrine-video-maquette.html" -> htmlBytes.size.toLong()
                else -> null
            }
        }
        val builder = AssetResponseBuilder(dataSource)

        // 200 OK for MP3 must include Content-Length, Accept-Ranges, no-transform, and null encoding
        val full = builder.build("mockup/assets/audio/t3-scene1-guide.mp3", "GET", null)
        assertEquals(200, full.statusCode)
        assertEquals("audio/mpeg", full.mimeType)
        assertNull(full.encoding)
        assertEquals("188013", full.headers["Content-Length"])
        assertEquals("bytes", full.headers["Accept-Ranges"])
        assertEquals("no-transform", full.headers["Cache-Control"])
        assertArrayEquals(mp3Bytes, full.body.readBytes())

        // 206 Partial Content across the 13-14s boundary (~55KB..100KB)
        val rangeMid = builder.build("mockup/assets/audio/t3-scene1-guide.mp3", "GET", "bytes=55000-100000")
        assertEquals(206, rangeMid.statusCode)
        assertNull(rangeMid.encoding)
        assertEquals("bytes 55000-100000/188013", rangeMid.headers["Content-Range"])
        assertEquals("45001", rangeMid.headers["Content-Length"])
        assertArrayEquals(mp3Bytes.copyOfRange(55_000, 100_001), rangeMid.body.readBytes())

        // Suffix range bytes=-1024
        val rangeSuffix = builder.build("mockup/assets/audio/t3-scene1-guide.mp3", "GET", "bytes=-1024")
        assertEquals(206, rangeSuffix.statusCode)
        assertEquals("bytes 186989-188012/188013", rangeSuffix.headers["Content-Range"])
        assertEquals("1024", rangeSuffix.headers["Content-Length"])
        assertArrayEquals(mp3Bytes.copyOfRange(186_989, 188_013), rangeSuffix.body.readBytes())

        // Out-of-bounds range returns 416
        val invalidRange = builder.build("mockup/assets/audio/t3-scene1-guide.mp3", "GET", "bytes=999999-")
        assertEquals(416, invalidRange.statusCode)
        assertEquals("bytes */188013", invalidRange.headers["Content-Range"])

        // HEAD returns empty body with exact Content-Length
        val headResp = builder.build("mockup/assets/audio/t3-scene1-guide.mp3", "HEAD", null)
        assertEquals(200, headResp.statusCode)
        assertEquals("188013", headResp.headers["Content-Length"])
        assertEquals(0, headResp.body.readBytes().size)

        // HTML keeps UTF-8 encoding
        val htmlResp = builder.build("mockup/vitrine-video-maquette.html", "GET", null)
        assertEquals("text/html", htmlResp.mimeType)
        assertEquals("UTF-8", htmlResp.encoding)
    }
}
