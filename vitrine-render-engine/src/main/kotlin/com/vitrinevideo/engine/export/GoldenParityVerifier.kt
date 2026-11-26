package com.vitrinevideo.engine.export

import com.vitrinevideo.engine.graphics.TextFieldResolver
import com.vitrinevideo.engine.pkg.LoadedTemplatePackage
import org.json.JSONObject

data class ParityCheckSummary(
    val passed: Boolean,
    val checkedItems: List<String>,
    val mismatches: List<String>
)

/**
 * Verifies engine structural, timeline, text-binding, and control-report parity
 * against a template package's golden control artifacts (`golden/controle.json`,
 * `golden/titres.json`, `golden/preuve-champs.json`).
 */
object GoldenParityVerifier {

    fun verifyPlanAndTextBindings(
        pkg: LoadedTemplatePackage,
        responses: Map<String, String>,
        goldenControleJson: JSONObject,
        goldenTitresJson: JSONObject?,
        goldenPreuveChampsJson: JSONObject?
    ): ParityCheckSummary {
        val checked = mutableListOf<String>()
        val mismatches = mutableListOf<String>()
        val plan = pkg.renderPlan

        val expDims = goldenControleJson.optJSONArray("dimensions")
        if (expDims != null && expDims.length() == 2) {
            val expW = expDims.getInt(0)
            val expH = expDims.getInt(1)
            if (plan.width == expW && plan.height == expH) checked += "dimensions=${plan.width}x${plan.height}"
            else mismatches += "dimensions: ${plan.width}x${plan.height} != ${expW}x${expH}"
        } else {
            val expFormat = goldenControleJson.optString("format", "")
            if (expFormat.isNotEmpty()) {
                val actualFormat = "${plan.width}x${plan.height}"
                if (actualFormat == expFormat) checked += "format=$actualFormat"
                else mismatches += "format: $actualFormat != $expFormat"
            }
        }

        if (goldenControleJson.has("fps")) {
            val expFps = goldenControleJson.getInt("fps")
            if (plan.fps == expFps) checked += "fps=${plan.fps}"
            else mismatches += "fps: ${plan.fps} != $expFps"
        }

        if (goldenControleJson.has("duree_s")) {
            val expDur = goldenControleJson.getDouble("duree_s")
            if (kotlin.math.abs(plan.durationSec - expDur) < 0.01) checked += "duree_s=${plan.durationSec}"
            else mismatches += "duree_s: ${plan.durationSec} != $expDur"
        }

        val expFramesKey = if (goldenControleJson.has("frames")) "frames" else "images"
        if (goldenControleJson.has(expFramesKey)) {
            val expFrames = goldenControleJson.getInt(expFramesKey)
            if (plan.totalFrames == expFrames) checked += "frames=${plan.totalFrames}"
            else mismatches += "frames: ${plan.totalFrames} != $expFrames"
        }

        val expScenes = goldenControleJson.optJSONArray("scenes")
        if (expScenes != null) {
            if (plan.scenes.size != expScenes.length()) {
                mismatches += "scenes.size: ${plan.scenes.size} != ${expScenes.length()}"
            } else {
                for (i in 0 until expScenes.length()) {
                    val es = expScenes.getJSONObject(i)
                    val ps = plan.scenes[i]
                    val expId = es.optString("scene", "").ifBlank { es.getString("id") }
                    val expDeb = es.getInt("debut")
                    val expFin = es.getInt("fin_exclue")
                    if (ps.id == expId && ps.startFrame == expDeb && ps.endFrameExcluded == expFin) {
                        checked += "scene[$expId]=${ps.startFrame}..${ps.endFrameExcluded}"
                    } else {
                        mismatches += "scene[$i]: (${ps.id},${ps.startFrame},${ps.endFrameExcluded}) != ($expId,$expDeb,$expFin)"
                    }
                }
            }
        }

        val expTrans = goldenControleJson.optJSONArray("transitions")
        if (expTrans != null) {
            if (plan.transitions.size != expTrans.length()) {
                mismatches += "transitions.size: ${plan.transitions.size} != ${expTrans.length()}"
            } else {
                for (i in 0 until expTrans.length()) {
                    val et = expTrans.getJSONObject(i)
                    val pt = plan.transitions[i]
                    val expType = et.getString("type")
                    val expDeb = et.getInt("debut")
                    val expDuree = if (et.has("duree")) et.getInt("duree") else et.getInt("duree_images")
                    if (pt.type.wireName == expType && pt.startFrame == expDeb && pt.durationFrames == expDuree) {
                        checked += "transition[$expType]@$expDeb/$expDuree"
                    } else {
                        mismatches += "transition[$i]: (${pt.type.wireName}@${pt.startFrame}/${pt.durationFrames}) != ($expType@$expDeb/$expDuree)"
                    }
                }
            }
        }

        val overlay = plan.textOverlay
        if (overlay != null && goldenTitresJson != null) {
            for (w in overlay.windows) {
                if (goldenTitresJson.has(w.fieldKey)) {
                    val expectedText = goldenTitresJson.getString(w.fieldKey)
                    val actualText = TextFieldResolver.resolveFieldText(w, overlay, responses)
                    if (actualText == expectedText) {
                        checked += "titre[${w.fieldKey}]"
                    } else {
                        mismatches += "titre[${w.fieldKey}]: '$actualText' != '$expectedText'"
                    }
                }
            }
        }

        if (overlay != null && goldenPreuveChampsJson != null) {
            val expectedPriceVisible = goldenControleJson.optBoolean("prix_affiche", true)
            val priceWin = overlay.windows.firstOrNull { it.fieldKey == "prix" }
            if (priceWin != null) {
                val actualVisible = TextFieldResolver.layoutWindow(priceWin, overlay, responses) { s, sz -> s.length * sz * 0.5f } != null
                if (actualVisible == expectedPriceVisible) {
                    checked += "prix_affiche=$actualVisible"
                } else {
                    mismatches += "prix_affiche: $actualVisible != $expectedPriceVisible"
                }
                val noPriceResponses = responses.toMutableMap().apply { put("prix", "") }
                val noPriceVisible = TextFieldResolver.layoutWindow(priceWin, overlay, noPriceResponses) { s, sz -> s.length * sz * 0.5f } != null
                if (!noPriceVisible) {
                    checked += "sans_prix.prix_affiche=false"
                } else {
                    mismatches += "sans_prix.prix_affiche: true != false"
                }
            }
            for (w in overlay.windows) {
                val proofObj = goldenPreuveChampsJson.optJSONObject(w.fieldKey) ?: continue
                val expValeur = proofObj.optString("valeur", "")
                val actualText = TextFieldResolver.resolveFieldText(w, overlay, responses)
                val decodedFrame = proofObj.optInt("image_decodee", -1)
                if (actualText == expValeur && decodedFrame in w.startFrame until w.endFrameExcluded) {
                    checked += "preuve[${w.fieldKey}]@$decodedFrame"
                } else {
                    mismatches += "preuve[${w.fieldKey}]: text='$actualText' vs '$expValeur', frame=$decodedFrame not in ${w.startFrame}..${w.endFrameExcluded}"
                }
            }
        }

        if (goldenControleJson.has("teintes_ajoutees")) {
            val expTint = goldenControleJson.getBoolean("teintes_ajoutees")
            val actualTint = plan.colorGrading.shouldRenderAddedTint
            if (actualTint == expTint && plan.colorGrading.mode == "source_native" && !plan.colorGrading.decorativeBorder) {
                checked += "etalonnage=${plan.colorGrading.mode}(teinte=${plan.colorGrading.addedTint},bordure=${plan.colorGrading.decorativeBorder})"
            } else {
                mismatches += "etalonnage: mode=${plan.colorGrading.mode}, teinte=$actualTint (exp=$expTint), bordure=${plan.colorGrading.decorativeBorder}"
            }
        }

        return ParityCheckSummary(
            passed = mismatches.isEmpty(),
            checkedItems = checked,
            mismatches = mismatches
        )
    }
}
