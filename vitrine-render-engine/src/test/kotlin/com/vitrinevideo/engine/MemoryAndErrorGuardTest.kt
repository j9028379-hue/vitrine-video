package com.vitrinevideo.engine

import com.vitrinevideo.engine.api.CaptureClip
import com.vitrinevideo.engine.api.EngineConfig
import com.vitrinevideo.engine.api.RenderOutcome
import com.vitrinevideo.engine.api.RenderRequest
import com.vitrinevideo.engine.audio.AudioDuckingEngine
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MemoryAndErrorGuardTest {

    @Test
    fun streamingAudio30sMaintainsBoundedHeap() {
        val cfg = AudioMixConfig("bench", includeCaptureVoice = true)
        val engine = AudioDuckingEngine(48_000, 2, cfg)
        val cap = ShortArray(480 * 2) { 1000 }
        val mus = ShortArray(480 * 2) { 2000 }
        val out = ShortArray(480 * 2)

        System.gc()
        val rt = Runtime.getRuntime()
        val beforeBytes = rt.totalMemory() - rt.freeMemory()

        repeat(3000) {
            engine.processInterleavedPcm16(cap, mus, 480, out)
        }

        val afterBytes = rt.totalMemory() - rt.freeMemory()
        val deltaMb = (afterBytes - beforeBytes) / (1024.0 * 1024.0)
        assertTrue("Streaming DSP heap delta too high: ${deltaMb} MB", deltaMb < 4.0)
    }

    @Test
    fun missingCaptureAndShaMismatchProduceExplicitStructuredFailures() {
        val tmpDir = Files.createTempDirectory("vitrine_err_test").toFile()
        try {
            val part = File("/tmp/inspect/t3/montage/partition.json")
            if (!part.exists()) return
            val plan = com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter.parsePartition(part)

            val fakeS1 = File(tmpDir, "S1.mp4").apply { writeBytes(ByteArray(128)) }
            val req = RenderRequest(
                templateId = "COMMERCE-T3",
                templateVersion = "1.0.0",
                partitionJsonFile = part,
                audioProfileJsonFile = null,
                resourcesDir = tmpDir,
                responses = mapOf("nom" to "Test"),
                captures = listOf(
                    CaptureClip("S1", fakeS1, "0000000000000000000000000000000000000000000000000000000000000000"),
                    CaptureClip("S2", File(tmpDir, "S2.mp4"), ""),
                    CaptureClip("S3", File(tmpDir, "S3.mp4"), ""),
                    CaptureClip("S4", File(tmpDir, "S4.mp4"), "")
                ),
                outputDir = File(tmpDir, "out"),
                rotationDbFile = File(tmpDir, "rot.db")
            )
            // PreFlight must fail explicitly (without Android Context) on SHA mismatch or missing file
            var caught = false
            try {
                PreFlightValidator.validate(null, req, plan.copy(audioProfileId = ""), null, EngineConfig(minFreeStorageBytes = 1024L))
            } catch (e: com.vitrinevideo.engine.timeline.PreFlightException) {
                caught = true
                assertTrue(e.code in setOf("CAPTURE_SHA_MISMATCH", "CAPTURE_MISSING", "FONT_MISSING", "INVALID_VIDEO_STREAM"))
            }
            assertTrue("Expected explicit PreFlightException", caught)

            // Also verify storage guard triggers INSUFFICIENT_STORAGE when threshold exceeds usable space
            var storageCaught = false
            try {
                PreFlightValidator.validate(null, req, plan.copy(audioProfileId = ""), null, EngineConfig(minFreeStorageBytes = Long.MAX_VALUE))
            } catch (e: com.vitrinevideo.engine.timeline.PreFlightException) {
                storageCaught = (e.code == "INSUFFICIENT_STORAGE")
            }
            assertTrue("Expected INSUFFICIENT_STORAGE", storageCaught)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
