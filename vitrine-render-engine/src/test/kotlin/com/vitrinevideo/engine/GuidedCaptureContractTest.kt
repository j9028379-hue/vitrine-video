package com.vitrinevideo.engine

import com.vitrinevideo.engine.api.EngineConfig
import com.vitrinevideo.engine.capture.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuidedCaptureContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun createT3Partition(dir: File): File {
        val f = File(dir, "partition.json")
        f.writeText(
            """
            {
              "template_id": "COMMERCE-T3",
              "version": "1.0.0",
              "fps": 30,
              "dimensions": [720, 1280],
              "images": 900,
              "scenes": [
                {"id": "S1", "debut": 0, "fin_exclue": 180},
                {"id": "S2", "debut": 180, "fin_exclue": 420},
                {"id": "S3", "debut": 420, "fin_exclue": 630},
                {"id": "S4", "debut": 630, "fin_exclue": 900}
              ],
              "liaisons": [
                {"debut": 180, "duree": 18, "type": "quatre_volets"},
                {"debut": 420, "duree": 17, "type": "case_vers_plein_cadre"},
                {"debut": 630, "duree": 15, "type": "balayage_diagonal"}
              ],
              "etalonnage": {"mode": "source_native"}
            }
            """.trimIndent()
        )
        return f
    }

    private fun createFakeMp4(name: String): File {
        val f = tmp.newFile(name)
        f.writeBytes(ByteArray(2048) { (it % 251).toByte() })
        return f
    }

    private fun baseSession(
        captures: List<SceneCaptureDescriptor>,
        state: CaptureSessionState = CaptureSessionState.READY_FOR_RENDER,
        permissions: CapturePermissionsSnapshot = CapturePermissionsSnapshot(true, true)
    ): GuidedCaptureSession {
        val outDir = tmp.newFolder("out_${System.nanoTime()}")
        return GuidedCaptureSession(
            sessionId = "sess-001",
            templateId = "COMMERCE-T3",
            templateVersion = "1.0.0",
            partitionJsonFile = createT3Partition(outDir),
            audioProfileJsonFile = null,
            resourcesDir = tmp.newFolder("res_${System.nanoTime()}"),
            outputDir = outDir,
            rotationDbFile = File(outDir, "rot.db"),
            responses = mapOf("nom" to "Boutique Élégance", "produit" to "Lampe"),
            captures = captures,
            sessionState = state,
            permissions = permissions
        )
    }

    private fun validDescriptors(): List<SceneCaptureDescriptor> = listOf(
        "S1" to 6_000_000L,
        "S2" to 8_000_000L,
        "S3" to 7_000_000L,
        "S4" to 9_000_000L
    ).map { (sc, dur) ->
        val file = createFakeMp4("$sc.mp4")
        SceneCaptureDescriptor(
            sessionId = "sess-001",
            templateId = "COMMERCE-T3",
            templateVersion = "1.0.0",
            sceneId = sc,
            localVideoPath = file.absolutePath,
            durationUs = dur,
            width = 720,
            height = 1280,
            rotation = 0,
            audioPresent = sc == "S2",
            recordingStatus = SceneRecordingStatus.COMPLETED
        )
    }

    private val okProbe = VideoMetadataProbe { f ->
        val dur = when {
            f.name.startsWith("S1") -> 6_000_000L
            f.name.startsWith("S2") -> 8_000_000L
            f.name.startsWith("S3") -> 7_000_000L
            else -> 9_000_000L
        }
        ProbedMediaMetadata(720, 1280, 0, dur, hasVideo = true, hasAudio = f.name.startsWith("S2"))
    }

    @Test
    fun validSessionMapsCleanlyToRenderRequest() {
        val session = baseSession(validDescriptors())
        val (plan, req) = GuidedCaptureValidator.validateAndMap(
            session,
            EngineConfig(minFreeStorageBytes = 1024L),
            okProbe
        )
        assertEquals("COMMERCE-T3", plan.templateId)
        assertEquals(listOf("S1", "S2", "S3", "S4"), req.captures.map { it.sceneId })
        assertTrue(req.captures.all { it.expectedSha256.length == 64 })
    }

    @Test
    fun rejectsMissingScene() {
        val session = baseSession(validDescriptors().take(3))
        val ex = assertThrows(GuidedCaptureValidationException::class.java) {
            GuidedCaptureValidator.validateAndMap(session, EngineConfig(minFreeStorageBytes = 1024L), okProbe)
        }
        assertEquals("SCENE_MISSING", ex.code)
    }

    @Test
    fun rejectsInterruptedRecording() {
        val caps = validDescriptors().mapIndexed { idx, d ->
            if (idx == 1) d.copy(recordingStatus = SceneRecordingStatus.INTERRUPTED) else d
        }
        val ex = assertThrows(GuidedCaptureValidationException::class.java) {
            GuidedCaptureValidator.validateAndMap(baseSession(caps), EngineConfig(minFreeStorageBytes = 1024L), okProbe)
        }
        assertEquals("RECORDING_INTERRUPTED", ex.code)
    }

    @Test
    fun rejectsCorruptContainer() {
        val corruptProbe = VideoMetadataProbe { null }
        val ex = assertThrows(GuidedCaptureValidationException::class.java) {
            GuidedCaptureValidator.validateAndMap(
                baseSession(validDescriptors()),
                EngineConfig(minFreeStorageBytes = 1024L),
                corruptProbe
            )
        }
        assertEquals("CORRUPT_FILE", ex.code)
    }

    @Test
    fun rejectsInsufficientDuration() {
        val shortProbe = VideoMetadataProbe {
            ProbedMediaMetadata(720, 1280, 0, 2_000_000L, hasVideo = true, hasAudio = false)
        }
        val ex = assertThrows(GuidedCaptureValidationException::class.java) {
            GuidedCaptureValidator.validateAndMap(
                baseSession(validDescriptors()),
                EngineConfig(minFreeStorageBytes = 1024L),
                shortProbe
            )
        }
        assertEquals("INSUFFICIENT_DURATION", ex.code)
    }

    @Test
    fun rejectsPermissionDenials() {
        val descriptors = validDescriptors()
        val camEx = assertThrows(GuidedCaptureValidationException::class.java) {
            GuidedCaptureValidator.validateAndMap(
                baseSession(descriptors, permissions = CapturePermissionsSnapshot(cameraGranted = false, microphoneGranted = true)),
                EngineConfig(minFreeStorageBytes = 1024L),
                okProbe
            )
        }
        assertEquals("CAMERA_PERMISSION_DENIED", camEx.code)

        val micEx = assertThrows(GuidedCaptureValidationException::class.java) {
            GuidedCaptureValidator.validateAndMap(
                baseSession(descriptors, permissions = CapturePermissionsSnapshot(cameraGranted = true, microphoneGranted = false)),
                EngineConfig(minFreeStorageBytes = 1024L),
                okProbe
            )
        }
        assertEquals("MICROPHONE_PERMISSION_DENIED", micEx.code)
    }

    @Test
    fun rejectsInsufficientStorage() {
        val ex = assertThrows(GuidedCaptureValidationException::class.java) {
            GuidedCaptureValidator.validateAndMap(
                baseSession(validDescriptors()),
                EngineConfig(minFreeStorageBytes = Long.MAX_VALUE / 2),
                okProbe
            )
        }
        assertEquals("INSUFFICIENT_STORAGE", ex.code)
    }

    @Test
    fun checkpointPersistsAndResumesAtNextIncompleteScene() {
        val ckFile = File(tmp.newFolder("session_ck"), "session_checkpoint.json")
        val partial = validDescriptors().take(2)
        CaptureSessionCheckpointStore.saveCheckpoint(
            checkpointFile = ckFile,
            sessionId = "sess-001",
            templateId = "COMMERCE-T3",
            templateVersion = "1.0.0",
            state = CaptureSessionState.INTERRUPTED,
            captures = partial,
            responses = mapOf("nom" to "Boutique Élégance")
        )
        val resumed = CaptureSessionCheckpointStore.loadResumeState(ckFile, listOf("S1", "S2", "S3", "S4"))
        assertNotNull(resumed)
        assertEquals(listOf("S1", "S2"), resumed!!.completedCaptures.map { it.sceneId })
        assertEquals("S3", resumed.nextSceneToCapture)
    }

    @Test
    fun parsesEcransCaptureGuide() {
        val sample = """
            {
              "ecrans": [
                {"numero": 1, "scene": 1, "etat": "A", "duree_s": 6, "titre": "PRÉPARE TON PRODUIT",
                 "blocs": {"CE QUE TU VAS FILMER": "Montre ton produit.", "À FILMER": ["Entier"], "À ÉVITER": ["Flou"]}},
                {"numero": 2, "scene": 1, "etat": "B", "duree_s": 6, "consigne": "MONTRE TON PRODUIT EN ENTIER.", "arret_automatique_s": 6}
              ]
            }
        """.trimIndent()
        val guides = GuidedCaptureGuideParser.parseFromEcransJson(sample)
        assertEquals(1, guides.size)
        assertEquals("S1", guides[0].sceneId)
        assertEquals(6, guides[0].durationSec)
        assertEquals("MONTRE TON PRODUIT EN ENTIER.", guides[0].onScreenPrompt)
    }
}
