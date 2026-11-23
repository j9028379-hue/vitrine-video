package com.vitrinevideo.engine.export

import android.media.MediaExtractor
import android.media.MediaFormat
import com.vitrinevideo.engine.api.EngineConfig
import com.vitrinevideo.engine.api.RenderReport
import com.vitrinevideo.engine.api.SceneReport
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.timeline.PreFlightReport
import com.vitrinevideo.engine.timeline.PreFlightValidator
import java.io.File
import kotlin.math.abs

class ExportVerificationException(val code: String, message: String) : IllegalStateException(message)

object PostRenderVerifier {

    fun verifyAndBuildReport(
        mp4File: File,
        plan: RenderPlan,
        preFlight: PreFlightReport,
        config: EngineConfig,
        priceDisplayed: Boolean,
        usedHardwareEncoder: Boolean,
        renderDurationMs: Long
    ): RenderReport {
        if (!mp4File.exists() || mp4File.length() < 1024L) {
            throw ExportVerificationException("EXPORT_EMPTY", "Fichier MP4 absent ou vide")
        }

        val extractor = MediaExtractor()
        var videoWidth = 0
        var videoHeight = 0
        var videoDurationUs = 0L
        var videoMime = ""
        var hasVideo = false
        var hasAudio = false

        try {
            extractor.setDataSource(mp4File.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    videoMime = mime
                    videoWidth = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    videoDurationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                }
            }
        } finally {
            extractor.release()
        }

        if (!hasVideo) {
            throw ExportVerificationException("MISSING_VIDEO_TRACK", "Le MP4 exporté ne contient pas de piste vidéo")
        }
        if (!hasAudio) {
            throw ExportVerificationException("MISSING_AUDIO_TRACK", "Le MP4 exporté ne contient pas de piste audio")
        }
        if (videoWidth != plan.width || videoHeight != plan.height) {
            throw ExportVerificationException(
                "DIMENSION_MISMATCH",
                "Dimensions exportées ${videoWidth}x${videoHeight} != attendues ${plan.width}x${plan.height}"
            )
        }

        val actualDurSec = videoDurationUs / 1_000_000.0
        val expectedDurSec = plan.durationSec
        if (videoDurationUs > 0L && abs(actualDurSec - expectedDurSec) > config.durationToleranceSec) {
            throw ExportVerificationException(
                "DURATION_MISMATCH",
                "Durée exportée ${"%.2f".format(actualDurSec)}s != attendue ${"%.2f".format(expectedDurSec)}s"
            )
        }

        val sha = PreFlightValidator.sha256File(mp4File)
        val rt = Runtime.getRuntime()
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)

        val sceneReports = plan.scenes.map { sc ->
            val cap = preFlight.validatedCaptures.getValue(sc.id)
            SceneReport(
                sceneId = sc.id,
                sourceSha256 = cap.sha256,
                startFrame = sc.startFrame,
                endFrameExcluded = sc.endFrameExcluded,
                audioActive = cap.hasAudio
            )
        }

        return RenderReport(
            templateId = plan.templateId,
            templateVersion = plan.version,
            width = videoWidth,
            height = videoHeight,
            fps = plan.fps,
            frames = plan.totalFrames,
            durationSec = if (videoDurationUs > 0L) actualDurSec else expectedDurSec,
            codec = videoMime,
            videoSha256 = sha,
            scenes = sceneReports,
            activeAudioScenes = sceneReports.filter { it.audioActive }.map { it.sceneId },
            priceDisplayed = priceDisplayed,
            usedHardwareEncoder = usedHardwareEncoder,
            renderDurationMs = renderDurationMs,
            peakHeapMb = usedHeapMb
        )
    }
}
