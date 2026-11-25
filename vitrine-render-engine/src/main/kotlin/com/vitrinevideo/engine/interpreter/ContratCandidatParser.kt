package com.vitrinevideo.engine.interpreter

import com.vitrinevideo.engine.capabilities.CapabilityId
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Parser for the capture/render contract used by COMMERCE-T1 2.x packages. */
object ContratCandidatParser {
    fun canParse(json: JSONObject): Boolean =
        json.optString("template_id").isNotBlank() && json.has("scenes") && json.has("effets") && json.has("typographie")

    fun parse(json: JSONObject, assetRoot: String = "templates/commerce/commerce-t1/assets/fonts/"): RenderPlan {
        val fps = json.optInt("fps", 30)
        val width = json.optInt("largeur", 720)
        val height = json.optInt("hauteur", 1280)
        val totalFrames = (json.optDouble("duree_s", 30.0) * fps).roundToInt()
        val scenesJson = json.getJSONArray("scenes")
        val required = mutableSetOf(
            CapabilityId.VIDEO_SCENE_TRIM,
            CapabilityId.VIDEO_CROP_ZOOM,
            CapabilityId.VIDEO_MOTION_CURVE,
            CapabilityId.COLOR_SOURCE_NATIVE,
            CapabilityId.TEXT_STYLED_WINDOWS,
            CapabilityId.TEXT_MULTI_FONT,
            CapabilityId.TEXT_ANIM_PLUME,
            CapabilityId.TEXT_ANIM_LEVER,
            CapabilityId.TEXT_ANIM_VAGUE_REVEAL,
            CapabilityId.EXPORT_MP4_H264_AAC
        )
        val scenes = mutableListOf<SceneSpec>()
        val textWindows = mutableListOf<TextWindowSpec>()
        val fieldBindings = linkedMapOf<String, List<String>>()
        val sourceEnds = mutableListOf<Int>()
        var cursor = 0

        for (i in 0 until scenesJson.length()) {
            val s = scenesJson.getJSONObject(i)
            val id = s.getString("id")
            val sortie = s.getJSONArray("sortie_s")
            val start = (sortie.getDouble(0) * fps).roundToInt()
            val end = (sortie.getDouble(1) * fps).roundToInt()
            require(start == cursor && end > start) { "Scène $id: intervalle sortie_s discontinu ou invalide" }
            val source = s.getJSONArray("source_s")
            val srcIn = (source.getDouble(0) * fps).roundToInt()
            val srcOut = (source.getDouble(1) * fps).roundToInt()
            require(srcOut > srcIn) { "Scène $id: intervalle source_s invalide" }
            sourceEnds += srcOut

            val duration = end - start
            val split = if (i < 3) start + (duration * 0.55).roundToInt() else end
            val shots = mutableListOf<CutSegmentSpec>()
            val boundaries = if (split in (start + 1) until end) listOf(start, split, end) else listOf(start, end)
            for (phase in 0 until boundaries.lastIndex) {
                val outStart = boundaries[phase]
                val outEnd = boundaries[phase + 1]
                val sourcePhaseStart = srcIn + (outStart - start)
                val sourcePhaseEnd = srcIn + (outEnd - start)
                val isFirst = phase == 0
                shots += CutSegmentSpec(
                    shotId = "${id}_P${phase + 1}", sceneId = id,
                    sourceInFrame = sourcePhaseStart, sourceOutFrame = sourcePhaseEnd,
                    outStartFrame = outStart, outEndFrameExcluded = outEnd,
                    viewport = ViewportSpec(0.5, 0.5, if (i < 3 && !isFirst) 1.05 else 1.0),
                    motion = when {
                        i < 3 && isFirst -> MotionSpec("zoom_in", startScale = 1.0, endScale = 1.05, easing = "smoothstep")
                        i == 3 -> MotionSpec("zoom_in", startScale = 1.0, endScale = 1.05, easing = "smoothstep")
                        else -> MotionSpec("none")
                    }
                )
            }
            scenes += SceneSpec(id, start, end, role = s.optString("titre", s.optString("prise", "")), shots = shots)
            cursor = end
        }

        val effects = json.optJSONArray("effets") ?: JSONArray()
        val transitions = mutableListOf<TransitionSpec>()
        for (i in 0 until effects.length()) {
            val effect = effects.getJSONObject(i)
            val motif = effect.optString("motif")
            val interval = effect.getJSONArray("sortie_s")
            val start = (interval.getDouble(0) * fps).roundToInt()
            val end = (interval.getDouble(1) * fps).roundToInt()
            val type = when (motif.uppercase()) {
                "Q" -> TransitionType.QUATRE_VOLETS
                "DIA" -> TransitionType.BALAYAGE_DIAGONAL
                "PUSH" -> TransitionType.POUSSEE_VOILE
                "FLASH" -> TransitionType.ECLAT_COLORE
                "FADE" -> TransitionType.CROSSFADE
                "CUT" -> TransitionType.CUT
                "BOX" -> TransitionType.CASE_VERS_PLEIN_CADRE
                "A" -> TransitionType.LAMELLES_OUVERTURE
                "B" -> TransitionType.ECLAT_COLORE
                "C" -> TransitionType.PAPIER_OBLIQUE
                "D" -> TransitionType.POUSSEE_VOILE
                "E" -> TransitionType.FENTE_HORIZONTALE
                "F" -> TransitionType.TRANCHE_PIP
                "G" -> TransitionType.BANDE_OBLIQUE_RECUL
                "H" -> TransitionType.LAMELLES_FERMETURE
                // Declared by the Restauration T5 montage adapter. Both gestures
                // use the engine's full-frame directional wipe primitive.
                "T5L", "T5LF", "T5R" -> TransitionType.WIPE_DIRECTIONNEL
                else -> throw IllegalArgumentException("Motif de transition inconnu: $motif")
            }
            required += type.capability
            transitions += TransitionSpec(
                startFrame = start,
                durationFrames = end - start,
                type = type,
                motifId = motif.uppercase(),
                direction = effect.optString("direction", if (motif.uppercase() == "T5R") "right" else "left"),
                // Data-driven only: a contract that declares the reference
                // travel (dish swipe / camera whip) gets the sliding primitive;
                // everything else keeps its current mask geometry.
                slideWhip = effect.optBoolean("full_frame_travel", false),
                whipBlurPx = effect.optDouble("motion_blur_px", 5.0),
                sourceFamily = effect.optString("source_family", ""),
                sourceVariant = effect.optString("source_variant", ""),
                sourceProfile = effect.optString("source_profile", ""),
                sourceScene = effect.optString("source_scene", ""),
                sourceFramesInclusive = effect.optJSONArray("source_frames_inclusive")?.let { frames ->
                    (0 until frames.length()).map { frames.getInt(it) }
                }.orEmpty(),
                sourceDurationFrames = effect.optInt("source_duration_frames", 0),
                sourceParametersJson = effect.optJSONObject("source_parameters")?.toString().orEmpty()
            )
        }

        val fontRoot = json.optString("asset_root", assetRoot).trimEnd('/')
        val fonts = json.optJSONObject("polices") ?: JSONObject()
        val typography = json.getJSONObject("typographie")
        val ink = rgbHex(typography.optJSONArray("encre_rgb"), "#FFF5E0")
        val reliefHex = rgbHex(typography.optJSONArray("relief_rgb"), "#2D1C15")
        val scriptFont = typography.optString("script", "")
        val serifFont = typography.optString("serif", "")
        // A package may declare named font roles (e.g. script/serif/utility/utility_bold)
        // or legacy file-name keys. Both remain data-driven: no template is hardcoded here.
        fun fontFile(alias: String): String = fonts.optString(alias, "").takeIf { it.isNotBlank() }
            ?: when (alias) { "script" -> scriptFont; else -> serifFont }
        val declaredFontHashes = json.optJSONObject("font_hashes") ?: JSONObject()
        fun fontHash(file: String): String = declaredFontHashes.optString(file, "").ifBlank {
            fonts.optString(file, "").takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) }.orEmpty()
        }
        val namedFontPaths = linkedMapOf<String, String>()
        val fontKeys = fonts.keys()
        while (fontKeys.hasNext()) {
            val alias = fontKeys.next()
            val declared = fonts.optString(alias, "")
            // Legacy `polices` maps a file path to its SHA; named roles map a role to a file.
            namedFontPaths[alias] = if (declared.matches(Regex("^[0-9a-fA-F]{64}$"))) alias else fontFile(alias)
        }
        val extraFontPaths = namedFontPaths.mapValues { (_, file) ->
            if (file.startsWith("/")) file else "$fontRoot/${file.trimStart('/')}"
        }.toMutableMap()
        extraFontPaths.putIfAbsent("fallback", "$fontRoot/DejaVuSans.ttf")
        // Named roles map role -> file name; their digests come from `font_hashes`
        // when present, or from a legacy file-name -> digest map.
        val extraFontHashes = namedFontPaths.mapValues { (_, file) -> fontHash(file) }.toMutableMap()
        extraFontHashes.putIfAbsent("fallback", fonts.optString("DejaVuSans.ttf", ""))
        val sourceTextMotion = json.optJSONObject("text_motion_source")
        val motionGroups = sourceTextMotion?.let { source ->
            val scrims = source.optJSONObject("legibility")?.optJSONObject("scrims")
            val graphics = source.optJSONArray("graphics") ?: JSONArray()
            (0 until graphics.length()).map { index ->
                val graphic = graphics.getJSONObject(index)
                val id = graphic.getString("id")
                val scrim = scrims?.optJSONObject(id)
                TextMotionGroupSpec(
                    id = id,
                    motionName = graphic.getString("motion"),
                    startFrame = graphic.getInt("start_f"),
                    revealEndFrame = graphic.getInt("reveal_end_f"),
                    endFrameExcluded = graphic.getInt("end_f_exclusive"),
                    holdThroughFrame = if (graphic.has("hold_through_frame")) graphic.getInt("hold_through_frame") else null,
                    opacityAtEnd = if (graphic.has("graphics_opacity_at_frame_899")) graphic.getDouble("graphics_opacity_at_frame_899") else null,
                    risePx = graphic.optDouble("rise_px", 0.0),
                    priceRisePx = graphic.optDouble("price_rise_px", 0.0),
                    scrimTop = scrim?.optDouble("top", 0.0) ?: 0.0,
                    scrimBottom = scrim?.optDouble("bottom", 0.0) ?: 0.0
                )
            }
        }.orEmpty()
        val layers = json.optJSONArray("calques") ?: JSONArray()

        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val layerText = layer.optString("texte", "")
            val placeholder = Regex("\\{([a-zA-Z0-9_]+)\\}").find(layerText)?.groupValues?.get(1)
            val token = placeholder ?: layerText.takeIf { it.isNotBlank() }?.let { "__literal_${layer.optString("id", i.toString())}" } ?: continue
            fieldBindings.putIfAbsent(token, listOf(token))
            val images = layer.optJSONArray("images") ?: JSONArray()
            require(images.length() == 2) { "Calque ${layer.optString("id")}: images doit contenir début et fin" }
            val fontAlias = layer.optString("police", "serif")
            val declaredFont = fontFile(fontAlias)
            val fontPath = "$fontRoot/$declaredFont"
            val secondaryAlias = layer.optString("police_secours", "")
            val fallbackFont = if (secondaryAlias.isNotBlank()) "$fontRoot/${fontFile(secondaryAlias)}"
                else "$fontRoot/DejaVuSans.ttf"
            val sourceMotionName = layer.optString("mouvement", "")
            val sourceMotionNames = setOf(
                "left_to_right_mask_plus_soft_rise",
                "split_reveal_with_price_settle",
                "center_bloom_and_signature",
                "sequential_identity_contact_lockup"
            )
            val animation = if (sourceMotionName in sourceMotionNames) TextEntryAnim.COUPE else when (sourceMotionName) {
                "plume" -> TextEntryAnim.PLUME
                "lever" -> TextEntryAnim.LEVER
                "vague" -> TextEntryAnim.VAGUE_REVEAL
                // Whole-block alpha fade: the declared unit is the text block,
                // never an individual letter (Restauration T5 V4 policy).
                "fondu" -> TextEntryAnim.FONDU
                else -> TextEntryAnim.COUPE
            }
            textWindows += TextWindowSpec(
                startFrame = images.getInt(0), endFrameExcluded = images.getInt(1),
                fieldKey = token, templateLines = if (placeholder == null) listOf(layerText) else null, entryAnim = animation,
                enterFrames = layer.optInt("entree", 0), exitFadeFrames = layer.optInt("sortie", 0),
                centerX = layer.optJSONArray("centre")?.optDouble(0),
                customY = layer.optJSONArray("centre")?.optDouble(1),
                maxLines = layer.optInt("lignes_max", 2), maxHeightPx = layer.optDouble("maxHeightPx", 132.0),
                fontRelativePath = fontPath,
                fallbackFontRelativePath = if (layer.optString("id") == "offre") fallbackFont else null,
                fontSha256 = fontHash(declaredFont),
                inkColorHex = layer.optString("couleur", ink), reliefColorHex = reliefHex,
                skipAutoWhatsappPrefix = layer.optBoolean("sans_prefixe_whatsapp", layer.optString("id") == "numero"),
                // Declared variable-font weight (Playfair 800/900 for T5). Absent
                // in T1–T4 layers, so their rendering is unchanged.
                declaredWeight = layer.optInt("poids", layer.optInt("graisse", 0)).takeIf { it in 100..900 } ?: 0,
                trackingPx = layer.optDouble("tracking_px", 0.0),
                minFontSizePx = layer.optDouble("taille_min_px", 0.0).takeIf { it > 0.0 },
                sourceMotionName = sourceMotionName,
                motionGroupId = layer.optString("motion_group_id", ""),
                overrideStyle = TextStyleSpec(
                    fontSizePx = layer.optDouble("taille_px", 60.0),
                    maxWidthPx = layer.optDouble("largeur_max", 606.0),
                    lineSpacingPx = layer.optDouble("taille_px", 60.0) * layer.optDouble("interligne_ratio", 1.08),
                    twoLineBaseY = layer.optJSONArray("centre")?.optDouble(1) ?: height * 0.5,
                    singleLineBaseY = layer.optJSONArray("centre")?.optDouble(1) ?: height * 0.5,
                    emRefPx = layer.optDouble("taille_px", 60.0)
                )
            )
        }
        if (textWindows.isNotEmpty()) {
            required += textWindows.map { it.entryAnim.capability }
        }
        val overlaySerif = fontFile("serif")
        val textSpec = if (textWindows.isEmpty()) null else TextOverlaySpec(
            fontFamily = "sans-serif",
            // Template-level weight = the heaviest declared layer weight. T5 declares
            // 900, which is what makes its typeface request instantiate Playfair
            // Black instead of the variable font's default Regular 400.
            fontWeight = textWindows.mapNotNull { it.declaredWeight.takeIf { w -> w in 100..900 } }.maxOrNull() ?: 400,
            fontRelativePath = "$fontRoot/$overlaySerif", fontSha256 = fontHash(overlaySerif),
            extraFontPaths = extraFontPaths,
            extraFontSha256s = extraFontHashes,
            colorHex = ink,
            defaultStyle = TextStyleSpec(68.0, 606.0, 74.0, height * 0.5, height * 0.5, 68.0),
            endSectionStyle = null, endSectionStartFrame = null,
            exportBand = BandExportSpec(0, height),
            relief = TextReliefSpec(
                shadowColorRgba = typography.optString("shadow_rgba", "rgba(45,28,21,0.72)"),
                shadowDxPx = typography.optDouble("shadow_dx_px", 2.0),
                shadowDyPx = typography.optDouble("shadow_dy_px", 4.0),
                shadowBlurPx = typography.optDouble("shadow_blur_px", 4.0),
                strokeColorRgba = typography.optString("outline_rgba", "rgba(45,28,21,1.0)"),
                strokeWidthPx = typography.optDouble("outline_width_px", 3.0)
            ),
            wave = null, fieldBindings = fieldBindings, maxLinesByField = emptyMap(), windows = textWindows,
            fadeFrames = json.optInt("fade_frames", 0),
            motionGroups = motionGroups
        )

        val audioProfile = json.optString("audio_profile", "")
        if (audioProfile.isNotBlank()) {
            required += CapabilityId.AUDIO_CAPTURES_DUCKING
            required += CapabilityId.AUDIO_CYCLIC_ROTATION
        }
        require(cursor == totalFrames) { "Durée des scènes ($cursor images) != durée totale ($totalFrames)" }
        return RenderPlan(
            templateId = json.getString("template_id"), version = json.optString("version", "1.0.0"),
            statusLabel = json.optString("etat", ""), fps = fps, width = width, height = height,
            totalFrames = totalFrames, scenes = scenes, transitions = transitions, textOverlay = textSpec,
            colorMode = "source_native", audioProfileId = audioProfile,
            requiredCapabilities = required, unknownCapabilities = emptyList()
        )
    }

    private fun rgbHex(rgb: JSONArray?, fallback: String): String {
        if (rgb == null || rgb.length() < 3) return fallback
        return "#%02X%02X%02X".format(rgb.getInt(0).coerceIn(0, 255), rgb.getInt(1).coerceIn(0, 255), rgb.getInt(2).coerceIn(0, 255))
    }
}
