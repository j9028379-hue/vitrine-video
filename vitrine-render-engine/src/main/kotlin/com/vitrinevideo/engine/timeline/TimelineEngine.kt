package com.vitrinevideo.engine.timeline

import com.vitrinevideo.engine.interpreter.*
import java.security.MessageDigest
import kotlin.math.floor

data class FrameTimelineState(
    val frameIndex: Int,
    val presentationTimeUs: Long,
    val scene: SceneSpec,
    val shot: CutSegmentSpec,
    val gridOrder: List<Int>?,
    val activeTransition: TransitionSpec?
)

class TimelineEngine(val plan: RenderPlan) {

    fun frameToUs(frame: Int): Long =
        (frame.toLong() * 1_000_000L) / plan.fps.toLong()

    fun usToFrame(presentationTimeUs: Long): Int {
        if (presentationTimeUs <= 0L) return 0
        val f = floor((presentationTimeUs.toDouble() * plan.fps.toDouble()) / 1_000_000.0 + 0.0001).toInt()
        return f.coerceIn(0, plan.totalFrames - 1)
    }

    fun sceneAtFrame(frame: Int): SceneSpec =
        plan.scenes.firstOrNull { frame in it.startFrame until it.endFrameExcluded }
            ?: plan.scenes.last()

    fun shotAtFrame(frame: Int): CutSegmentSpec {
        val sc = sceneAtFrame(frame)
        return sc.shots.firstOrNull { frame in it.outStartFrame until it.outEndFrameExcluded }
            ?: sc.shots.last()
    }

    fun gridOrderAtFrame(shot: CutSegmentSpec, frame: Int): List<Int>? {
        val grid = shot.gridLayout ?: return null
        return grid.cellCuts.lastOrNull { it.atFrame <= frame }?.order
            ?: grid.cellCuts.firstOrNull()?.order
            ?: listOf(0, 1, 2, 3)
    }

    fun transitionAtFrame(frame: Int): TransitionSpec? =
        plan.transitions.firstOrNull { frame >= it.startFrame && frame < it.startFrame + it.durationFrames }

    fun stateAtFrame(frame: Int): FrameTimelineState {
        val clamped = frame.coerceIn(0, plan.totalFrames - 1)
        val sc = sceneAtFrame(clamped)
        val shot = shotAtFrame(clamped)
        return FrameTimelineState(
            frameIndex = clamped,
            presentationTimeUs = frameToUs(clamped),
            scene = sc,
            shot = shot,
            gridOrder = gridOrderAtFrame(shot, clamped),
            activeTransition = transitionAtFrame(clamped)
        )
    }

    companion object {
        fun computeRunId(
            templateId: String,
            templateVersion: String,
            responses: Map<String, String>,
            captureSha256s: List<Pair<String, String>>
        ): String {
            val canonical = buildString {
                append(templateId).append('|').append(templateVersion).append('|')
                responses.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v).append(';') }
                append('|')
                captureSha256s.forEach { (sc, h) -> append(sc).append(':').append(h).append(',') }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
