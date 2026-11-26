package com.vitrinevideo.engine.graphics

import com.vitrinevideo.engine.interpreter.TextMotionGroupSpec
import kotlin.math.max
import kotlin.math.round

/**
 * Frame-indexed port of the official RESTAURATION-T3 text-motion reference
 * (`engine/text_motion.py` in the signed source archive). Group timing and
 * geometry are supplied by the packaged T3 contract, not shared-template defaults.
 */
data class TextMotionFrame(
    val progress: Float,
    val opacity: Float,
    val riseOffsetPx: Float,
    val priceSettleOffsetPx: Float,
    val revealWidthPx: Float
)

object TextMotionEvaluator {
    private val SUPPORTED_SOURCE_MOTIONS = setOf(
        "left_to_right_mask_plus_soft_rise",
        "split_reveal_with_price_settle",
        "center_bloom_and_signature",
        "sequential_identity_contact_lockup"
    )

    fun needsFrameUpdate(group: TextMotionGroupSpec, frame: Int, totalFrames: Int): Boolean =
        frame < group.revealEndFrame ||
            (group.endFrameExcluded != totalFrames && frame >= group.endFrameExcluded - 18)

    fun evaluate(group: TextMotionGroupSpec, frame: Int, canvasWidth: Int, totalFrames: Int): TextMotionFrame? {
        require(group.motionName in SUPPORTED_SOURCE_MOTIONS) {
            "Unsupported RESTAURATION-T3 source motion: ${group.motionName}"
        }
        require(group.risePx >= 0.0 && group.priceRisePx >= 0.0) {
            "Negative source motion geometry for ${group.id}"
        }
        if (frame < group.startFrame || frame >= group.endFrameExcluded) return null
        val denominator = max(1, group.revealEndFrame - group.startFrame)
        val raw = ((frame - group.startFrame + 1).toFloat() / denominator).coerceIn(0f, 1f)
        val progress = smooth(raw)
        val terminalFade = if (group.endFrameExcluded == totalFrames) {
            1f
        } else {
            val fadeStart = group.endFrameExcluded - 18
            if (frame < fadeStart) 1f else smooth(
                ((group.endFrameExcluded - frame).toFloat() / 18f).coerceIn(0f, 1f)
            )
        }
        val opacity = (progress * terminalFade).coerceIn(0f, 1f)
        val priceRise = if (group.motionName == "split_reveal_with_price_settle") {
            ((1f - progress) * group.priceRisePx).toInt().toFloat()
        } else 0f
        return TextMotionFrame(
            progress = progress,
            opacity = opacity,
            riseOffsetPx = round((1f - progress) * group.risePx).toFloat(),
            priceSettleOffsetPx = priceRise,
            revealWidthPx = round(canvasWidth * progress).toFloat()
        )
    }

    fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
