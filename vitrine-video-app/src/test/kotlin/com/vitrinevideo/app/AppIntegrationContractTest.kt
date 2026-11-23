package com.vitrinevideo.app

import com.vitrinevideo.app.flow.AppExecutionMode
import com.vitrinevideo.app.flow.CaptureProgressionStateMachine
import com.vitrinevideo.app.flow.CaptureSource
import com.vitrinevideo.app.flow.CapturedSceneTake
import com.vitrinevideo.app.flow.MockupResponseMapper
import com.vitrinevideo.app.flow.RealSessionGuard
import com.vitrinevideo.app.flow.RealSessionViolationException
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream

class AppIntegrationContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun assetsRoot(): File {
        val candidates = listOf(
            File("src/main/assets"),
            File("vitrine-video-app/src/main/assets")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Impossible de localiser src/main/assets depuis ${File(".").absolutePath}")
    }

    private fun createDummyVideoFile(name: String, content: String): File {
        val f = tempFolder.newFile(name)
        f.writeText(content)
        return f
    }

    private fun makeRealTake(
        sceneIndex: Int,
        durationSec: Int,
        file: File,
        validated: Boolean = false
    ): CapturedSceneTake = CapturedSceneTake(
        sceneIndex = sceneIndex,
        sceneId = "S${sceneIndex + 1}",
        filePath = file.absolutePath,
        durationUs = durationSec * 1_000_000L,
        width = 720,
        height = 1280,
        rotation = 0,
        audioPresent = true,
        sha256 = PreFlightValidator.sha256File(file),
        source = CaptureSource.CAMERAX_REAL,
        validatedByMerchant = validated,
        recordedAtEpochMs = 1_760_000_000_000L + sceneIndex
    )

    @Test
    fun bundledAssets_andT3Package_areIntactAndParityVerified() {
        val assets = assetsRoot()
        val htmlFile = File(assets, "mockup/vitrine-video-maquette.html")
        val cssFile = File(assets, "mockup/android-host.css")
        val bridgeFile = File(assets, "mockup/android-bridge.js")
        val zipFile = File(assets, "packages/COMMERCE-T3-1.0.0.zip")

        assertTrue(htmlFile.exists())
        assertTrue(cssFile.exists())
        assertTrue(bridgeFile.exists())
        assertTrue(zipFile.exists())

        val html = htmlFile.readText()
        assertTrue("android-host.css doit être chargé", html.contains("android-host.css"))
        assertTrue("android-bridge.js doit être chargé", html.contains("android-bridge.js"))
        assertTrue("window.__vitrineFlow doit être exposé", html.contains("window.__vitrineFlow"))

        // Vérifie que chaque référence assets/... dans la maquette HTML existe localement
        val refRegex = Regex("""assets/(images|audio)/[A-Za-z0-9._-]+""")
        val refs = refRegex.findAll(html).map { it.value }.toSet()
        assertTrue("Au moins 60 références médias attendues", refs.size >= 60)
        for (ref in refs) {
            val f = File(assets, "mockup/$ref")
            assertTrue("Asset manquant dans l'APK: $ref", f.exists() && f.length() > 0L)
        }

        // Charge et vérifie le ZIP COMMERCE-T3-1.0.0 avec le vrai TemplatePackageLoader
        val pkgDir = tempFolder.newFolder("pkg_store")
        val pkg = FileInputStream(zipFile).use { TemplatePackageLoader.importZipStream(it, pkgDir) }
        assertEquals("COMMERCE-T3", pkg.templateId)
        assertEquals("1.0.0", pkg.version)
        assertEquals(listOf(6, 8, 7, 9), pkg.captureGuides.map { it.durationSec })
        assertEquals(
            listOf("nom", "produit", "prix", "demonstration", "quartier", "whatsapp"),
            pkg.formSpec.questions.map { it.key }
        )
    }

