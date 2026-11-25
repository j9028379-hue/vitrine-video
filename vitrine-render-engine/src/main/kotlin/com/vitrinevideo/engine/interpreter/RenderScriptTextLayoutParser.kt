package com.vitrinevideo.engine.interpreter

/**
 * Converts the fixed lettrage of a package's own reference render script into
 * declarative text windows. Only field names, fixed labels, geometry, colours,
 * timing and the darkening band are read from the package; merchant copy always
 * comes from the merchant's form answers at render time.
 */
object RenderScriptTextLayoutParser {

    private data class TextCall(
        val x: Int,
        val y: Int,
        val fieldKey: String?,
        val fixedLabel: String?,
        val size: Int,
        val face: String,
        val colorName: String
    )

    private data class TextGroup(
        val sceneIndex: Int,
        val phase: Int,
        val calls: List<TextCall>
    )

    private val colorDefinition = Regex("""([A-Za-z_]+)=\((\d+),(\d+),(\d+)(?:,(\d+))?\)""")
    private val textCall = Regex(
        """text\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(ans\['([^']+)'\]|'([^']+)')\s*,\s*(\d+)\s*,\s*'([^']+)'\s*(?:,\s*([A-Za-z_]+))?\s*\)"""
    )
    private val sceneLine = Regex("""(?:if|elif)\s+j==(\d+)\s*:""")
    private val phaseZero = Regex("""if\s+phase==0\s*:""")
    private val pairPattern = Regex("""\((\d+)\s*,\s*(\d+)\)""")

    fun parseTextOverlay(script: String, scenes: List<SceneSpec>, width: Int, height: Int): TextOverlaySpec? {
        val colors = parseColors(script)
        val groups = parseGroups(script)
        val frames = parseWindows(script, groups)
        val windows = mutableListOf<TextWindowSpec>()
        groups.forEachIndexed { gi, group ->
            val range = frames[gi] ?: return@forEachIndexed
            group.calls.forEach { call ->
                val family = if (call.face.equals("serif", ignoreCase = true)) "serif" else "sans"
                val key = call.fieldKey ?: "__label_" + call.fixedLabel!!.replace(Regex("[^A-Za-z0-9]+"), "_").lowercase()
                windows += TextWindowSpec(
                    startFrame = range.first,
                    endFrameExcluded = range.second,
                    fieldKey = key,
                    templateLines = call.fixedLabel?.let { listOf(it) },
                    entryAnim = TextEntryAnim.FONDU,
                    exitFadeFrames = 8,
                    enterFrames = 8,
                    customY = call.y.toDouble() + call.size.toDouble() * 0.80,
                    leftXPx = call.x.toDouble(),
                    fontFamilyOverride = family,
                    inkColorHex = colors[call.colorName] ?: "#FFFFFF",
                    skipAutoWhatsappPrefix = call.fieldKey == "whatsapp" || call.fixedLabel?.contains("WhatsApp") == true,
                    maxLines = 2,
                    overrideStyle = TextStyleSpec(
                        fontSizePx = call.size.toDouble(),
                        maxWidthPx = 624.0,
                        lineSpacingPx = call.size * 1.15,
                        twoLineBaseY = call.y + call.size * 0.80,
                        singleLineBaseY = call.y + call.size * 0.80,
                        emRefPx = call.size.toDouble()
                    )
                )
            }
        }
        if (windows.isEmpty()) return null
        val ramp = parseRamp(script)
        return TextOverlaySpec(
            fontFamily = "sans",
            fontWeight = 400,
            fontRelativePath = "",
            colorHex = colors["cream"] ?: "#FFFFFF",
            defaultStyle = TextStyleSpec(48.0, 624.0, 56.0, height * 0.72, height * 0.72, 48.0),
            endSectionStyle = null,
            endSectionStartFrame = null,
            exportBand = BandExportSpec(0, height),
            relief = TextReliefSpec(
                shadowColorRgba = "rgba(38,27,17,130)",
                shadowDxPx = 0.0,
                shadowDyPx = 0.0,
                shadowBlurPx = 0.0,
                strokeColorRgba = "rgba(38,27,17,130)",
                strokeWidthPx = 1.0
            ),
            wave = null,
            fieldBindings = emptyMap(),
            maxLinesByField = emptyMap(),
            windows = windows,
            fadeFrames = ramp
        )
    }

