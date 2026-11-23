package com.vitrinevideo.engine.interpreter

import com.vitrinevideo.engine.capabilities.CapabilityId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal parser for `montage/partition.json` (T3, T4, T5 and compatible 1.x partitions).
 * Never hardcodes template-specific rules: all behavior comes from the JSON.
 */
object PartitionV1Parser {

    fun canParse(json: JSONObject): Boolean =
        json.has("template_id") && json.has("scenes") && json.has("dimensions") && json.has("fps")

    fun parse(json: JSONObject): RenderPlan {
        val required = mutableSetOf(
            CapabilityId.VIDEO_SCENE_TRIM,
            CapabilityId.VIDEO_CROP_ZOOM,
            CapabilityId.EXPORT_MP4_H264_AAC
        )
        val unknown = mutableListOf<String>()

        val templateId = json.getString("template_id")
        val version = json.optString("version", "1.0.0")
        val fps = json.getInt("fps")
        val dims = json.getJSONArray("dimensions")
        require(dims.length() == 2) { "dimensions must contain [width, height]" }
        val width = dims.getInt(0)
        val height = dims.getInt(1)
        val totalFrames = json.getInt("images")

        // Color grading check
        val colorMode = json.optJSONObject("etalonnage")?.optString("mode", "source_native") ?: "source_native"
        if (colorMode == "source_native") {
            required += CapabilityId.COLOR_SOURCE_NATIVE
        } else {
            unknown += "color.$colorMode"
        }

        // Parse grid/raccords & optional per-frame cuts (e.g. T5 effets.coupes)
        val raccordsArr = json.optJSONArray("raccords") ?: JSONArray()
        val gridByStartFrame = mutableMapOf<Int, GridLayoutSpec>()
        for (i in 0 until raccordsArr.length()) {
            val r = raccordsArr.getJSONObject(i)
            if (r.has("cadrages") && r.has("coupes_cases")) {
                required += CapabilityId.VIDEO_GRID_2X2_SINGLE_SOURCE
                val imgs = r.getJSONArray("images")
                val startF = imgs.getInt(0)
                val dividerPx = r.optInt("trait_px", 6)
                val cadragesJson = r.getJSONArray("cadrages")
                val viewports = (0 until cadragesJson.length()).map { idx ->
                    val arr = cadragesJson.getJSONArray(idx)
                    ViewportSpec(arr.getDouble(0), arr.getDouble(1), arr.getDouble(2))
                }
                val cutsJson = r.getJSONArray("coupes_cases")
                val cellCuts = (0 until cutsJson.length()).map { idx ->
                    val obj = cutsJson.getJSONObject(idx)
                    val ordArr = obj.getJSONArray("ordre")
                    GridCellCut(
                        atFrame = obj.getInt("image"),
                        order = (0 until ordArr.length()).map { j -> ordArr.getInt(j) }
                    )
                }
                gridByStartFrame[startF] = GridLayoutSpec(
                    dividerPx = dividerPx,
                    viewports = viewports,
                    cellCuts = cellCuts
                )
            }
        }

        val effetCoupes = json.optJSONObject("effets")?.optJSONArray("coupes")
        val shotZoomEvents = mutableListOf<Pair<Int, Double>>()
        if (effetCoupes != null) {
            for (i in 0 until effetCoupes.length()) {
                val c = effetCoupes.getJSONObject(i)
                shotZoomEvents += c.getInt("image") to c.optDouble("zoom", 1.0)
            }
        }

        // Parse scenes
        val scenesArr = json.getJSONArray("scenes")
        val scenes = mutableListOf<SceneSpec>()
        for (i in 0 until scenesArr.length()) {
            val s = scenesArr.getJSONObject(i)
            val id = s.getString("id")
            val debut = s.getInt("debut")
            val finExclue = when {
                s.has("fin_exclue") -> s.getInt("fin_exclue")
                s.has("fin") -> s.getInt("fin")
                else -> throw IllegalArgumentException("Scene $id missing fin_exclue/fin")
            }
            val role = s.optString("role", s.optString("titre", ""))
            val grid = gridByStartFrame[debut]
            val sceneCuts = buildSceneShots(id, debut, finExclue, grid, shotZoomEvents)
            scenes += SceneSpec(
                id = id,
                startFrame = debut,
                endFrameExcluded = finExclue,
                role = role,
                shots = sceneCuts
            )
        }

        // Parse transitions (liaisons)
        val liaisonsArr = json.optJSONArray("liaisons") ?: JSONArray()
        val transitions = mutableListOf<TransitionSpec>()
        for (i in 0 until liaisonsArr.length()) {
            val l = liaisonsArr.getJSONObject(i)
            val typeStr = l.getString("type")
            val tType = mapTransitionType(typeStr)
            if (tType == null) {
                unknown += "transition.$typeStr"
            } else {
                required += tType.capability
                transitions += TransitionSpec(
                    startFrame = l.getInt("debut"),
                    durationFrames = l.getInt("duree"),
                    type = tType
                )
            }
        }

        // Parse text specification
        val textOverlay = json.optJSONObject("texte")?.let { tJson ->
            required += CapabilityId.TEXT_STYLED_WINDOWS
            parseTextSpec(tJson, scenes, width, height, required, unknown)
        }

        val audioProfileId = json.optString("audio_profile", "")
        if (audioProfileId.isNotEmpty()) {
            required += CapabilityId.AUDIO_CAPTURES_DUCKING
            required += CapabilityId.AUDIO_CYCLIC_ROTATION
        }

        return RenderPlan(
            templateId = templateId,
            version = version,
            fps = fps,
            width = width,
            height = height,
            totalFrames = totalFrames,
            scenes = scenes,
            transitions = transitions,
            textOverlay = textOverlay,
            colorMode = colorMode,
            audioProfileId = audioProfileId,
            requiredCapabilities = required,
            unknownCapabilities = unknown
        )
    }

