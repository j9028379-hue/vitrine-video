package com.vitrinevideo.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vitrinevideo.app.flow.CaptureSource
import com.vitrinevideo.app.flow.CapturedSceneTake
import com.vitrinevideo.engine.capture.AndroidMediaExtractorProbe
import com.vitrinevideo.engine.capture.SceneCaptureValidationSnapshot
import com.vitrinevideo.engine.capture.SceneCaptureValidator
import com.vitrinevideo.engine.timeline.PreFlightValidator
import java.io.File

class CameraXSceneRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    interface Callback {
        fun onCameraReady()
        fun onCameraError(code: String, message: String)
        fun onRecordingTick(elapsedMs: Long, targetMs: Long)
        fun onRecordingFinished(take: CapturedSceneTake)
        fun onRecordingAborted()
    }

    companion object {
        private const val MIN_STORAGE_BYTES = 40L * 1024L * 1024L
        private const val WARMUP_HEADROOM_NS = 250_000_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var discardCurrentRecording = false
    private var stopRequested = false
    private var watchdogRunnable: Runnable? = null

    fun hasPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return cam && mic
    }

    fun isRecording(): Boolean = activeRecording != null

    fun bindPreview(previewView: PreviewView, callback: Callback) {
        if (!hasPermissions()) {
            callback.onCameraError("PERMISSIONS_MISSING", "Autorisations caméra et microphone requises.")
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        callback.onCameraError("NO_CAMERA_FOUND", "Aucune caméra disponible sur cet appareil.")
                        return@addListener
                    }
                }

                val preview = Preview.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val qualitySelector = QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()

                val vc = VideoCapture.Builder(recorder)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()

                videoCapture = vc
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
                callback.onCameraReady()
            } catch (t: Throwable) {
                callback.onCameraError("CAMERA_INIT_FAILED", t.message ?: "Impossible d'initialiser la caméra.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        cancelActiveRecording()
        runCatching { cameraProvider?.unbindAll() }
        videoCapture = null
    }

    fun startRecording(
        sceneIndex: Int,
        sceneId: String,
        targetDurationSec: Int,
        capturesDir: File,
        callback: Callback
    ) {
        if (activeRecording != null) return
        if (!hasPermissions()) {
            callback.onCameraError("PERMISSIONS_MISSING", "Autorisations caméra et microphone requises.")
            return
        }
        val vc = videoCapture ?: run {
            callback.onCameraError("CAMERA_NOT_READY", "La caméra n'est pas prête.")
            return
        }

        capturesDir.mkdirs()
        val freeBytes = runCatching { StatFs(capturesDir.absolutePath).availableBytes }.getOrDefault(capturesDir.usableSpace)
        if (freeBytes in 1 until MIN_STORAGE_BYTES) {
            callback.onCameraError(
                "INSUFFICIENT_STORAGE",
                "Espace de stockage insuffisant (${freeBytes / (1024 * 1024)} Mo libres, minimum 40 Mo requis)."
            )
            return
        }

        val destFile = File(capturesDir, "${sceneId}_take_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(destFile).build()
        val targetMs = targetDurationSec * 1000L
        val targetNsWithHeadroom = (targetDurationSec * 1_000_000_000L) + WARMUP_HEADROOM_NS

        discardCurrentRecording = false
        stopRequested = false

        var pending = vc.output.prepareRecording(context, outputOptions)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }

        val watchdog = Runnable {
            if (activeRecording != null && !stopRequested) {
                stopRequested = true
                activeRecording?.stop()
            }
        }
        watchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, targetMs + 3500L)

        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    val recNs = event.recordingStats.recordedDurationNanos
                    val elapsedMs = (recNs / 1_000_000L).coerceAtMost(targetMs)
                    callback.onRecordingTick(elapsedMs, targetMs)
                    if (!stopRequested && !discardCurrentRecording && recNs >= targetNsWithHeadroom) {
                        stopRequested = true
                        activeRecording?.stop()
                    }
                }

                is VideoRecordEvent.Finalize -> {
                    mainHandler.removeCallbacks(watchdog)
                    watchdogRunnable = null
                    activeRecording = null

                    if (discardCurrentRecording) {
                        destFile.delete()
                        callback.onRecordingAborted()
                        return@start
                    }

                    if (event.hasError()) {
                        destFile.delete()
                        callback.onCameraError(
                            "RECORDING_ENCODE_ERROR",
                            "Échec d'enregistrement caméra (code=${event.error})."
                        )
                        return@start
                    }

                    val probed = AndroidMediaExtractorProbe.probe(destFile)
                    if (probed == null || !probed.hasVideo || probed.width <= 0 || probed.height <= 0) {
                        destFile.delete()
                        callback.onCameraError(
                            "INCOMPLETE_RECORDING",
                            "Vidéo capturée invalide ou corrompue pour $sceneId."
                        )
                        return@start
                    }

                    val statsDurUs = event.recordingStats.recordedDurationNanos / 1000L
                    // MediaExtractor video timestamps are authoritative; CameraX stats are
                    // only a fallback when a container has no duration metadata.
                    val actualDurUs = if (probed.durationUs > 0L) probed.durationUs else statsDurUs
                    val sha256 = PreFlightValidator.sha256File(destFile)
                    val failure = SceneCaptureValidator.validate(
                        SceneCaptureValidationSnapshot(
                            sceneId = sceneId,
                            filePath = destFile.absolutePath,
                            fileExists = destFile.exists(),
                            fileReadable = destFile.canRead(),
                            fileNonEmpty = destFile.length() > 0L,
                            durationUs = actualDurUs,
                            width = probed.width,
                            height = probed.height,
                            rotation = probed.rotation,
                            hasVideoStream = probed.hasVideo,
                            audioPresent = probed.hasAudio,
                            sha256 = sha256,
                            fromRealCamera = true
                        ),
                        requiredDurationUs = targetMs * 1_000L
                    )
                    if (failure != null) {
                        destFile.delete()
                        callback.onCameraError(failure.code, "${failure.message} Recommence la prise.")
                        return@start
                    }
                    callback.onRecordingFinished(
                        CapturedSceneTake(
                            sceneIndex = sceneIndex,
                            sceneId = sceneId,
                            filePath = destFile.absolutePath,
                            durationUs = actualDurUs,
                            width = probed.width,
                            height = probed.height,
                            rotation = probed.rotation,
                            audioPresent = probed.hasAudio,
                            sha256 = sha256,
                            source = CaptureSource.CAMERAX_REAL,
                            validatedByMerchant = false,
                            recordedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    fun cancelActiveRecording() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
        if (activeRecording != null) {
            discardCurrentRecording = true
            stopRequested = true
            activeRecording?.stop()
        }
    }
}
