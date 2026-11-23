package com.vitrinevideo.engine.interpreter

import com.vitrinevideo.engine.capabilities.CapabilityId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal parser for multi-plan V1.2 engineering specs (e.g. T1_ENGINEERING_SPEC_V1_2.json)
 * converting shot plans, movements, transitions and text_blocks into the single canonical RenderPlan IR.
 */
object EngineeringSpecParser {

    fun canParse(json: JSONObject): Boolean =
        json.has("gabarit") && json.has("plans") && json.has("units")

    fun parse(json: JSONObject): RenderPlan {
        val required = mutableSetOf(
            CapabilityId.VIDEO_SCENE_TRIM,
            CapabilityId.VIDEO_CROP_ZOOM,
            CapabilityId.COLOR_SOURCE_NATIVE,
            CapabilityId.EXPORT_MP4_H264_AAC
        )
        val unknown = mutableListOf<String>()

        val gabarit = json.getJSONObject("gabarit")
        val templateId = gabarit.getString("id")
        val version = json.optString("spec_version", "1.2")
        val units = json.getJSONObject("units")
        val fps = units.optInt("fps", 30)
        val totalFrames = units.optInt("total_frames", 900)
        val width = 720
        val height = 1280
        val scaleTo720 = width / 1080.0

        val movementMap = mutableMapOf<String, MotionSpec>()
        json.optJSONArray("movements")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.getString("id")
                val nom = m.getString("nom")
                val params = m.optJSONObject("parametres") ?: JSONObject()
                val kArr = params.optJSONArray("k_ratio")
                val panArr = params.optJSONArray("pan_px")
                val easing = params.optString("easing", "easeInOutQuad")
                movementMap[id] = when (nom) {
                    "zoom_in_lent", "push_in_rapide" -> MotionSpec(
                        type = "zoom_in",
                        startScale = kArr?.optDouble(0, 1.0) ?: 1.0,
                        endScale = kArr?.optDouble(1, 1.05) ?: 1.05,
                        easing = easing
                    )
                    "zoom_out_lent", "pull_out_rapide", "settle" -> MotionSpec(
                        type = "zoom_out",
                        startScale = kArr?.optDouble(0, 1.04) ?: 1.04,
                        endScale = kArr?.optDouble(1, 1.0) ?: 1.0,
                        easing = easing
                    )
                    "pan_h" -> MotionSpec(
                        type = "pan_h",
                        startPanX = (panArr?.optDouble(0, -14.0) ?: -14.0) * scaleTo720,
                        endPanX = (panArr?.optDouble(1, 14.0) ?: 14.0) * scaleTo720,
                        easing = easing
                    )
                    "pan_v" -> MotionSpec(
                        type = "pan_v",
                        startPanY = (panArr?.optDouble(0, 10.0) ?: 10.0) * scaleTo720,
                        endPanY = (panArr?.optDouble(1, -10.0) ?: -10.0) * scaleTo720,
                        easing = easing
                    )
                    "diagonale" -> MotionSpec(
                        type = "diagonale",
                        startScale = kArr?.optDouble(0, 1.0) ?: 1.0,
                        endScale = kArr?.optDouble(1, 1.02) ?: 1.02,
                        endPanX = (panArr?.optDouble(0, 12.0) ?: 12.0) * scaleTo720,
                        endPanY = (panArr?.optDouble(1, 8.0) ?: 8.0) * scaleTo720,
                        easing = easing
                    )
                    else -> MotionSpec("none")
                }
            }
        }

        val plansArr = json.getJSONArray("plans")
        val shotsByScene = linkedMapOf<String, MutableList<CutSegmentSpec>>()
        for (i in 0 until plansArr.length()) {
            val p = plansArr.getJSONObject(i)
            val sceneId = p.getString("scene")
            val freezeTail = p.optInt("freeze_tail_f", 0)
            if (freezeTail > 0) required += CapabilityId.VIDEO_FREEZE_TAIL
            val mvId = p.optString("movement", "")
            val motion = movementMap[mvId] ?: MotionSpec("none")
            if (motion.type != "none") required += CapabilityId.VIDEO_MOTION_CURVE

            val seg = CutSegmentSpec(
                shotId = p.getString("id"),
                sceneId = sceneId,
                sourceInFrame = p.getInt("source_in_f"),
                sourceOutFrame = p.getInt("source_out_f"),
                outStartFrame = p.getInt("out_start_f"),
                outEndFrameExcluded = p.getInt("out_end_f"),
                freezeTailFrames = freezeTail,
                viewport = ViewportSpec(0.5, 0.5, p.optDouble("k_base", 1.0)),
                motion = motion
            )
            shotsByScene.getOrPut(sceneId) { mutableListOf() }.add(seg)
        }

        val scenesArr = json.getJSONArray("scenes")
        val scenes = mutableListOf<SceneSpec>()
        var cursor = 0
        for (i in 0 until scenesArr.length()) {
            val s = scenesArr.getJSONObject(i)
            val id = s.getString("id")
            val frames = s.getInt("frames")
            scenes += SceneSpec(
                id = id,
                startFrame = cursor,
                endFrameExcluded = cursor + frames,
                role = s.optString("nom", ""),
                shots = shotsByScene[id].orEmpty()
            )
            cursor += frames
        }

        val transObj = json.optJSONObject("transitions")
        val transLib = mutableMapOf<String, Pair<String, Int>>()
        transObj?.optJSONArray("library")?.let { lib ->
            for (i in 0 until lib.length()) {
                val item = lib.getJSONObject(i)
                transLib[item.getString("id")] = item.getString("nom") to item.optInt("dur_f", 0)
            }
        }
        val transitions = mutableListOf<TransitionSpec>()
        transObj?.optJSONArray("assignments")?.let { assigns ->
            for (i in 0 until assigns.length()) {
                val a = assigns.getJSONObject(i)
                val trId = a.getString("transition")
                val (nom, dur) = transLib[trId] ?: ("unknown_$trId" to 0)
                val mapped = when (nom) {
                    "cut_sec", "match_cut" -> TransitionType.CUT
                    "punch_cut", "speed_ramp_entrant" -> TransitionType.PUNCH
                    "wipe_geometrique", "wipe_directionnel" -> TransitionType.WIPE_DIRECTIONNEL
                    "fondu_court_crossfade" -> TransitionType.CROSSFADE
                    else -> {
                        unknown += "transition.$nom"
                        null
                    }
                }
                if (mapped != null) {
                    required += mapped.capability
                    if (dur > 0 || mapped == TransitionType.CUT) {
                        transitions += TransitionSpec(a.getInt("at_f"), dur, mapped)
                    }
                }
            }
        }

        val textOverlay = parseTextBlocks(json, width, height, scaleTo720, required)

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
            colorMode = "source_native",
            audioProfileId = "",
            requiredCapabilities = required,
            unknownCapabilities = unknown
        )
    }

    private fun parseTextBlocks(
        json: JSONObject,
        width: Int,
        height: Int,
        scaleTo720: Double,
        required: MutableSet<CapabilityId>
    ): TextOverlaySpec? {
        val textBlocks = json.optJSONArray("text_blocks") ?: return null
        val graphicBlocks = mutableMapOf<String, JSONObject>()
        json.optJSONArray("graphic_blocks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val gb = arr.getJSONObject(i)
                graphicBlocks[gb.getString("id")] = gb
            }
        }
        required += CapabilityId.TEXT_STYLED_WINDOWS
        required += CapabilityId.TEXT_ANIM_SLIDE_POP

        val bindings = mutableMapOf<String, List<String>>()
        val windows = mutableListOf<TextWindowSpec>()
        for (i in 0 until textBlocks.length()) {
            val tb = textBlocks.getJSONObject(i)
            val champ = tb.getString("champ")
            val gbId = tb.optString("bloc", "")
            val gb = graphicBlocks[gbId]
            val nominal = gb?.optJSONObject("nominal")
            val yCenter = nominal?.let { (it.getDouble("y_min") + it.getDouble("y_max")) * 0.5 * scaleTo720 }
            val px720 = gb?.optDouble("text_px_720", 48.0) ?: 48.0
            bindings[champ] = listOf(champ)
            windows += TextWindowSpec(
                startFrame = tb.getInt("start_f"),
                endFrameExcluded = tb.getInt("end_f"),
                fieldKey = champ,
                entryAnim = TextEntryAnim.SLIDE_POP,
                customY = yCenter,
                overrideStyle = TextStyleSpec(
                    fontSizePx = px720,
                    maxWidthPx = width * 0.84,
                    lineSpacingPx = px720 * 1.15,
                    twoLineBaseY = yCenter ?: height * 0.5,
                    singleLineBaseY = yCenter ?: height * 0.5,
                    emRefPx = px720
                )
            )
        }
        return TextOverlaySpec(
            fontFamily = "Montserrat",
            fontWeight = 800,
            fontRelativePath = "docs/assets/Montserrat.ttf",
            colorHex = "#FFFFFF",
            defaultStyle = TextStyleSpec(48.0, width * 0.84, 55.0, height * 0.45, height * 0.5, 48.0),
            endSectionStyle = null,
            endSectionStartFrame = null,
            exportBand = BandExportSpec(0, height),
            relief = TextReliefSpec("rgba(0,0,0,0.45)", 0.0, 3.0, 10.0, "rgba(0,0,0,0.6)", 2.0),
            wave = null,
            fieldBindings = bindings,
            maxLinesByField = emptyMap(),
            windows = windows,
            fadeFrames = 8
        )
    }
}
