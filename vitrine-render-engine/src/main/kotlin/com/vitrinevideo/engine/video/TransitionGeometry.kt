package com.vitrinevideo.engine.video

import com.vitrinevideo.engine.interpreter.TransitionSpec
import com.vitrinevideo.engine.interpreter.TransitionType
import kotlin.math.max
import kotlin.math.min

data class NormalizedRect(val x: Float, val y: Float, val w: Float, val h: Float)

data class TransitionMaskState(
    val type: TransitionType,
    val progressQ: Float,
    val rects: List<NormalizedRect> = emptyList(),
    val diagonalTopX: Float = 0f,
    val diagonalBottomX: Float = 0f,
    val alphaIncoming: Float = 1f,
    val direction: String = "right"
)

/**
 * Exact normalized transition geometry matching reference `motion.js` & `film.py`.
 */
object TransitionGeometry {

    fun smoothstep(t: Float): Float {
        val c = max(0f, min(1f, t))
        return c * c * (3f - 2f * c)
    }

    fun evaluate(spec: TransitionSpec, frame: Int): TransitionMaskState? {
        if (frame < spec.startFrame || frame >= spec.startFrame + spec.durationFrames) return null
        if (spec.durationFrames <= 1 || spec.type == TransitionType.CUT || spec.type == TransitionType.PUNCH) {
            return TransitionMaskState(spec.type, 1f, listOf(NormalizedRect(0f, 0f, 1f, 1f)), alphaIncoming = 1f)
        }
        val u = (frame - spec.startFrame).toFloat() / (spec.durationFrames - 1).toFloat()
        val q = smoothstep(u)

        return when (spec.type) {
            TransitionType.QUATRE_VOLETS -> {
                val rects = (0 until 4).map { i ->
                    val t = smoothstep((u - i * 0.07f) / 0.79f)
                    val xBase = (i % 2) * 0.5f
                    val yBase = (i / 2) * 0.5f
                    val x = xBase + if (i % 2 != 0) 0f else (1f - t) * 0.5f
                    NormalizedRect(x, yBase, t * 0.5f, 0.5f)
                }
                TransitionMaskState(spec.type, q, rects = rects)
            }
            TransitionType.CASE_VERS_PLEIN_CADRE -> {
                val rect = NormalizedRect(0f, 0.5f * (1f - q), 0.5f + 0.5f * q, 0.5f + 0.5f * q)
                TransitionMaskState(spec.type, q, rects = listOf(rect))
            }
            TransitionType.BALAYAGE_DIAGONAL -> {
                val bottomX = -0.24f + 1.24f * q
                TransitionMaskState(
                    type = spec.type,
                    progressQ = q,
                    diagonalTopX = bottomX + 0.24f,
                    diagonalBottomX = bottomX
                )
            }
            TransitionType.WIPE_DIRECTIONNEL -> {
                // Full-frame wipe only: no cards, mosaics or decorative layers.
                // An existing contract without a declared direction keeps the
                // historical left-edge reveal.
                val rect = if (spec.direction.equals("left", ignoreCase = true))
                    NormalizedRect(1f - q, 0f, q, 1f)
                else
                    NormalizedRect(0f, 0f, q, 1f)
                TransitionMaskState(spec.type, q, rects = listOf(rect), direction = spec.direction)
            }
            TransitionType.CROSSFADE -> {
                TransitionMaskState(spec.type, q, alphaIncoming = q)
            }
            TransitionType.CUT, TransitionType.PUNCH -> {
                TransitionMaskState(spec.type, 1f, listOf(NormalizedRect(0f, 0f, 1f, 1f)))
            }
            TransitionType.LAMELLES_OUVERTURE,
            TransitionType.LAMELLES_FERMETURE,
            TransitionType.ECLAT_COLORE,
            TransitionType.PAPIER_OBLIQUE,
            TransitionType.POUSSEE_VOILE,
            TransitionType.FENTE_HORIZONTALE,
            TransitionType.TRANCHE_PIP,
            TransitionType.BANDE_OBLIQUE_RECUL -> TransitionMaskState(spec.type, q)
        }
    }

    /**
     * Evaluates whether normalized point (nx, ny) in [0..1]x[0..1] reveals incoming frame (true)
     * or holds outgoing frame (false), or returns blend alpha in [0f..1f].
     */
    fun sampleIncomingAlpha(state: TransitionMaskState, nx: Float, ny: Float): Float {
        return when (state.type) {
            TransitionType.CROSSFADE -> state.alphaIncoming
            TransitionType.BALAYAGE_DIAGONAL -> {
                val edgeX = state.diagonalTopX + (state.diagonalBottomX - state.diagonalTopX) * ny
                if (nx <= edgeX) 1f else 0f
            }
            else -> {
                val inside = state.rects.any { r ->
                    nx >= r.x && nx <= r.x + r.w && ny >= r.y && ny <= r.y + r.h
                }
                if (inside) 1f else 0f
            }
        }
    }
}