    @Test
    fun mockupResponseMapper_mapsAccrocheToDemonstration_andPreservesBlankPrice() {
        val assets = assetsRoot()
        val zipFile = File(assets, "packages/COMMERCE-T3-1.0.0.zip")
        val pkg = FileInputStream(zipFile).use {
            TemplatePackageLoader.importZipStream(it, tempFolder.newFolder("pkg_map"))
        }

        val webPayload = """
            {
              "nom": "  Boutique Élégance  ",
              "produit": "Sac cuir artisanal",
              "prix": "   ",
              "demonstration": "On voit la fermeture dorée et la doublure",
              "quartier": "Akpakpa",
              "whatsapp": "+229 01 97 00 00 00"
            }
        """.trimIndent()

        val normalized = MockupResponseMapper.parseFromWebJson(webPayload)
        val validated = MockupResponseMapper.validateAgainstPackage(pkg, normalized)
        assertEquals("Boutique Élégance", validated["nom"])
        assertEquals("Sac cuir artisanal", validated["produit"])
        assertEquals("", validated["prix"])
        assertEquals("On voit la fermeture dorée et la doublure", validated["demonstration"])
        assertEquals("Akpakpa", validated["quartier"])
        assertEquals("+229 01 97 00 00 00", validated["whatsapp"])
    }

    @Test
    fun progressionStateMachine_enforcesSequentialOrder_andPreservesOtherScenesOnRetake() {
        val sm = CaptureProgressionStateMachine(executionMode = AppExecutionMode.REAL_APPLICATION)
        val f1 = createDummyVideoFile("s1.mp4", "real-s1")
        val f2a = createDummyVideoFile("s2a.mp4", "real-s2-first")
        val f2b = createDummyVideoFile("s2b.mp4", "real-s2-retake")
        val f3 = createDummyVideoFile("s3.mp4", "real-s3")
        val f4 = createDummyVideoFile("s4.mp4", "real-s4")

        assertTrue(sm.canOpenSceneCamera(0))
        assertFalse(sm.canOpenSceneCamera(1))

        sm.onRecordingCompleted(makeRealTake(0, 6, f1))
        assertFalse(sm.canOpenSceneCamera(1)) // pas encore accepté
        sm.acceptScene(0)
        assertTrue(sm.canOpenSceneCamera(1))

        sm.onRecordingCompleted(makeRealTake(1, 8, f2a))
        sm.acceptScene(1)
        sm.onRecordingCompleted(makeRealTake(2, 7, f3))
        sm.acceptScene(2)
        sm.onRecordingCompleted(makeRealTake(3, 9, f4))
        sm.acceptScene(3)
        assertTrue(sm.isAllScenesValidated())

        // Refaire uniquement S2 doit préserver S1, S3, S4
        sm.retakeScene(1)
        assertFalse(sm.isAllScenesValidated())
        assertTrue(sm.hasValidatedScene(0))
        assertFalse(sm.hasValidatedScene(1))
        assertTrue(sm.hasValidatedScene(2))
        assertTrue(sm.hasValidatedScene(3))

        sm.onRecordingCompleted(makeRealTake(1, 8, f2b))
        sm.acceptScene(1)
        assertTrue(sm.isAllScenesValidated())
        assertEquals(f2b.absolutePath, sm.allValidatedTakes()[1].filePath)
    }

    @Test(expected = RealSessionViolationException::class)
    fun realApplicationMode_strictlyRejectsSyntheticCaptures_withoutSilentFallback() {
        val sm = CaptureProgressionStateMachine(executionMode = AppExecutionMode.REAL_APPLICATION)
        val syntheticFile = createDummyVideoFile("synth.mp4", "synthetic-s1")
        sm.onRecordingCompleted(
            CapturedSceneTake(
                sceneIndex = 0,
                sceneId = "S1",
                filePath = syntheticFile.absolutePath,
                durationUs = 6_000_000L,
                width = 720,
                height = 1280,
                rotation = 0,
                audioPresent = true,
                sha256 = PreFlightValidator.sha256File(syntheticFile),
                source = CaptureSource.TECHNICAL_FIXTURE,
                validatedByMerchant = false,
                recordedAtEpochMs = 1L
            )
        )
    }

