package com.vitrinevideo.engine.graphics

import com.vitrinevideo.engine.interpreter.TextEntryAnim
import com.vitrinevideo.engine.interpreter.TextOverlaySpec
import com.vitrinevideo.engine.interpreter.TextWindowSpec
import com.vitrinevideo.engine.interpreter.WaveKeyframe
import com.vitrinevideo.engine.interpreter.WaveSpec
import kotlin.math.max
import kotlin.math.min

data class CharTransform(
    val dxPx: Float,
    val dyPx: Float,
    val rotationDeg: Float,
    val scale: Float,
    val alpha: Float
)

/**
 * Pure Kotlin mathematical evaluator for timed text entry/exit animations:
 * - `coupe`
 * - `fondu`
 * - `vague` (16-keyframe per-character wave matching reference `motion.js` / `partition.json`)
 * - `slide_pop`
 */
object WaveCurveEvaluator {

    fun interpolateWave(keyframes: List<WaveKeyframe>, ageRefFrames: Double): CharTransform {
        if (keyframes.isEmpty()) return CharTransform(0f, 0f, 0f, 1f, 1f)
        if (ageRefFrames < 0.0) return CharTransform(0f, 0f, 0f, 0f, 0f)
        val last = keyframes.last()
        if (ageRefFrames >= last.ageRefFrames) {
            return CharTransform(
                dxPx = last.dxPx.toFloat(),
                dyPx = last.dyPx.toFloat(),
                rotationDeg = last.rotationDeg.toFloat(),
                scale = last.scale.toFloat(),
                alpha = 1f
            )
        }
        for (i in 0 until keyframes.size - 1) {
            val a = keyframes[i]
            val b = keyframes[i + 1]
            if (ageRefFrames >= a.ageRefFrames && ageRefFrames <= b.ageRefFrames) {
                val span = max(1e-6, b.ageRefFrames - a.ageRefFrames)
                val t = (ageRefFrames - a.ageRefFrames) / span
                return CharTransform(
                    dxPx = (a.dxPx + (b.dxPx - a.dxPx) * t).toFloat(),
                    dyPx = (a.dyPx + (b.dyPx - a.dyPx) * t).toFloat(),
                    rotationDeg = (a.rotationDeg + (b.rotationDeg - a.rotationDeg) * t).toFloat(),
                    scale = (a.scale + (b.scale - a.scale) * t).toFloat(),
                    alpha = 1f
                )
            }
        }
        return CharTransform(0f, 0f, 0f, 1f, 1f)
    }

    fun evaluateLineAlpha(window: TextWindowSpec, spec: TextOverlaySpec, frame: Int): Float {
        if (frame < window.startFrame || frame >= window.endFrameExcluded) return 0f
        var alpha = when (window.entryAnim) {
            TextEntryAnim.COUPE -> 1f
            TextEntryAnim.FONDU -> {
                val d = max(1, spec.fadeFrames).toFloat()
                min(1f, (frame - window.startFrame).toFloat() / d)
            }
            TextEntryAnim.SLIDE_POP -> {
                val d = max(1, spec.fadeFrames).toFloat()
                min(1f, (frame - window.startFrame).toFloat() / d)
            }
            TextEntryAnim.VAGUE -> 1f
        }
        if (window.exitFadeFrames > 0) {
            val rem = (window.endFrameExcluded - frame).toFloat()
            alpha = min(alpha, rem / window.exitFadeFrames.toFloat())
        }
        return alpha.coerceIn(0f, 1f)
    }

    fun evaluateCharTransform(
        wave: WaveSpec,
        startFrame: Int,
        frame: Int,
        lineIndex: Int,
        nonSpaceCharIndexBefore: Int,
        fontSizePx: Float,
        emRefPx: Double
    ): CharTransform {
        val ratio = (fontSizePx.toDouble() / max(1.0, emRefPx)).toFloat()
        val delayRef = nonSpaceCharIndexBefore * wave.charIntervalRefFrames + lineIndex * wave.linePauseRefFrames
        val ageRef = (frame - startFrame).toDouble() / max(0.01, wave.timeAdaptation) - delayRef
        val raw = interpolateWave(wave.keyframes, ageRef)
        return CharTransform(
            dxPx = raw.dxPx * ratio,
            dyPx = raw.dyPx * ratio,
            rotationDeg = raw.rotationDeg,
            scale = raw.scale,
            alpha = raw.alpha
        )
    }
}
