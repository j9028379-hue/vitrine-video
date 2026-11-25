package com.vitrinevideo.engine.graphics

import com.vitrinevideo.engine.interpreter.TextOverlaySpec
import com.vitrinevideo.engine.interpreter.TextWindowSpec
import java.util.Locale

data class ResolvedTextWindow(
    val spec: TextWindowSpec,
    val lines: List<String>,
    val fontSizePx: Float,
    val baseY: Float
)

/**
 * Pure Kotlin text resolver & multi-line wrapper implementing:
 * - `liaison_champs` resolution
 * - Optional field omission (e.g. empty `prix` -> omitted cleanly, never placeholder text)
 * - Template placeholders `{key}` (for T5-style inline `lignes`)
 * - 1..N line balanced splitting + font auto-shrink fitting within `largeur_max_px`
 */
object TextFieldResolver {

    fun resolveFieldText(
        window: TextWindowSpec,
        spec: TextOverlaySpec,
        responses: Map<String, String>
    ): String {
        if (window.whiteTextTemplate != null || window.accentTextTemplate != null) {
            fun render(template: String?): String = template.orEmpty().replace(Regex("\\{([a-zA-Z0-9_]+)\\}")) { m ->
                responses[m.groupValues[1]]?.trim().orEmpty()
            }.trim()
            val toCase: (String) -> String = if (spec.caseMode.equals("majuscules", ignoreCase = true)) {
                { it.uppercase(Locale.ROOT) }
            } else {
                { it }
            }
            val white = toCase(render(window.whiteTextTemplate))
            val accent = toCase(render(window.accentTextTemplate))
            return listOf(white, accent).filter { it.isNotBlank() }.joinToString(" ")
        }
        val tplLines = window.templateLines
        if (tplLines != null) {
            val rendered = tplLines.map { line ->
                Regex("\\{([a-zA-Z0-9_]+)\\}").replace(line) { m ->
                    responses[m.groupValues[1]]?.trim().orEmpty()
                }.trim()
            }.filter { it.isNotEmpty() }
            return rendered.joinToString("\n")
        }

        val keys = spec.fieldBindings[window.fieldKey]
        if (keys == null) {
            return responses[window.fieldKey]?.trim().orEmpty()
        }
        val values = keys.mapNotNull { k -> responses[k]?.trim()?.takeIf { it.isNotEmpty() } }
        if (values.isEmpty()) return ""
        val joined = values.joinToString("\n")
        return if (!window.skipAutoWhatsappPrefix && (window.fieldKey == "contact" || window.fieldKey == "whatsapp") &&
            !joined.startsWith("WhatsApp", ignoreCase = true)
        ) {
            "WhatsApp\n$joined"
        } else {
            joined
        }
    }

    fun layoutWindow(
        window: TextWindowSpec,
        spec: TextOverlaySpec,
        responses: Map<String, String>,
        measureWidth: (text: String, fontSizePx: Float) -> Float
    ): ResolvedTextWindow? {
        val text = resolveFieldText(window, spec, responses)
        if (text.isBlank()) return null

        val useEndStyle = spec.endSectionStyle != null &&
            spec.endSectionStartFrame != null &&
            window.startFrame >= spec.endSectionStartFrame
        val style = window.overrideStyle ?: if (useEndStyle) spec.endSectionStyle!! else spec.defaultStyle

        val maxLines = window.maxLines ?: spec.maxLinesByField[window.fieldKey] ?: 2
        val maxW = style.maxWidthPx.toFloat()
        var sz = style.fontSizePx.toFloat()
        val minimumSize = maxOf(14f, window.minFontSizePx?.toFloat() ?: 14f)

        var lines = splitBalancedLines(text, maxLines, sz, maxW, measureWidth)
        while (sz > minimumSize && lines.any { measureWidth(it, sz) > maxW }) {
            sz -= 1f
            lines = splitBalancedLines(text, maxLines, sz, maxW, measureWidth)
        }
        val maxHeight = window.maxHeightPx?.toFloat() ?: Float.POSITIVE_INFINITY
        val lineSpacing = window.overrideStyle?.lineSpacingPx?.toFloat()
            ?: spec.defaultStyle.lineSpacingPx.toFloat() * (sz / spec.defaultStyle.fontSizePx.toFloat())
        while (sz > 14f && lines.size * lineSpacing > maxHeight) sz -= 1f

        val baseY = when {
            window.customY != null -> window.customY.toFloat()
            lines.size == 1 -> style.singleLineBaseY.toFloat()
            else -> style.twoLineBaseY.toFloat()
        }

        return ResolvedTextWindow(
            spec = window,
            lines = lines,
            fontSizePx = sz,
            baseY = baseY
        )
    }

    private fun splitBalancedLines(
        text: String,
        maxLines: Int,
        fontSizePx: Float,
        maxWidthPx: Float,
        measureWidth: (String, Float) -> Float
    ): List<String> {
        if ('\n' in text) {
            return text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        if (measureWidth(text.trim(), fontSizePx) <= maxWidthPx || maxLines <= 1 || words.size == 1) {
            return listOf(words.joinToString(" "))
        }
        if (maxLines == 2) {
            var bestIdx = 1
            var bestMax = Float.MAX_VALUE
            for (i in 1 until words.size) {
                val l1 = words.subList(0, i).joinToString(" ")
                val l2 = words.subList(i, words.size).joinToString(" ")
                val m = maxOf(measureWidth(l1, fontSizePx), measureWidth(l2, fontSizePx))
                if (m < bestMax) {
                    bestMax = m
                    bestIdx = i
                }
            }
            return listOf(
                words.subList(0, bestIdx).joinToString(" "),
                words.subList(bestIdx, words.size).joinToString(" ")
            )
        }
        if (maxLines == 3 && words.size >= 3) {
            var bestSplit: List<String>? = null
            var bestMax = Float.MAX_VALUE
            for (i in 1 until words.size - 1) {
                for (j in i + 1 until words.size) {
                    val l1 = words.subList(0, i).joinToString(" ")
                    val l2 = words.subList(i, j).joinToString(" ")
                    val l3 = words.subList(j, words.size).joinToString(" ")
                    val m = maxOf(
                        measureWidth(l1, fontSizePx),
                        measureWidth(l2, fontSizePx),
                        measureWidth(l3, fontSizePx)
                    )
                    if (m < bestMax) {
                        bestMax = m
                        bestSplit = listOf(l1, l2, l3)
                    }
                }
            }
            if (bestSplit != null) return bestSplit
        }
        // Greedy line wrapping up to maxLines
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (current.isNotEmpty() && measureWidth(candidate, fontSizePx) > maxWidthPx && out.size + 1 < maxLines) {
                out += current.toString()
                current = StringBuilder(w)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(w)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
