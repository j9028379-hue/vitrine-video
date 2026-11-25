package com.vitrinevideo.engine.interpreter

import com.vitrinevideo.engine.capabilities.CapabilityId
import com.vitrinevideo.engine.graphics.VitrineBitmapTextOverlay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal adapter for an evidence-driven montage contract: the package publishes
 * its source-frame windows, output windows, declared effect operations and framing
 * trajectories as JSON. Nothing here invents a creative value; unsupported declared
 * operations are reported through [RenderPlan.unknownCapabilities] and fail fast.
 */
object EvidenceMontageContractParser {

    private val zoomOperations = setOf(
        "revelation_par_geste", "coupe_recadrage", "retour_plan_entier",
        "coupe_detail", "tenue_finale"
    )
    private val veilOperations = setOf("voile_chaud", "passage_assombri")
    private val glowOperations = setOf("reflet_local_mobile")
    private val freezeOperations = setOf("fondu_aligne_deux_echelles")
    private val blurOperations = setOf("surimpression_floue")
    private val finalOperations = setOf("fondu_noir")

    fun canParse(json: JSONObject): Boolean =
        json.has("format_propose") && json.has("scenes") && json.has("effets") && json.has("cadrage")

    fun parse(
        json: JSONObject,
        renderScript: String? = null
    ): RenderPlan {
        val required = mutableSetOf(
            CapabilityId.VIDEO_SCENE_TRIM,
            CapabilityId.VIDEO_CROP_ZOOM,
            CapabilityId.VIDEO_MOTION_CURVE,
            CapabilityId.VIDEO_VIEWPORT_TRAJECTORY,
            CapabilityId.COLOR_SOURCE_NATIVE,
            CapabilityId.TEXT_STYLED_WINDOWS,
            CapabilityId.EXPORT_MP4_H264_AAC
        )
        val unknown = mutableListOf<String>()
        val fmt = json.getJSONObject("format_propose")
        val fps = fmt.getInt("fps")
        val totalFrames = fmt.getInt("frames")
        val width = fmt.getInt("width")
        val height = fmt.getInt("height")
        val cadrage = json.getJSONObject("cadrage")
        val zoomMax = cadrage.optDouble("zoom_max", 1.0)
        val trajectories = cadrage.getJSONArray("trajectoires")

        val scenesJson = json.getJSONArray("scenes")
        val scenes = mutableListOf<SceneSpec>()
        for (i in 0 until scenesJson.length()) {
            val s = scenesJson.getJSONObject(i)
            val out = s.getJSONArray("images_sortie")
            val src = s.getJSONArray("images_source")
            val start = out.getInt(0)
            val end = out.getInt(1)
            val frameKeys = trajectories.getJSONArray(i)
            val keys = (0 until frameKeys.length()).map { k ->
                val pair = frameKeys.getJSONArray(k)
                ViewportKeyframe(pair.getDouble(0), pair.getDouble(1))
            }
            scenes += SceneSpec(
                id = s.getString("id"),
                startFrame = start,
                endFrameExcluded = end,
                role = s.optString("fonction", ""),
                shots = listOf(
                    CutSegmentSpec(
                        shotId = "${s.getString("id")}_P1",
                        sceneId = s.getString("id"),
                        sourceInFrame = src.getInt(0),
                        sourceOutFrame = src.getInt(1),
                        outStartFrame = start,
                        outEndFrameExcluded = end,
                        viewport = ViewportSpec(keys.firstOrNull()?.cx ?: 0.5, 0.5, 1.0),
                        viewportKeyframes = keys,
                        maxZoom = zoomMax
                    )
                )
            )
        }

        val effects = mutableListOf<VisualEffectSpec>()
        val transitions = mutableListOf<TransitionSpec>()
        val effectsJson = json.getJSONArray("effets")
        for (i in 0 until effectsJson.length()) {
            val e = effectsJson.getJSONObject(i)
            val id = e.getString("id")
            val operation = e.getString("operation")
            val window = e.getJSONArray("images_t2")
            val start = window.getInt(0)
            val end = window.getInt(1)
            val zoom = if (e.has("zoom")) {
                val a = e.getJSONArray("zoom")
                a.getDouble(0) to a.getDouble(1)
            } else null
            val opacity = if (e.has("opacite")) absoluteKeyframes(e.getJSONArray("opacite"), start) else emptyList()
            val alpha = if (e.has("alpha_entrant")) absoluteKeyframes(e.getJSONArray("alpha_entrant"), start) else emptyList()
            val centers = if (e.has("centre_xy")) {
                val c = e.getJSONArray("centre_xy")
                pairFrom(c.getJSONArray(0)) to pairFrom(c.getJSONArray(1))
            } else null
            val radius = if (e.has("rayon_xy")) {
                val r = e.getJSONArray("rayon_xy")
                r.getDouble(0) to r.getDouble(1)
            } else null
            val zoomCopy = if (e.has("zoom_sortant")) {
                val a = e.getJSONArray("zoom_sortant"); a.getDouble(0) to a.getDouble(1)
            } else if (e.has("zoom_copie")) {
                val a = e.getJSONArray("zoom_copie"); a.getDouble(0) to a.getDouble(1)
            } else null

            val kind = when (operation) {
                in zoomOperations -> EffectKind.ZOOM
                in veilOperations -> EffectKind.VEIL
                in glowOperations -> EffectKind.GLOW
                in freezeOperations -> EffectKind.FREEZE_CROSSFADE
                in blurOperations -> EffectKind.BLURRED_OVERLAY
                in finalOperations -> EffectKind.FINAL_FADE
                else -> {
                    unknown += "effect.$operation"
                    continue
                }
            }
            required += kind.capability
            if (kind == EffectKind.FREEZE_CROSSFADE) {
                required += CapabilityId.TRANSITION_CROSSFADE
                transitions += TransitionSpec(start, end - start, TransitionType.CROSSFADE, id)
            }
            effects += VisualEffectSpec(
                id = id,
                operation = operation,
                kind = kind,
                startFrame = start,
                endFrameExcluded = end,
                zoom = zoom,
                colorHex = e.optString("couleur_sRGB").takeIf { it.isNotBlank() },
                opacityKeyframes = opacity,
                alphaKeyframes = alpha,
                centerStart = centers?.first,
                centerEnd = centers?.second,
                radiusXY = radius,
                zoomCopy = zoomCopy,
                sigmaMaxPx = e.optDouble("sigma_max_px", 0.0),
                stage = if (kind == EffectKind.FINAL_FADE) EffectStage.FINAL else EffectStage.COMPOSITE
            )
        }

        val scriptEffects = if (renderScript.isNullOrBlank()) emptyList() else
            RenderScriptTextLayoutParser.parseEffects(renderScript, scenes)
        effects += scriptEffects.filter { it.kind == EffectKind.SCRIM }
        val textOverlay = if (renderScript.isNullOrBlank()) null else
            RenderScriptTextLayoutParser.parseTextOverlay(renderScript, scenes, width, height)

        return RenderPlan(
            templateId = json.getString("template_id"),
            version = json.optString("version", "1.0.0"),
            statusLabel = json.optString("statut", ""),
            fps = fps,
            width = width,
            height = height,
            totalFrames = totalFrames,
            scenes = scenes,
            transitions = transitions,
            textOverlay = textOverlay,
            colorMode = "source_native",
            audioProfileId = json.optString("audio_profile", ""),
            requiredCapabilities = required,
            unknownCapabilities = unknown,
            visualEffects = effects
        )
    }

    private fun absoluteKeyframes(arr: JSONArray, startFrame: Int): List<EffectKeyframe> =
        (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i)
            EffectKeyframe(startFrame + p.getInt(0), p.getDouble(1))
        }

    private fun pairFrom(arr: JSONArray): Pair<Double, Double> = arr.getDouble(0) to arr.getDouble(1)
}