    fun parseEffects(script: String, scenes: List<SceneSpec>): List<VisualEffectSpec> {
        val groups = parseGroups(script)
        val frames = parseWindows(script, groups)
        val scrim = parseScrim(script) ?: return emptyList()
        val result = mutableListOf<VisualEffectSpec>()
        groups.forEachIndexed { index, group ->
            val window = frames[index] ?: return@forEachIndexed
            val scene = scenes.getOrNull(group.sceneIndex) ?: return@forEachIndexed
            require(window.first in scene.startFrame until scene.endFrameExcluded) {
                "Texte hors scène: ${scene.id} / ${window.first}"
            }
            result += VisualEffectSpec(
                id = "${scene.id}_scrim_${group.phase}",
                operation = "ombre_portee_texte",
                kind = EffectKind.SCRIM,
                startFrame = window.first,
                endFrameExcluded = window.second,
                centerY = scrim.centerY,
                spreadY = scrim.spreadY,
                strength = scrim.strength,
                visibilityRampFrames = scrim.rampFrames
            )
        }
        return result
    }

    /** Window pairs are read in source order from `a,b=(...)` in the reference script. */
    private fun parseWindows(script: String, groups: List<TextGroup>): Map<Int, Pair<Int, Int>> {
        val line = script.lineSequence().firstOrNull {
            it.contains("a,b=") && it.contains("phase]")
        } ?: script
        val pairs = pairPattern.findAll(line)
            .map { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() }
            .toList()
        return groups.indices.associateWith { i -> pairs.getOrElse(i) { 0 to 0 } }
    }

    private fun parseGroups(script: String): List<TextGroup> {
        val result = mutableListOf<TextGroup>()
        var sceneIndex: Int? = null
        var phase = 0
        for (line in script.lineSequence()) {
            sceneLine.find(line)?.let { sceneIndex = it.groupValues[1].toInt() }
            if (phaseZero.containsMatchIn(line)) phase = 0
            else if (line.trimStart().startsWith("else:text(")) phase = 1
            val calls = textCall.findAll(line).map { m ->
                TextCall(
                    x = m.groupValues[1].toInt(),
                    y = m.groupValues[2].toInt(),
                    fieldKey = m.groupValues[4].takeIf { it.isNotBlank() },
                    fixedLabel = m.groupValues[5].takeIf { it.isNotBlank() },
                    size = m.groupValues[6].toInt(),
                    face = m.groupValues[7],
                    colorName = m.groupValues[8].ifBlank { "cream" }
                )
            }.toList()
            if (calls.isNotEmpty() && sceneIndex != null) result += TextGroup(sceneIndex!!, phase, calls)
        }
        return result
    }

    private fun parseColors(script: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        colorDefinition.findAll(script).forEach { m ->
            val r = m.groupValues[2].toInt().coerceIn(0, 255)
            val g = m.groupValues[3].toInt().coerceIn(0, 255)
            val b = m.groupValues[4].toInt().coerceIn(0, 255)
            out[m.groupValues[1]] = "#%02X%02X%02X".format(r, g, b)
        }
        return out
    }

    private fun parseRamp(script: String): Int {
        val line = script.lineSequence().firstOrNull { it.contains("visibility=np.clip") } ?: return 8
        return Regex("""\(i-a\)/(\d+)""").find(line)?.groupValues?.get(1)?.toInt() ?: 8
    }

    private data class Scrim(val centerY: Double, val spreadY: Double, val strength: Double, val rampFrames: Int)

    private fun parseScrim(script: String): Scrim? {
        val line = script.lineSequence().firstOrNull { it.contains("visibility") && it.contains("np.exp") } ?: return null
        val strength = Regex("""1-([0-9.]+)\*visibility""").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val gauss = Regex("""yy-([0-9.]+)\)/([0-9.]+)""").find(line) ?: return null
        return Scrim(
            centerY = gauss.groupValues[1].toDouble(),
            spreadY = gauss.groupValues[2].toDouble(),
            strength = strength,
            rampFrames = parseRamp(script)
        )
    }
}