    private fun buildSceneShots(
        sceneId: String,
        debut: Int,
        finExclue: Int,
        grid: GridLayoutSpec?,
        zoomEvents: List<Pair<Int, Double>>
    ): List<CutSegmentSpec> {
        val inSceneZooms = zoomEvents.filter { (img, _) -> img in debut until finExclue }.sortedBy { it.first }
        if (grid != null || inSceneZooms.isEmpty()) {
            return listOf(
                CutSegmentSpec(
                    shotId = "${sceneId}_P1",
                    sceneId = sceneId,
                    sourceInFrame = 0,
                    sourceOutFrame = finExclue - debut,
                    outStartFrame = debut,
                    outEndFrameExcluded = finExclue,
                    gridLayout = grid
                )
            )
        }
        val boundaries = (listOf(debut) + inSceneZooms.map { it.first } + listOf(finExclue)).distinct().sorted()
        return boundaries.zipWithNext().mapIndexed { idx, (a, b) ->
            val zoom = inSceneZooms.lastOrNull { it.first <= a }?.second ?: 1.0
            CutSegmentSpec(
                shotId = "${sceneId}_P${idx + 1}",
                sceneId = sceneId,
                sourceInFrame = a - debut,
                sourceOutFrame = b - debut,
                outStartFrame = a,
                outEndFrameExcluded = b,
                viewport = ViewportSpec(0.5, 0.5, zoom)
            )
        }
    }

    private fun mapTransitionType(wire: String): TransitionType? = when (wire) {
        "cut_sec", "coupe", "cut" -> TransitionType.CUT
        "quatre_volets" -> TransitionType.QUATRE_VOLETS
        "case_vers_plein_cadre" -> TransitionType.CASE_VERS_PLEIN_CADRE
        "balayage_diagonal" -> TransitionType.BALAYAGE_DIAGONAL
        "wipe_directionnel", "wipe_geometrique" -> TransitionType.WIPE_DIRECTIONNEL
        "fondu_court_crossfade", "crossfade", "fondu" -> TransitionType.CROSSFADE
        "punch_cut", "zoom_punch" -> TransitionType.PUNCH
        else -> null
    }

