package com.vitrinevideo.app

import com.vitrinevideo.app.flow.CaptureProgressionStateMachine
import com.vitrinevideo.app.flow.CaptureSource
import com.vitrinevideo.app.flow.CapturedSceneTake
import com.vitrinevideo.app.flow.MockupResponseMapper
import com.vitrinevideo.app.flow.RealSessionViolationException
import com.vitrinevideo.app.flow.TemplateCatalogue
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Restauration T5 in the existing app: exact six answers, 12 screens and shared readiness. */
class RestaurationT5AppFlowTest {

    private fun assetsRoot(): File {
        val candidates = listOf(File("src/main/assets"), File("vitrine-video-app/src/main/assets"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("assets root not found from ${File(".").absolutePath}")
    }

    private fun load() = TemplatePackageLoader.importZip(
        File(assetsRoot(), "packages/RESTAURATION-T5-1.0.0.zip"),
        kotlin.io.path.createTempDirectory("t5_app_pkg").toFile(),
        File(assetsRoot(), "music")
    )

    @Test
    fun catalogueUsesExactDisplayNameAndNoRestaurationT4() {
        assertEquals("RESTAURATION-T5", TemplateCatalogue.RESTAURATION_T5)
        assertTrue(TemplateCatalogue.isSupported(TemplateCatalogue.RESTAURATION_T5))
        assertEquals("Formule, combo et offre", TemplateCatalogue.displayName(TemplateCatalogue.RESTAURATION_T5, true))
        assertEquals("Formule, combo et offre", TemplateCatalogue.displayName(TemplateCatalogue.RESTAURATION_T5, false))
        assertEquals(
            setOf("COMMERCE-T1", "COMMERCE-T2", "COMMERCE-T3", "COMMERCE-T4", "RESTAURATION-T2", "RESTAURATION-T5"),
            TemplateCatalogue.SUPPORTED_IDS
        )
        assertFalse(TemplateCatalogue.SUPPORTED_IDS.contains("RESTAURATION-T4"))
    }

    @Test
    fun bridgeEnablesR4AndCollectsExactlySixOfficialKeys() {
        val html = File(assetsRoot(), "mockup/vitrine-video-maquette.html").readText()
        val bridge = File(assetsRoot(), "mockup/android-bridge.js").readText()
        assertTrue(html.contains("data-format=\"r4\""))
        assertTrue(html.contains("data-i18n=\"formats.r4.title\">Formule, combo et offre"))
        assertTrue(bridge.contains("\"r2\", \"r4\"].includes(fmt)"))
        assertTrue(bridge.contains("fmt === \"r4\" ? \"RESTAURATION-T5\""))
        assertTrue(bridge.contains("if (activeTemplateFormat === \"r4\") return {"))
        assertTrue(bridge.contains("offre: val(\"q-produit\")"))
        assertTrue(bridge.contains("contenu: val(\"q-accroche\")"))
        assertFalse(bridge.contains("onTemplateSelected(\"RESTAURATION-T4\")"))
        assertTrue(bridge.contains("vitrine-countdown"))
        assertTrue(html.contains("class=\"t5-guide-copy\""))
        assertTrue(html.contains("EST-CE QU'ON VOIT BIEN TOUTE LA FORMULE ?"))
        assertTrue(html.contains("MONTRE LE PLAT, LA BOISSON ET L'ACCOMPAGNEMENT."))
        assertTrue(html.contains("VOIR MON MONTAGE"))
        assertFalse(html.contains("screen_id"))
        assertFalse(html.contains("T5_CAPTURE_"))
    }

    @Test
    fun merchantAnswersValidateAgainstTheBundledSixFieldSchema() {
        val pkg = load()
        val answers = linkedMapOf(
            "nom" to "Cantine Kandi",
            "offre" to "Formule du jour",
            "contenu" to "1 plat, 1 boisson, 1 accompagnement",
            "prix" to "5 000 FCFA",
            "whatsapp" to "+229 01 97 00 00 00",
            "quartier" to "Kandi marché"
        )
        val mapped = MockupResponseMapper.parseFromWebJson(JSONObject(answers).toString(), TemplateCatalogue.RESTAURATION_T5)
        assertEquals(answers, mapped)
        assertEquals(6, TemplateJobAssembler.validateResponses(pkg, mapped).size)
        assertThrows(com.vitrinevideo.engine.form.TemplateFormValidationException::class.java) {
            TemplateJobAssembler.validateResponses(pkg, mapped + ("produit" to "inventé"))
        }
    }

    private fun take(index: Int, durationUs: Long, source: CaptureSource = CaptureSource.CAMERAX_REAL): CapturedSceneTake {
        val file = kotlin.io.path.createTempFile("t5_scene_s${index + 1}", ".mp4").toFile().apply { writeText("camera take") }
        return CapturedSceneTake(
            sceneIndex = index,
            sceneId = "S${index + 1}",
            filePath = file.absolutePath,
            durationUs = durationUs,
            width = 720,
            height = 1280,
            rotation = 0,
            audioPresent = false,
            sha256 = "b".repeat(64),
            source = source,
            validatedByMerchant = false,
            recordedAtEpochMs = 1L
        )
    }

    @Test
    fun readinessUsesEngineDurationsAndRejectsShortOrOutOfOrderTakes() {
        val pkg = load()
        val progression = CaptureProgressionStateMachine()
        progression.configureRequirements(pkg)
        assertFalse(progression.canOpenSceneCamera(1))
        progression.onRecordingCompleted(take(0, 6_050_000L))
        progression.acceptScene(0)
        assertTrue(progression.canOpenSceneCamera(1))
        progression.onRecordingCompleted(take(1, 7_700_000L))
        assertThrows(RealSessionViolationException::class.java) { progression.acceptScene(1) }
        assertFalse(progression.isAllScenesValidated())
        progression.retakeScene(1)
        progression.onRecordingCompleted(take(1, 8_100_000L))
        progression.acceptScene(1)
        progression.onRecordingCompleted(take(2, 7_050_000L))
        progression.acceptScene(2)
        progression.onRecordingCompleted(take(3, 9_050_000L))
        progression.acceptScene(3)
        assertTrue(progression.isAllScenesValidated())
        assertTrue(progression.allValidatedTakes().all { it.source == CaptureSource.CAMERAX_REAL })
        assertEquals(12, progression.visitedScreens().size)
    }
}
