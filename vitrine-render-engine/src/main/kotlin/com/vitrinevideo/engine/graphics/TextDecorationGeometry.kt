package com.vitrinevideo.engine.graphics

import kotlin.math.max

/**
 * Pure resolver for price strikethrough width.
 *
 * A partition may state `texte.prix_barre_px` explicitly; when it does, that
 * value is authoritative. The legacy proportional fallback is only used when
 * the JSON does not declare a width.
 */
object TextDecorationGeometry {
    fun strikethroughWidthPx(fontSizePx: Float, configuredPx: Double?): Float {
        val declared = configuredPx?.toFloat()?.takeIf { it > 0f && it.isFinite() }
        return declared ?: max(4f, fontSizePx * 0.08f)
    }
}