    private fun parseTextSpec(
        t: JSONObject,
        scenes: List<SceneSpec>,
        width: Int,
        height: Int,
        required: MutableSet<CapabilityId>,
        unknown: MutableList<String>
    ): TextOverlaySpec {
        val family = t.optString("famille_substitut", t.optString("famille", "sans-serif"))
        val weight = t.optInt("graisse", 400)
        val rawFontPath = t.optString("fonte_fichier", t.optString("fonte", ""))
        val fontRelPath = if (rawFontPath.startsWith("assets/")) "docs/$rawFontPath" else rawFontPath
        val colorHex = t.optString("couleur", "#FFFFFF")

        val styleObj = t.optJSONObject("style")
        val defaultStyle = if (styleObj != null) {
            TextStyleSpec(
                fontSizePx = styleObj.optDouble("taille_px", 60.0),
                maxWidthPx = styleObj.optDouble("largeur_max_px", width * 0.9),
                lineSpacingPx = styleObj.optDouble("interligne_px", 72.0),
                twoLineBaseY = styleObj.optDouble("base_ligne_un_sur_deux_y", height * 0.48),
                singleLineBaseY = styleObj.optDouble("base_ligne_unique_y", height * 0.52),
                emRefPx = styleObj.optDouble("em_ref_px", 66.0)
            )
        } else {
            val sz = t.optDouble("taille_px", 43.0)
            TextStyleSpec(
                fontSizePx = sz,
                maxWidthPx = t.optDouble("largeur", width * 0.88),
                lineSpacingPx = sz * 1.22,
                twoLineBaseY = height * 0.48,
                singleLineBaseY = height * 0.52,
                emRefPx = sz
            )
        }

        val finObj = t.optJSONObject("fin_style")
        val endStyle = finObj?.let {
            TextStyleSpec(
                fontSizePx = it.optDouble("taille_px", defaultStyle.fontSizePx),
                maxWidthPx = it.optDouble("largeur_max_px", defaultStyle.maxWidthPx),
                lineSpacingPx = it.optDouble("interligne_px", defaultStyle.lineSpacingPx),
                twoLineBaseY = it.optDouble("base_ligne_un_sur_deux_y", 210.0),
                singleLineBaseY = it.optDouble("base_ligne_unique_y", 246.0),
                emRefPx = defaultStyle.emRefPx,
                alignTopBand = true
            )
        }
        val endSectionStart = if (endStyle != null && scenes.isNotEmpty()) scenes.last().startFrame else null

        val bandObj = t.optJSONObject("bande_export")
        val exportBand = if (bandObj != null) {
            BandExportSpec(bandObj.getInt("haut"), bandObj.getInt("hauteur"))
        } else {
            BandExportSpec(0, height)
        }

        val reliefObj = t.optJSONObject("relief")
        val relief = if (reliefObj != null) {
            TextReliefSpec(
                shadowColorRgba = reliefObj.optString("ombre", "rgba(0,0,0,0.6)"),
                shadowDxPx = reliefObj.optDouble("decalage_x_px", 0.0),
                shadowDyPx = reliefObj.optDouble("decalage_y_px", 3.0),
                shadowBlurPx = reliefObj.optDouble("flou_px", 6.0),
                strokeColorRgba = reliefObj.optString("liseré", reliefObj.optString("lisere", "rgba(0,0,0,0.8)")),
                strokeWidthPx = reliefObj.optDouble("largeur_trait_px", 2.0)
            )
        } else {
            TextReliefSpec("rgba(0,0,0,0.45)", 0.0, 3.0, 8.0, "rgba(0,0,0,0)", 0.0)
        }

        val waveObj = t.optJSONObject("vague")
        val wave = waveObj?.let { w ->
            val samples = w.getJSONArray("echantillons")
            val kfs = (0 until samples.length()).map { idx ->
                val row = samples.getJSONArray(idx)
                WaveKeyframe(
                    ageRefFrames = row.getDouble(0),
                    dxPx = row.getDouble(1),
                    dyPx = row.getDouble(2),
                    rotationDeg = row.getDouble(3),
                    scale = row.getDouble(4)
                )
            }
            WaveSpec(
                keyframes = kfs,
                charIntervalRefFrames = w.optDouble("intervalle_lettres_images_ref", 0.77),
                linePauseRefFrames = w.optDouble("pause_interligne_images_ref", 1.0),
                timeAdaptation = w.optDouble("adaptation_temps", 1.35)
            )
        }

        val bindings = mutableMapOf<String, List<String>>()
        t.optJSONObject("liaison_champs")?.let { lb ->
            val keys = lb.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = lb.optJSONArray(k)
                if (arr != null) {
                    bindings[k] = (0 until arr.length()).map { arr.getString(it) }
                }
            }
        }

        val maxLinesMap = mutableMapOf<String, Int>()
        t.optJSONObject("lignes_par_champ")?.let { lp ->
            val keys = lp.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                maxLinesMap[k] = lp.getInt(k)
            }
        }

        val windowsArr = t.getJSONArray("fenetres")
        val windows = mutableListOf<TextWindowSpec>()
        for (i in 0 until windowsArr.length()) {
            val w = windowsArr.getJSONObject(i)
            val debut = w.getInt("debut")
            val finExclue = when {
                w.has("fin_exclue") -> w.getInt("fin_exclue")
                w.has("fin") -> w.getInt("fin")
                else -> throw IllegalArgumentException("Text window missing fin_exclue/fin")
            }
            val entreeStr = w.optString("entree", if (t.has("entree_images")) "fondu" else "coupe")
            val entryAnim = when (entreeStr) {
                "coupe" -> TextEntryAnim.COUPE
                "fondu" -> TextEntryAnim.FONDU
                "vague" -> TextEntryAnim.VAGUE
                "slide_pop", "pop" -> TextEntryAnim.SLIDE_POP
                else -> {
                    unknown += "text.anim.$entreeStr"
                    TextEntryAnim.COUPE
                }
            }
            required += entryAnim.capability
            val strikethrough = w.optBoolean("barre", false)
            if (strikethrough) required += CapabilityId.TEXT_STRIKETHROUGH

            val templateLines = w.optJSONArray("lignes")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            val fieldKey = w.optString("champ", "inline_$i")
            windows += TextWindowSpec(
                startFrame = debut,
                endFrameExcluded = finExclue,
                fieldKey = fieldKey,
                templateLines = templateLines,
                entryAnim = entryAnim,
                exitFadeFrames = t.optInt("sortie_images", 0),
                strikethrough = strikethrough,
                customY = if (w.has("y")) w.getDouble("y") else null,
                maxLines = maxLinesMap[fieldKey]
            )
        }

        return TextOverlaySpec(
            fontFamily = family,
            fontWeight = weight,
            fontRelativePath = fontRelPath,
            colorHex = colorHex,
            defaultStyle = defaultStyle,
            endSectionStyle = endStyle,
            endSectionStartFrame = endSectionStart,
            exportBand = exportBand,
            relief = relief,
            wave = wave,
            fieldBindings = bindings,
            maxLinesByField = maxLinesMap,
            windows = windows,
            fadeFrames = t.optInt("entree_images", 16)
        )
    }
}
