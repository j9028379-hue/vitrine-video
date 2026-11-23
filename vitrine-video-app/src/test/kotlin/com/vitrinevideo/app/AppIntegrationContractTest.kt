package com.vitrinevideo.app

import com.vitrinevideo.app.flow.AppExecutionMode
import com.vitrinevideo.app.flow.CaptureProgressionStateMachine
import com.vitrinevideo.app.flow.CaptureSource
import com.vitrinevideo.app.flow.CapturedSceneTake
import com.vitrinevideo.app.flow.MockupResponseMapper
import com.vitrinevideo.app.flow.RealSessionGuard
import com.vitrinevideo.app.flow.RealSessionViolationException
import com.vitrinevideo.app.flow.CreationSessionStatus
import com.vitrinevideo.app.flow.SessionLifecycleManager
import com.vitrinevideo.engine.api.CaptureClip
import com.vitrinevideo.engine.api.EngineConfig
import com.vitrinevideo.engine.api.RenderRequest
import com.vitrinevideo.engine.capture.ProbedMediaMetadata
import com.vitrinevideo.engine.capture.SceneCaptureDescriptor
import com.vitrinevideo.engine.capture.VideoMetadataProbe
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.timeline.PreFlightException
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private fun loadBundledT3Package(subDirName: String) =
        FileInputStream(File(assetsRoot(), "packages/COMMERCE-T3-1.0.0.zip")).use {
            TemplatePackageLoader.importZipStream(it, tempFolder.newFolder(subDirName))
        }

    private fun createTakeInDir(
        capturesDir: File,
        sceneIndex: Int,
        durationSec: Int,
        payload: String,
        validated: Boolean = false
    ): CapturedSceneTake {
        capturesDir.mkdirs()
        val f = File(capturesDir, "S${sceneIndex + 1}_take_${System.nanoTime()}.mp4")
        f.writeText(payload)
        return makeRealTake(sceneIndex, durationSec, f, validated = validated)
    }

    private fun sampleResponses(tag: String): Map<String, String> = mapOf(
        "nom" to "Boutique $tag",
        "produit" to "Produit $tag",
        "prix" to "12 000 FCFA",
        "demonstration" to "Geste $tag clair",
        "quartier" to "Cotonou",
        "whatsapp" to "+229 97 00 00 01"
    )

    private fun stubMetadataProbe(): VideoMetadataProbe = VideoMetadataProbe { file ->
        val path = file.absolutePath
        val durUs = when {
            path.contains("S1") -> 6_000_000L
            path.contains("S2") -> 8_000_000L
            path.contains("S3") -> 7_000_000L
            else -> 9_000_000L
        }
        ProbedMediaMetadata(
            width = 720,
            height = 1280,
            rotation = 0,
            durationUs = durUs,
            hasVideo = true,
            hasAudio = true
        )
    }

    @Test
    fun sessionReset_1_newCreationGeneratesFreshUuidAndEmptyDraft() {
        val filesDir = tempFolder.newFolder("app_files_1")
        val sm = SessionLifecycleManager(filesDir)
        sm.initialize()

        assertTrue(sm.creationId.isNotBlank())
        assertTrue(sm.responses.isEmpty())
        assertEquals(0, sm.progression.allValidatedTakes().size)
        assertEquals(CreationSessionStatus.DRAFT, sm.status)
        assertTrue(sm.currentSessionCaptureDir().isDirectory)
        assertTrue(sm.currentSessionCaptureDir().absolutePath.contains(sm.creationId))

        val firstId = sm.creationId
        sm.updateResponses(sampleResponses("Un"))
        sm.startNewBlankSession()
        val newId = sm.creationId
        assertNotEquals(firstId, newId)
        assertTrue(sm.responses.isEmpty())
        assertEquals(0, sm.progression.allValidatedTakes().size)
    }

    @Test
    fun sessionReset_2_confirmedSuccessDeletesTempScenesAndAnswersWhilePreservingFinalMp4() {
        val filesDir = tempFolder.newFolder("app_files_2")
        val pkg = loadBundledT3Package("pkg_reset_2")
        val sm = SessionLifecycleManager(filesDir)
        sm.initialize()

        val firstSessionId = sm.creationId
        val firstCaptureDir = sm.currentSessionCaptureDir()
        sm.updateResponses(sampleResponses("Session1"))

        val sceneFiles = mutableListOf<File>()
        val durations = listOf(6, 8, 7, 9)
        for (i in 0..3) {
            val take = createTakeInDir(firstCaptureDir, i, durations[i], "session1-scene-$i-bytes")
            sceneFiles += File(take.filePath)
            sm.onSceneRecordingCompleted(take)
            sm.acceptScene(i)
        }

        val verified = sm.buildVerifiedRenderInputs(pkg)
        assertEquals(firstSessionId, verified.creationId)
        assertEquals(4, verified.descriptors.size)

        // Simulate Media3 output written to permanent export folder
        val permanentExportDir = tempFolder.newFolder("vitrine_exports_2", firstSessionId)
        val permanentMp4 = File(permanentExportDir, "Film.mp4").apply {
            writeBytes(ByteArray(2048) { (it % 127).toByte() })
        }

        sm.onRenderSucceededAndVerified(permanentMp4, "Movies/VitrineVideo/Vitrine_T3_test.mp4")

        // 1. All 4 temporary scene files and session temp directory are deleted
        sceneFiles.forEach { assertFalse("Temp scene doit être supprimée: ${it.name}", it.exists()) }
        assertFalse(File(filesDir, "sessions_tmp/$firstSessionId").exists())

        // 2. Permanent final MP4 is untouched
        assertTrue(permanentMp4.exists())
        assertEquals(2048L, permanentMp4.length())

        // 3. New blank session started automatically with fresh UUID and empty answers/scenes
        assertNotEquals(firstSessionId, sm.creationId)
        assertTrue(sm.responses.isEmpty())
        assertEquals(0, sm.progression.allValidatedTakes().size)
        assertTrue(sm.getClosedSessionIds().contains(firstSessionId))
        assertEquals(4, sm.getConsumedCaptureHashes().size)

        // 4. Restarting app does NOT resurrect old responses or old session ID
        val reloaded = SessionLifecycleManager(filesDir)
        reloaded.initialize()
        assertNotEquals(firstSessionId, reloaded.creationId)
        assertTrue(reloaded.responses.isEmpty())
        assertEquals(0, reloaded.progression.allValidatedTakes().size)
    }

    @Test
    fun sessionReset_3_secondCreationRejectsOldSceneFilesAndOldSessionId() {
        val filesDir = tempFolder.newFolder("app_files_3")
        val pkg = loadBundledT3Package("pkg_reset_3")
        val sm = SessionLifecycleManager(filesDir)
        sm.initialize()

        val firstId = sm.creationId
        val capDir1 = sm.currentSessionCaptureDir()
        sm.updateResponses(sampleResponses("Premiere"))
        val durations = listOf(6, 8, 7, 9)
        val s1TakeBytes = "session1-unique-take-0"
        for (i in 0..3) {
            val payload = if (i == 0) s1TakeBytes else "session1-unique-take-$i"
            val take = createTakeInDir(capDir1, i, durations[i], payload)
            sm.onSceneRecordingCompleted(take)
            sm.acceptScene(i)
        }
        val permanentMp4 = tempFolder.newFile("permanent_film_3.mp4").apply { writeText("valid-mp4-output") }
        sm.onRenderSucceededAndVerified(permanentMp4, "Movies/VitrineVideo/Film3.mp4")

        // Second session attempts to reuse identical bytes from session 1
        val capDir2 = sm.currentSessionCaptureDir()
        val recycledTake = createTakeInDir(capDir2, 0, 6, s1TakeBytes)
        try {
            sm.onSceneRecordingCompleted(recycledTake)
            fail("La réutilisation du hash d'une session clôturée doit être rejetée")
        } catch (expected: RealSessionViolationException) {
            assertEquals("CONSUMED_CAPTURE_HASH_REUSED", expected.code)
        }

        // Engine PreFlightValidator also blocks both reused session_id and reused capture sha256
        val freshTake = createTakeInDir(capDir2, 0, 6, "brand-new-s1")
        val plan = UniversalJsonInterpreter.parsePartition(pkg.partitionFile)
        val audioProfile = pkg.audioProfileFile?.let { UniversalJsonInterpreter.parseAudioProfile(it) }
        val reusedReq = RenderRequest(
            templateId = pkg.templateId,
            templateVersion = pkg.version,
            partitionJsonFile = pkg.partitionFile,
            audioProfileJsonFile = pkg.audioProfileFile,
            resourcesDir = pkg.packageDir,
            responses = sampleResponses("Deux"),
            captures = listOf(
                CaptureClip(
                    sceneId = "S1",
                    file = File(freshTake.filePath),
                    expectedSha256 = freshTake.sha256,
                    validated = true,
                    sessionId = firstId
                )
            ),
            outputDir = tempFolder.newFolder("out_preflight_3"),
            rotationDbFile = tempFolder.newFile("rot_preflight_3.db"),
            manifestJsonFile = pkg.manifestFile,
            sessionId = firstId,
            rejectedPreviousSessionIds = sm.getClosedSessionIds(),
            rejectedPreviousCaptureSha256s = sm.getConsumedCaptureHashes()
        )
        try {
            PreFlightValidator.validate(
                context = null,
                request = reusedReq,
                plan = plan,
                audioProfile = audioProfile,
                config = EngineConfig(minFreeStorageBytes = 0L),
                probe = stubMetadataProbe()
            )
            fail("PreFlightValidator doit rejeter la réutilisation d'un sessionId clôturé")
        } catch (expected: PreFlightException) {
            assertEquals("SESSION_ALREADY_CLOSED", expected.code)
        }
    }

    @Test
    fun sessionReset_4_failedRenderPreservesTempScenesAndAnswersForRetry() {
        val filesDir = tempFolder.newFolder("app_files_4")
        val sm = SessionLifecycleManager(filesDir)
        sm.initialize()

        val sessionId = sm.creationId
        val capDir = sm.currentSessionCaptureDir()
        sm.updateResponses(sampleResponses("Retry"))
        val durations = listOf(6, 8, 7, 9)
        val sceneFiles = mutableListOf<File>()
        for (i in 0..3) {
            val take = createTakeInDir(capDir, i, durations[i], "retry-scene-$i")
            sceneFiles += File(take.filePath)
            sm.onSceneRecordingCompleted(take)
            sm.acceptScene(i)
        }

        sm.onRenderFailed()
        assertEquals(CreationSessionStatus.FAILED_PRESERVED, sm.status)
        sceneFiles.forEach { assertTrue("Les scènes doivent rester présentes après échec", it.exists()) }

        // App restart restores failed session intact so retry succeeds without re-filming
        val restarted = SessionLifecycleManager(filesDir)
        restarted.initialize()
        assertEquals(sessionId, restarted.creationId)
        assertEquals(CreationSessionStatus.FAILED_PRESERVED, restarted.status)
        assertEquals("Boutique Retry", restarted.responses["nom"])
        assertTrue(restarted.progression.isAllScenesValidated())
    }

    @Test
    fun sessionReset_5_unfinishedCreationResumesUntilCompletionOrExplicitReset() {
        val filesDir = tempFolder.newFolder("app_files_5")
        val sm = SessionLifecycleManager(filesDir)
        sm.initialize()
        val id1 = sm.creationId
        sm.updateResponses(sampleResponses("EnCours"))
        val take0 = createTakeInDir(sm.currentSessionCaptureDir(), 0, 6, "partial-s1")
        sm.onSceneRecordingCompleted(take0)
        sm.acceptScene(0)

        val resumed = SessionLifecycleManager(filesDir)
        resumed.initialize()
        assertEquals(id1, resumed.creationId)
        assertEquals("Boutique EnCours", resumed.responses["nom"])
        assertTrue(resumed.progression.hasValidatedScene(0))
        assertFalse(resumed.progression.hasValidatedScene(1))

        resumed.startNewBlankSession()
        val id2 = resumed.creationId
        assertNotEquals(id1, id2)
        assertTrue(resumed.responses.isEmpty())
        assertFalse(File(take0.filePath).exists())
    }

    @Test
    fun sessionReset_6_retakeSingleScenePreservesOtherThreeScenesAndAnswers() {
        val filesDir = tempFolder.newFolder("app_files_6")
        val sm = SessionLifecycleManager(filesDir)
        sm.initialize()
        sm.updateResponses(sampleResponses("Retake"))

        val capDir = sm.currentSessionCaptureDir()
        val durations = listOf(6, 8, 7, 9)
        val files = mutableListOf<File>()
        for (i in 0..3) {
            val take = createTakeInDir(capDir, i, durations[i], "retake-orig-$i")
            files += File(take.filePath)
            sm.onSceneRecordingCompleted(take)
            sm.acceptScene(i)
        }

        // Retake S2 only
        sm.retakeScene(1)
        assertFalse("L'ancien fichier S2 doit être supprimé", files[1].exists())
        assertTrue(files[0].exists())
        assertTrue(files[2].exists())
        assertTrue(files[3].exists())
        assertTrue(sm.progression.hasValidatedScene(0))
        assertFalse(sm.progression.hasValidatedScene(1))
        assertTrue(sm.progression.hasValidatedScene(2))
        assertTrue(sm.progression.hasValidatedScene(3))
        assertEquals("Boutique Retake", sm.responses["nom"])
    }

    @Test
    fun sessionReset_7_crossSessionTemplateJobAssemblerRejectsMismatchedSessionOwnership() {
        val filesDir = tempFolder.newFolder("app_files_7")
        val pkg = loadBundledT3Package("pkg_reset_7")
        val sm = SessionLifecycleManager(filesDir)
        sm.initialize()

        val otherDir = File(filesDir, "sessions_tmp/other-session-id/captures").apply { mkdirs() }
        val foreignTake = createTakeInDir(otherDir, 0, 6, "foreign-bytes")
        val okTake2 = createTakeInDir(sm.currentSessionCaptureDir(), 1, 8, "ok-s2")
        val okTake3 = createTakeInDir(sm.currentSessionCaptureDir(), 2, 7, "ok-s3")
        val okTake4 = createTakeInDir(sm.currentSessionCaptureDir(), 3, 9, "ok-s4")
        val plan = UniversalJsonInterpreter.parsePartition(pkg.partitionFile)
        val audioProfile = pkg.audioProfileFile?.let { UniversalJsonInterpreter.parseAudioProfile(it) }

        val ownershipReq = RenderRequest(
            templateId = pkg.templateId,
            templateVersion = pkg.version,
            partitionJsonFile = pkg.partitionFile,
            audioProfileJsonFile = pkg.audioProfileFile,
            resourcesDir = pkg.packageDir,
            responses = sampleResponses("Ownership"),
            captures = listOf(
                CaptureClip("S1", File(foreignTake.filePath), foreignTake.sha256, validated = true, sessionId = sm.creationId),
                CaptureClip("S2", File(okTake2.filePath), okTake2.sha256, validated = true, sessionId = sm.creationId),
                CaptureClip("S3", File(okTake3.filePath), okTake3.sha256, validated = true, sessionId = sm.creationId),
                CaptureClip("S4", File(okTake4.filePath), okTake4.sha256, validated = true, sessionId = sm.creationId)
            ),
            outputDir = tempFolder.newFolder("out_preflight_7"),
            rotationDbFile = tempFolder.newFile("rot_preflight_7.db"),
            manifestJsonFile = pkg.manifestFile,
            sessionId = sm.creationId
        )

        try {
            PreFlightValidator.validate(
                context = null,
                request = ownershipReq,
                plan = plan,
                audioProfile = audioProfile,
                config = EngineConfig(minFreeStorageBytes = 0L),
                probe = stubMetadataProbe()
            )
            fail("PreFlightValidator doit rejeter une capture issue d'un autre dossier session")
        } catch (expected: PreFlightException) {
            assertEquals("CROSS_SESSION_FILE_OWNERSHIP_VIOLATION", expected.code)
        }
    }
}
