package com.vitrinevideo.app.camera

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/**
 * The single owner-approved 3 → 2 → 1 → ON FILME ! sequence.
 *
 * It runs after the merchant presses COMMENCER À FILMER / DÉMARRER
 * L'ENREGISTREMENT and strictly before CameraX starts recording, for every
 * shipped template (Commerce T1–T4, Restauration T2 and Restauration T5).
 *
 * The countdown is transient commercial feedback only:
 * - it is never written into the captured file,
 * - it adds zero frames and zero seconds to the take,
 * - official scene durations still start when CameraX actually starts,
 * - it can never launch a second recording (re-entry is ignored).
 */
class RecordingCountdown(
    private val host: ViewGroup,
    private val isFrench: () -> Boolean
) {
    companion object {
        const val STEP_MS = 700L
        const val FINAL_HOLD_MS = 450L
        private val SEQUENCE = listOf("3", "2", "1")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var running = false

    val isRunning: Boolean get() = running

    fun start(onFinished: () -> Unit) {
        if (running) return
        running = true
        val view = buildOverlay()
        overlay = view
        host.addView(view)
        advance(view, index = 0, onFinished = onFinished)
    }

    /** Drops the sequence and never invokes its completion callback. */
    fun cancel() {
        mainHandler.removeCallbacksAndMessages(null)
        overlay?.let { host.removeView(it) }
        overlay = null
        running = false
    }

    private fun advance(view: TextView, index: Int, onFinished: () -> Unit) {
        if (index < SEQUENCE.size) {
            view.text = SEQUENCE[index]
            mainHandler.postDelayed({ advance(view, index + 1, onFinished) }, STEP_MS)
            return
        }
        view.text = if (isFrench()) "ON FILME !" else "WE'RE FILMING!"
        mainHandler.postDelayed({
            host.removeView(view)
            overlay = null
            running = false
            onFinished()
        }, FINAL_HOLD_MS)
    }

    @Suppress("DEPRECATION")
    private fun buildOverlay(): TextView {
        val density = host.resources.displayMetrics.density
        return TextView(host.context).apply {
            text = SEQUENCE.first()
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 76f)
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setShadowLayer(6f * density, 0f, 2f * density, Color.argb(120, 0, 0, 0))
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 10, 21, 80))
            }
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
}
