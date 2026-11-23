package com.vitrinevideo.engine

import com.vitrinevideo.engine.capture.SceneCaptureDescriptor
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.preview.PreviewTimelineMapper
import com.vitrinevideo.engine.security.ProtectedResourceVault
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class LocalPreviewAndVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t3PartitionFile = File("/tmp/t3_server_test/montage/partition.json")
    private val t5PartitionFile = File("/tmp/t5_server_test/montage/partition.json")
    private val v12SpecFile = File("/home/user/google_drive/T1_ENGINEERING_SPEC_V1_2.json")

    @Test
    fun previewTimelineMapperMatchesTemplateShotsDeterministically() {
        val sampleFile = tmp.newFile("S1.mp4")
        val partitions = listOf(t3PartitionFile, t5PartitionFile, v12SpecFile).filter { it.exists() }
        assertTrue("Au moins un gabarit réel doit être présent", partitions.isNotEmpty())

        for (pFile in partitions) {
            val plan = UniversalJsonInterpreter.parsePartition(pFile)
            val captures = plan.scenes.associate { sc ->
                sc.id to SceneCaptureDescriptor(
                    sessionId = "sess-prev",
                    templateId = plan.templateId,
                    templateVersion = plan.version,
                    sceneId = sc.id,
                    localVideoPath = sampleFile.absolutePath,
                    durationUs = 12_000_000L,
                    width = 720,
                    height = 1280,
                    rotation = 0,
                    audioPresent = true
                )
            }
            val shots = PreviewTimelineMapper.buildShotSpecs(plan, captures)
            assertTrue(shots.isNotEmpty())
            val totalShotUs = shots.sumOf { it.expectedOutDurationUs }
            assertEquals(
                "La durée totale de prévisualisation doit égaler le gabarit ${plan.templateId}",
                (plan.totalFrames * 1_000_000L) / plan.fps,
                totalShotUs
            )
            // Verify zero gaps in output timeline frames
            var cursor = 0
            for (s in shots) {
                assertEquals(cursor, s.outStartFrame)
                assertTrue(s.clipEndMs > s.clipStartMs)
                cursor = s.outEndFrameExcluded
            }
            assertEquals(plan.totalFrames, cursor)
        }
    }

    @Test
    fun protectedResourceVaultEncryptsDecryptsAndWipesPlaintext() {
        val kg = KeyGenerator.getInstance("AES").apply { init(256) }
        val key = kg.generateKey()
        val vault = ProtectedResourceVault(tmp.newFolder("vault")) { key }

        val rawPartition = """{"template_id":"COMMERCE-T3","secret":"audio_bed_rules"}""".toByteArray()
        val rawAudio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)

        val sha = vault.storeEncrypted("montage/partition.json", rawPartition)
        assertEquals(64, sha.length)
        vault.storeEncrypted("audio/pistes/01-Afrobeat.mp3", rawAudio)
        assertTrue(vault.hasEncryptedAsset("montage/partition.json"))

        // Plaintext bytes must NOT appear on disk inside vault directory
        val storedFiles = vaultFilesIn(tmp.root.resolve("vault"))
        assertTrue(storedFiles.isNotEmpty())
        for (f in storedFiles) {
            assertFalse(f.readText(Charsets.ISO_8859_1).contains("COMMERCE-T3"))
        }

        val work = File(tmp.root, "ephemeral_work")
        vault.materializeToWorkdir(listOf("montage/partition.json", "audio/pistes/01-Afrobeat.mp3"), work)
        assertArrayEquals(rawPartition, File(work, "montage/partition.json").readBytes())
        assertArrayEquals(rawAudio, File(work, "audio/pistes/01-Afrobeat.mp3").readBytes())

        vault.wipeWorkdir(work)
        assertFalse(work.exists())
    }

    private fun vaultFilesIn(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile }.toList()
}
