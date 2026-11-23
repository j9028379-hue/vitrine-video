package com.vitrinevideo.engine

import com.vitrinevideo.engine.api.RenderError
import com.vitrinevideo.engine.api.RenderResult
import com.vitrinevideo.engine.audio.AudioDuckingEngine
import com.vitrinevideo.engine.capabilities.UnsupportedCapabilityException
import com.vitrinevideo.engine.export.GoldenParityVerifier
import com.vitrinevideo.engine.graphics.TextFieldResolver
import com.vitrinevideo.engine.graphics.WaveCurveEvaluator
import com.vitrinevideo.engine.interpreter.TransitionType
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.video.TransitionGeometry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommerceT3GoldenParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun matchesAllCommerceT3GoldenControlsTitresAndPreuveChamps() {
        val realZip = File("/home/user/google_drive/COMMERCE-T3-1.0.0.zip")
        assertTrue(realZip.exists())
        val pkg = TemplatePackageLoader.importZip(realZip, tmp.newFolder("t3_golden_reg"))

        val goldenJob = JSONObject(pkg.resolvePackageRelative("golden/job.json").readText())
        val goldenControle = JSONObject(pkg.resolvePackageRelative("golden/controle.json").readText())
        val goldenTitres = JSONObject(pkg.resolvePackageRelative("golden/titres.json").readText())
        val goldenPreuve = JSONObject(pkg.resolvePackageRelative("golden/preuve-champs.json").readText())

        val responses = mutableMapOf<String, String>()
        val repObj = goldenJob.getJSONObject("reponses")
        val keys = repObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            responses[k] = repObj.getString(k)
        }

        val parity = GoldenParityVerifier.verifyPlanAndTextBindings(
            pkg = pkg,
            responses = responses,
            goldenControleJson = goldenControle,
            goldenTitresJson = goldenTitres,
            goldenPreuveChampsJson = goldenPreuve
        )
        assertTrue("Écarts parité T3: ${parity.mismatches}", parity.passed)
        assertTrue(parity.checkedItems.size >= 14)

        // Verify balanced 3-line demonstration wrapping matches motion.js
        val overlay = pkg.renderPlan.textOverlay!!
        val demoWin = overlay.windows.first { it.fieldKey == "demonstration" }
        val demoLayout = TextFieldResolver.layoutWindow(demoWin, overlay, responses) { s, sz -> s.length * sz * 0.55f }
        assertNotNull(demoLayout)
        assertEquals(3, demoLayout!!.lines.size)

        // Verify transitions match T3 specs
        val tr1 = TransitionGeometry.evaluate(pkg.renderPlan.transitions[0], 189)
        assertEquals(TransitionType.QUATRE_VOLETS, tr1!!.type)
        assertEquals(4, tr1.rects.size)

        val tr2 = TransitionGeometry.evaluate(pkg.renderPlan.transitions[1], 428)
        assertEquals(TransitionType.CASE_VERS_PLEIN_CADRE, tr2!!.type)
        assertEquals(1, tr2.rects.size)

        val tr3 = TransitionGeometry.evaluate(pkg.renderPlan.transitions[2], 637)
        assertEquals(TransitionType.BALAYAGE_DIAGONAL, tr3!!.type)

        // Verify wave animation curves match T3 keyframes
        val wave = overlay.wave!!
        assertEquals(0f, WaveCurveEvaluator.interpolateWave(wave.keyframes, -0.5).scale, 0.001f)
        assertEquals(1f, WaveCurveEvaluator.interpolateWave(wave.keyframes, 30.0).scale, 0.001f)

        // Verify music-only fallback (silent capture -> zero ducking, music intact)
        val audioEngine = AudioDuckingEngine(48_000, 2, pkg.audioProfile!!.mix)
        val silentCap = ShortArray(48_000 * 2)
        val music = ShortArray(48_000 * 2) { 4000 }
        val out = ShortArray(48_000 * 2)
        audioEngine.processInterleavedPcm16(silentCap, music, 48_000, out)
        assertEquals(0L, audioEngine.activeDuckingHops)
        assertTrue(out.maxOf { kotlin.math.abs(it.toInt()) } > 1500)
    }

    @Test
    fun enforcesRenderResultAndErrorSchemasAndBlocksUnsupportedGradingFlags() {
        val realZip = File("/home/user/google_drive/COMMERCE-T3-1.0.0.zip")
        val pkg = TemplatePackageLoader.importZip(realZip, tmp.newFolder("t3_schema_reg"))

        // 1. Verify RenderResult.toSchemaResultJson() satisfies schemas/render-result.json exactly
        val resSchema = JSONObject(pkg.resolvePackageRelative("schemas/render-result.json").readText())
        val reqRes = resSchema.getJSONArray("required")
        val allowedRes = resSchema.getJSONObject("properties").keys().asSequence().toSet()

        val result = RenderResult(
            templateId = "COMMERCE-T3",
            templateVersion = "1.0.0",
            runId = "run_test_01",
            outputFileName = "Film.mp4",
            outputPath = "/tmp/Film.mp4",
            sha256 = "a".repeat(64),
            musicId = 1,
            musicCredit = "Credit"
        ).toSchemaResultJson()

        for (i in 0 until reqRes.length()) {
            assertTrue("Clé requise absente: ${reqRes.getString(i)}", result.has(reqRes.getString(i)))
        }
        assertTrue("Clés supplémentaires interdites", allowedRes.containsAll(result.keys().asSequence().toSet()))

        // 2. Verify RenderError.toSchemaErrorJson() satisfies schemas/render-error.json exactly
        val errSchema = JSONObject(pkg.resolvePackageRelative("schemas/render-error.json").readText())
        val allowedErr = errSchema.getJSONObject("properties").keys().asSequence().toSet()
        val errJson = RenderError(stage = "PREFLIGHT", code = "TEST", message = "Erreur test").toSchemaErrorJson()
        assertEquals("FAILED", errJson.getString("status"))
        assertTrue(allowedErr.containsAll(errJson.keys().asSequence().toSet()))

        // 3. Verify explicit D44.1 `etalonnage` typed preservation & native behavior on T3
        val grading = pkg.renderPlan.colorGrading
        assertEquals("source_native", grading.mode)
        assertFalse(grading.addedTint)
        assertFalse(grading.decorativeBorder)
        assertTrue(grading.isNativeColorPreserved)
        assertFalse(grading.shouldRenderAddedTint)
        assertFalse(grading.shouldRenderDecorativeBorder)

        // Verify other partition properties (`statut`, `raccords[].mode`, `texte.alignement`, `texte.prix_absent`) are preserved
        assertTrue(pkg.renderPlan.statusLabel.isNotBlank())
        assertTrue(pkg.renderPlan.scenes.all { it.raccordMode.isNotBlank() })
        assertEquals("centre", pkg.renderPlan.textOverlay!!.alignment)
        assertEquals("aucun texte ni montant de remplacement", pkg.renderPlan.textOverlay!!.absentPriceRule)

        // 4. Verify future activated/unsupported variants raise explicit diagnostic errors (never silent ignore)
        val tintJson = JSONObject(pkg.partitionFile.readText())
        tintJson.getJSONObject("etalonnage").put("teinte_ajoutee", true)
        val tintEx = assertThrows(UnsupportedCapabilityException::class.java) {
            UniversalJsonInterpreter.parsePartitionJson(tintJson.toString())
        }
        assertTrue(tintEx.message!!.contains("color.teinte_ajoutee=true"))

        val borderJson = JSONObject(pkg.partitionFile.readText())
        borderJson.getJSONObject("etalonnage").put("bordure_decorative", true)
        val borderEx = assertThrows(UnsupportedCapabilityException::class.java) {
            UniversalJsonInterpreter.parsePartitionJson(borderJson.toString())
        }
        assertTrue(borderEx.message!!.contains("color.bordure_decorative=true"))

        val modeJson = JSONObject(pkg.partitionFile.readText())
        modeJson.getJSONObject("etalonnage").put("mode", "lut_custom")
        val modeEx = assertThrows(UnsupportedCapabilityException::class.java) {
            UniversalJsonInterpreter.parsePartitionJson(modeJson.toString())
        }
        assertTrue(modeEx.message!!.contains("color.mode:lut_custom"))
    }
}
