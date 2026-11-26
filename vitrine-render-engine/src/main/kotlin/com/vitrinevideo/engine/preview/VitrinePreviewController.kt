package com.vitrinevideo.engine.preview

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vitrinevideo.engine.capture.SceneCaptureDescriptor
import com.vitrinevideo.engine.graphics.VitrineBitmapTextOverlay
import com.vitrinevideo.engine.interpreter.CutSegmentSpec
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.timeline.TimelineEngine
import com.vitrinevideo.engine.video.VitrineSceneGlEffect
import java.io.File
import kotlin.math.min

data class PreviewShotItemSpec(
    val shotId: String,
    val sceneId: String,
    val videoFile: File,
    val clipStartMs: Long,
    val clipEndMs: Long,
    val expectedOutDurationUs: Long,
    val outStartFrame: Int,
    val outEndFrameExcluded: Int
)

data class LocalPreviewMetrics(
    val templateId: String,
    val templateVersion: String,
    val totalFrames: Int,
    val shotCount: Int,
    val textUpdateCount: Int,
    val sceneReplacementCount: Int,
    val serverRenderCallsMade: Int = 0,
    val creditsConsumed: Int = 0
)

/**
 * Deterministic timeline mapper shared with Media3RenderPipeline so live preview cuts
 * and final MP4 export cuts match frame-for-frame.
 */
object PreviewTimelineMapper {

    fun buildShotSpecs(
        plan: RenderPlan,
        capturesByScene: Map<String, SceneCaptureDescriptor>
    ): List<PreviewShotItemSpec> {
        val timeline = TimelineEngine(plan)
        return plan.scenes.flatMap { sc ->
            val cap = capturesByScene[sc.id]
                ?: throw IllegalArgumentException("Capture manquante pour la scène ${sc.id}")
            val file = File(cap.localVideoPath)
            val shots = if (sc.shots.isNotEmpty()) sc.shots else listOf(
                CutSegmentSpec(
                    shotId = "${sc.id}_P1",
                    sceneId = sc.id,
                    sourceInFrame = 0,
                    sourceOutFrame = sc.endFrameExcluded - sc.startFrame,
                    outStartFrame = sc.startFrame,
                    outEndFrameExcluded = sc.endFrameExcluded
                )
            )
            val maxAvailMs = if (cap.durationUs > 0L) (cap.durationUs / 1000L) else Long.MAX_VALUE
            shots.map { shot ->
                val durUs = timeline.frameToUs(shot.outEndFrameExcluded) - timeline.frameToUs(shot.outStartFrame)
                val clipStartMs = (timeline.frameToUs(shot.sourceInFrame) / 1000L).coerceAtLeast(0L)
                val rawEndMs = (timeline.frameToUs(shot.sourceOutFrame) / 1000L).coerceAtLeast(clipStartMs + 1L)
                val clipEndMs = min(rawEndMs, maxAvailMs).coerceAtLeast(clipStartMs + 1L)
                PreviewShotItemSpec(
                    shotId = shot.shotId,
                    sceneId = sc.id,
                    videoFile = file,
                    clipStartMs = clipStartMs,
                    clipEndMs = clipEndMs,
                    expectedOutDurationUs = durUs,
                    outStartFrame = shot.outStartFrame,
                    outEndFrameExcluded = shot.outEndFrameExcluded
                )
            }
        }
    }

    fun toMediaItem(spec: PreviewShotItemSpec): MediaItem =
        MediaItem.Builder()
            .setMediaId(spec.shotId)
            .setUri(Uri.fromFile(spec.videoFile))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(spec.clipStartMs)
                    .setEndPositionMs(spec.clipEndMs)
                    .build()
            )
            .build()
}

/**
 * Unlimited, zero-re-render local preview engine running directly on Android GPU via ExoPlayer +
 * VitrineSceneGlEffect + hot-swappable VitrineBitmapTextOverlay.
 *
 * - Editing price, title, shop name or WhatsApp updates only the overlay state (< 1 ms) without
 *   re-encoding any video or calling any server.
 * - Re-shooting one scene replaces only that scene's MediaItems in place.
 * - Never writes an unwatermarked export Film.mp4 to disk (Surface playback only).
 */
@OptIn(UnstableApi::class)
class VitrinePreviewController(private val context: Context) {

    private var player: ExoPlayer? = null
    private var currentPlan: RenderPlan? = null
    private var currentOverlay: VitrineBitmapTextOverlay? = null
    private var currentShots: List<PreviewShotItemSpec> = emptyList()
    private val capturesMap = linkedMapOf<String, SceneCaptureDescriptor>()
    private val currentResponses = linkedMapOf<String, String>()
    private var textUpdates = 0
    private var sceneReplacements = 0