    @Test
    fun realSessionGuard_andTemplateJobAssembler_produceValidT3RenderJob() {
        val assets = assetsRoot()
        val zipFile = File(assets, "packages/COMMERCE-T3-1.0.0.zip")
        val pkg = FileInputStream(zipFile).use {
            TemplatePackageLoader.importZipStream(it, tempFolder.newFolder("pkg_job"))
        }

        val f1 = createDummyVideoFile("cam_s1.mp4", "camera-take-1")
        val f2 = createDummyVideoFile("cam_s2.mp4", "camera-take-2")
        val f3 = createDummyVideoFile("cam_s3.mp4", "camera-take-3")
        val f4 = createDummyVideoFile("cam_s4.mp4", "camera-take-4")

        val takes = listOf(
            makeRealTake(0, 6, f1, validated = true),
            makeRealTake(1, 8, f2, validated = true),
            makeRealTake(2, 7, f3, validated = true),
            makeRealTake(3, 9, f4, validated = true)
        )
        val responses = mapOf(
            "nom" to "Atelier Kandi",
            "produit" to "Robe Wax Premium",
            "prix" to "15 000 FCFA",
            "demonstration" to "Coupe fluide et finitions main",
            "quartier" to "Centre-ville",
            "whatsapp" to "+229 97 11 22 33"
        )

        val (validatedResponses, descriptors) = RealSessionGuard.verifyAndBuildDescriptors(
            mode = AppExecutionMode.REAL_APPLICATION,
            pkg = pkg,
            sessionId = "sess_d46_test",
            responses = responses,
            takes = takes
        )
        val assembled = TemplateJobAssembler.assemble(
            pkg = pkg,
            sessionId = "sess_d46_test",
            rawResponses = validatedResponses,
            captures = descriptors,
            outputDir = tempFolder.newFolder("out_d46"),
            rotationDbFile = tempFolder.newFile("rot.db"),
            visitedScreens = (1..12).toList()
        )
        assertEquals("COMMERCE-T3", assembled.jobJson.getString("template_id"))
        assertEquals("1.0.0", assembled.jobJson.getString("template_version"))
        assertEquals(4, assembled.jobJson.getJSONArray("captures").length())
        assertEquals("Coupe fluide et finitions main", assembled.jobJson.getJSONObject("reponses").getString("demonstration"))
    }

    @Test(expected = RealSessionViolationException::class)
    fun realSessionGuard_rejectsDuplicateFilesAcrossScenes() {
        val assets = assetsRoot()
        val zipFile = File(assets, "packages/COMMERCE-T3-1.0.0.zip")
        val pkg = FileInputStream(zipFile).use {
            TemplatePackageLoader.importZipStream(it, tempFolder.newFolder("pkg_dup"))
        }

        val sameFile = createDummyVideoFile("same.mp4", "identical-bytes")
        val takes = listOf(
            makeRealTake(0, 6, sameFile, validated = true),
            makeRealTake(1, 8, sameFile, validated = true),
            makeRealTake(2, 7, createDummyVideoFile("s3.mp4", "s3"), validated = true),
            makeRealTake(3, 9, createDummyVideoFile("s4.mp4", "s4"), validated = true)
        )
        val responses = mapOf(
            "nom" to "Boutique Test",
            "produit" to "Article Test",
            "prix" to "",
            "demonstration" to "Détail produit",
            "quartier" to "Cotonou",
            "whatsapp" to "97000000"
        )

        RealSessionGuard.verifyAndBuildDescriptors(
            mode = AppExecutionMode.REAL_APPLICATION,
            pkg = pkg,
            sessionId = "sess_dup",
            responses = responses,
            takes = takes
        )
    }
}
