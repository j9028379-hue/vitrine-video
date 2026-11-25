package com.vitrinevideo.engine.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import com.vitrinevideo.engine.interpreter.EffectCurveEvaluator
import com.vitrinevideo.engine.interpreter.EffectKind
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.timeline.TimelineEngine

/**
 * Applies declared final-stage effects (e.g. `fondu_noir`) after text compositing.
 * A single reusable full-frame bitmap is allocated only when such an effect exists.
 */
@OptIn(UnstableApi::class)
class VitrineDeclarativeFinalOverlay(private val plan: RenderPlan) : BitmapOverlay() {

    private val timeline = TimelineEngine(plan)
    private val effects = plan.visualEffects.filter { it.kind == EffectKind.FINAL_FADE }
    private val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val frameBitmap: Bitmap? = if (effects.isEmpty()) null else
        Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
    private val canvas: Canvas? = frameBitmap?.let { Canvas(it) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (frameBitmap == null || canvas == null) return emptyBitmap
        val frame = timeline.usToFrame(presentationTimeUs)
        val active = effects.filter { frame in it.startFrame until it.endFrameExcluded }
        if (active.isEmpty()) return emptyBitmap
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (effect in active) {
            val opacity = EffectCurveEvaluator.valueAt(effect.opacityKeyframes, frame) *
                EffectCurveEvaluator.visibilityAt(
                    frame, effect.startFrame, effect.endFrameExcluded, effect.visibilityRampFrames
                )
            val alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
            if (alpha <= 0) continue
            val color = runCatching { Color.parseColor(effect.colorHex ?: "#000000") }
                .getOrDefault(Color.BLACK)
            canvas.drawARGB(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }
        return frameBitmap
    }

    override fun release() {
        super.release()
        if (!emptyBitmap.isRecycled) emptyBitmap.recycle()
        frameBitmap?.takeIf { !it.isRecycled }?.recycle()
    }

    companion object {
        fun createEffect(plan: RenderPlan): OverlayEffect? {
            if (plan.visualEffects.none { it.kind == EffectKind.FINAL_FADE }) return null
            return OverlayEffect(listOf(VitrineDeclarativeFinalOverlay(plan)))
        }
    }
}
