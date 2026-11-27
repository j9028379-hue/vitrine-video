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
            setOf("COMMERCE-T1", "COMMERCE-T2", "COMMERCE-T3", "COMMERCE-T4", "RESTAURATION-T1", "RESTAURATION-T2", "RESTAURATION-T3", "RESTAURATION-T5"),
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
        assertTrue(bridge.contains("\"r2\", \"r3\", \"r4\"].includes(fmt)"))
        assertTrue(bridge.contains("fmt === \"r4\" ? \"RESTAURATION-T5\""))
        assertTrue(bridge.contains("if (activeTemplateFormat === \"r4\") return {"))
        assertTrue(bridge.contains("offre: val(\"q-produit\")"))
        assertTrue(bridge.contains("contenu: val(\"q-accroche\")"))
        assertFalse(bridge.contains("onTemplateSelected(\"RESTAURATION-T4\")"))
        // One native owner for the approved sequence, for every template.
        assertFalse(bridge.contains("startWithCountdown"))
        assertTrue(File(assetsRoot(), "mockup/android-host.css").readText().contains("native RecordingCountdown"))
        // The explanatory text block is gone from the rendered parcours; the
        // commercial data it used to display stays in R4_SCENES for the contract.
        assertFalse(html.contains("t5-guide-copy"))
        assertTrue(html.contains("EST-CE QU'ON VOIT BIEN TOUTE LA FORMULE ?"))
        assertTrue(html.contains("MONTRE LE PLAT, LA BOISSON ET L'ACCOMPAGNEMENT."))
        assertTrue(html.contains("VOIR MON MONTAGE"))
        assertFalse(html.contains("screen_id"))
        assertFalse(html.contains("T5_CAPTURE_"))
    }

    @Test
    fun storefrontAndCameraSurfacesCarryNoTechnicalOrTestWording() {
        val html = File(assetsRoot(), "mockup/vitrine-video-maquette.html").readText()
        val bridge = File(assetsRoot(), "mockup/android-bridge.js").readText()
        val forbidden = listOf(
            "TEST T5", "DEBUG", "MOCK", "screen_id", "route_key",
            "capturer exactement", "vitesse naturelle",
            "non connectée dans cette maquette", "connected by the engineer",
            "seront connectés par l", "La maquette du parcours"
        )
        for (token in forbidden) {
            assertFalse("merchant UI must not show '$token'", html.contains(token) || bridge.contains(token))
        }
        // Approved guide functions stay untouched.
        for (required in listOf(
            "COMMENCER À FILMER", "ÉCOUTER LE CONSEIL",
            "guide-audio-toggle", "guide-audio-back", "guide-audio-forward",
            "guide-audio-seek", "guide-audio-current", "guide-audio-duration",
            "guide-scene-image"
        )) {
            assertTrue("approved T5 guide element missing: $required", html.contains(required))
        }
    }

    /**
     * Owner correction: a T5 scene opens on its illustration. The guide markup
     * must render header -> image -> audio -> COMMENCER À FILMER, with no text
     * block in between, exactly like the validated Restauration T2 screen.
     */
    @Test
    fun t5SceneGuideShowsTheImageImmediatelyUnderTheHeader() {
        val html = File(assetsRoot(), "mockup/vitrine-video-maquette.html").readText()
        val guideStart = html.indexOf("if(flow.stage===\"guide\"){")
        assertTrue(guideStart > 0)
        val guideEnd = html.indexOf("const audio=$(\"#guide-audio\")", guideStart)
        assertTrue(guideEnd > guideStart)
        val markup = html.substring(guideStart, guideEnd)

        val headerAt = markup.indexOf("guide-readable-header")
        val imageAt = markup.indexOf("guide-scene-image")
        val audioAt = markup.indexOf("guide-audio-panel")
        val startAt = markup.indexOf("COMMENCER À FILMER")
        assertTrue(headerAt in 0 until imageAt)
        assertTrue(imageAt < audioAt)
        assertTrue(audioAt < startAt)

        // No explanatory section may sit between the header and the image.
        val between = markup.substring(headerAt, imageAt)
        for (banned in listOf("À FILMER", "À ÉVITER", "CE QUE TU DOIS FAIRE", "guide-copy", "instruction")) {
            assertFalse("text block between header and image: $banned", between.contains(banned))
        }

        // Each scene keeps its own picture and its own audio guide.
        for (n in 1..4) {
            assertTrue(html.contains("assets/images/r4-scene$n-portrait.webp"))
            assertTrue(html.contains("assets/audio/r4-scene$n-guide.mp3"))
        }
        // The four official images are present and displayable. Their content is
        // NOT asserted to be distinct: scene 3 currently ships the same bytes as
        // scene 1, a pre-existing finding reported to the owner. Replacing it
        // would mean swapping an approved illustration, which is out of scope.
        val images = (1..4).map { File(assetsRoot(), "mockup/assets/images/r4-scene$it-portrait.webp") }
        assertTrue(images.all { it.isFile && it.length() > 0 })
        assertEquals(4, images.size)
    }

    @Test
    fun finalScreenShowsNoTechnicalDiagnosticsToTheMerchant() {
        val activity = File(
            listOf(File("src/main/kotlin"), File("vitrine-video-app/src/main/kotlin")).first { it.isDirectory },
            "com/vitrinevideo/app/MainActivity.kt"
        ).readText()
        // The verified format proof stays in the engine report and logcat only.
        for (hidden in listOf("Format vérifié", "Verified format", "Codecs :", "Codecs:")) {
            assertFalse("final screen must not show '$hidden'", activity.contains("\"$hidden"))
        }
        assertTrue(activity.contains("Ta vidéo est prête et enregistrée sur ton téléphone."))
        // The internal template id must never reach the final screen either.
        assertFalse(activity.contains("Ta publicité \$" + "renderedTemplate"))
        assertTrue(activity.contains("Ta publicité est prête !"))
        assertTrue(activity.contains("Log.e(\"VitrineRender\""))
        assertTrue(activity.contains("Log.w(\"VitrineFinalPlayer\""))
    }

    @Test
    fun countdownComponentIsSharedByEveryShippedTemplate() {
        val activity = File(
            listOf(File("src/main/kotlin"), File("vitrine-video-app/src/main/kotlin")).first { it.isDirectory },
            "com/vitrinevideo/app/MainActivity.kt"
        ).readText()
        val component = File(
            listOf(File("src/main/kotlin"), File("vitrine-video-app/src/main/kotlin")).first { it.isDirectory },
            "com/vitrinevideo/app/camera/RecordingCountdown.kt"
        ).readText()
        // One shared component, no template branch.
        assertFalse(component.contains("RESTAURATION-T5"))
        assertFalse(component.contains("COMMERCE-T"))
        // It runs before CameraX starts and after the merchant's tap.
        assertTrue(activity.contains("recordingCountdown.start { beginCameraRecording() }"))
        assertTrue(activity.contains("private fun beginCameraRecording()"))
        assertTrue(activity.contains("recordingCountdown.isRunning) return"))
        assertTrue(activity.indexOf("recordingCountdown.start {") < activity.indexOf("cameraRecorder.startRecording("))
        // The take's own duration still starts on the real CameraX start.
        assertTrue(component.contains("\"3\", \"2\", \"1\""))
        assertTrue(component.contains("ON FILME !"))
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
