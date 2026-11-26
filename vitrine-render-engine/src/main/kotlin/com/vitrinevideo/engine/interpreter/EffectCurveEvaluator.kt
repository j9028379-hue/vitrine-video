package com.vitrinevideo.engine.interpreter

import kotlin.math.max
import kotlin.math.min

/** Pure frame-domain evaluation shared by the JSON parser, GPU uniforms and JVM tests. */
object EffectCurveEvaluator {

    fun valueAt(frames: List<EffectKeyframe>, frame: Int): Double {
        if (frames.isEmpty()) return 0.0
        if (frame <= frames.first().frame) return frames.first().value
        if (frame >= frames.last().frame) return frames.last().value
        for (i in 1 until frames.size) {
            val a = frames[i - 1]
            val b = frames[i]
            if (frame in a.frame..b.frame) {
                val t = (frame - a.frame).toDouble() / max(1, b.frame - a.frame).toDouble()
                return a.value + (b.value - a.value) * t
            }
        }
        return frames.last().value
    }

    fun rampAt(zoom: Pair<Double, Double>?, frame: Int, startFrame: Int, endFrameExcluded: Int): Double {
        if (zoom == null) return 1.0
        val span = max(1, endFrameExcluded - startFrame - 1)
        val t = ((frame - startFrame).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        return zoom.first + (zoom.second - zoom.first) * t
    }

    fun visibilityAt(frame: Int, startFrame: Int, endFrameExcluded: Int, rampFrames: Int): Double {
        if (rampFrames <= 0) return 1.0
        val entering = (frame - startFrame).toDouble() / rampFrames.toDouble()
        val leaving = (endFrameExcluded - 1 - frame).toDouble() / rampFrames.toDouble()
        return max(0.0, min(1.0, min(entering, leaving)))
    }

    fun pairAt(start: Pair<Double, Double>?, end: Pair<Double, Double>?, frame: Int,
               fromFrame: Int, toFrameExcluded: Int): Pair<Double, Double>? {
        if (start == null || end == null) return start ?: end
        val span = max(1, toFrameExcluded - fromFrame - 1)
        val t = ((frame - fromFrame).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        return start.first + (end.first - start.first) * t to
            start.second + (end.second - start.second) * t
    }

    fun viewportCxAt(keys: List<ViewportKeyframe>, progress: Double): Double {
        if (keys.isEmpty()) return 0.5
        if (progress <= keys.first().progress) return keys.first().cx
        if (progress >= keys.last().progress) return keys.last().cx
        for (i in 1 until keys.size) {
            val a = keys[i - 1]
            val b = keys[i]
            if (progress in a.progress..b.progress) {
                val span = b.progress - a.progress
                val t = if (span <= 0.0) 1.0 else (progress - a.progress) / span
                return a.cx + (b.cx - a.cx) * t
            }
        }
        return keys.last().cx
    }
}