    fun prepare(
        partitionJsonFile: File,
        resourcesDir: File,
        captures: List<SceneCaptureDescriptor>,
        initialResponses: Map<String, String>,
        surfaceView: SurfaceView? = null,
        loopPlayback: Boolean = true
    ): RenderPlan {
        release()
        val plan = UniversalJsonInterpreter.parsePartition(partitionJsonFile)
        currentPlan = plan
        capturesMap.clear()
        captures.forEach { capturesMap[it.sceneId] = it }
        currentResponses.clear()
        currentResponses.putAll(initialResponses)

        currentShots = PreviewTimelineMapper.buildShotSpecs(plan, capturesMap)
        val mediaItems = currentShots.map { PreviewTimelineMapper.toMediaItem(it) }

        val effects = mutableListOf<Effect>(VitrineSceneGlEffect(plan))
        plan.textOverlay?.let { spec ->
            val (overlay, effect) = VitrineBitmapTextOverlay.createDynamicOverlay(
                plan = plan,
                spec = spec,
                responses = currentResponses,
                resourcesDir = resourcesDir
            )
            currentOverlay = overlay
            effects += effect
        }

        val exo = ExoPlayer.Builder(context).build().apply {
            repeatMode = if (loopPlayback) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            setVideoEffects(effects)
            setMediaItems(mediaItems)
            surfaceView?.let { setVideoSurfaceView(it) }
            prepare()
            playWhenReady = true
        }
        player = exo
        return plan
    }

    fun attachSurfaceView(surfaceView: SurfaceView?) {
        player?.setVideoSurfaceView(surfaceView)
    }

    /**
     * Instant live text update without rebuilding ExoPlayer, re-decoding scenes, or touching credits.
     */
    fun updateResponses(newResponses: Map<String, String>) {
        currentResponses.clear()
        currentResponses.putAll(newResponses)
        currentOverlay?.updateResponses(currentResponses)
        textUpdates++
        val exo = player ?: return
        if (!exo.isPlaying) {
            exo.seekTo(exo.currentPosition)
        }
    }

    /**
     * Replaces a single re-shot scene in place while keeping all other scenes and text edits intact.
     */
    fun replaceSceneCapture(sceneId: String, newCapture: SceneCaptureDescriptor) {
        val plan = currentPlan ?: return
        val exo = player ?: return
        capturesMap[sceneId] = newCapture

        val firstIdx = currentShots.indexOfFirst { it.sceneId == sceneId }
        val lastIdx = currentShots.indexOfLast { it.sceneId == sceneId }
        val updatedShots = PreviewTimelineMapper.buildShotSpecs(plan, capturesMap)
        val replacementItems = updatedShots
            .filter { it.sceneId == sceneId }
            .map { PreviewTimelineMapper.toMediaItem(it) }

        currentShots = updatedShots
        if (firstIdx >= 0 && lastIdx >= firstIdx) {
            exo.replaceMediaItems(firstIdx, lastIdx + 1, replacementItems)
        } else {
            exo.setMediaItems(updatedShots.map { PreviewTimelineMapper.toMediaItem(it) })
        }
        sceneReplacements++
    }

    fun seekToFrame(frameIndex: Int) {
        val plan = currentPlan ?: return
        val exo = player ?: return
        val targetMs = (frameIndex.coerceIn(0, plan.totalFrames) * 1000L) / plan.fps
        var accMs = 0L
        for (i in currentShots.indices) {
            val durMs = currentShots[i].expectedOutDurationUs / 1000L
            if (targetMs < accMs + durMs || i == currentShots.lastIndex) {
                exo.seekTo(i, (targetMs - accMs).coerceAtLeast(0L))
                return
            }
            accMs += durMs
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun metrics(): LocalPreviewMetrics? {
        val p = currentPlan ?: return null
        return LocalPreviewMetrics(
            templateId = p.templateId,
            templateVersion = p.version,
            totalFrames = p.totalFrames,
            shotCount = currentShots.size,
            textUpdateCount = textUpdates,
            sceneReplacementCount = sceneReplacements
        )
    }

    /**
     * Releases hardware decoder & GL FBO VRAM cleanly before running Media3 final export.
     */
    fun release() {
        player?.release()
        player = null
        currentOverlay = null
        currentPlan = null
        currentShots = emptyList()
    }
}
