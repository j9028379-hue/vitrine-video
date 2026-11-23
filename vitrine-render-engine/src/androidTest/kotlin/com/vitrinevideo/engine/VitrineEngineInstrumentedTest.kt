package com.vitrinevideo.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vitrinevideo.engine.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device instrumentation test verifying full Media3 Transformer + OpenGL ES + MediaCodec
 * + AudioProcessor MP4 export on a target Android device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class VitrineEngineInstrumentedTest {

    @Test
    fun rendersFullCommerceT3TemplateOnDeviceWhenFixturesPresent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = File(context.filesDir, "t3_fixture")
        if (!fixtureRoot.exists()) {
            // Fixture bundle not pushed to device external/app storage; verify engine boots & lists capabilities
            val engine = VitrineRenderEngine(context)
            assertTrue(engine.supportedCapabilities().isNotEmpty())
            return
        }

        val outputDir = File(context.cacheDir, "t3_out").apply { deleteRecursively(); mkdirs() }
        val engine = VitrineRenderEngine(context)
        val request = RenderRequest(
            templateId = "COMMERCE-T3",
            templateVersion = "1.0.0",
            partitionJsonFile = File(fixtureRoot, "montage/partition.json"),
            audioProfileJsonFile = File(fixtureRoot, "audio/profil.json"),
            resourcesDir = fixtureRoot,
            responses = mapOf(
                "nom" to "Atelier Kossi",
                "benefice" to "Couture sur mesure livrée en 48h",
                "prix" to "15 000 FCFA",
                "whatsapp" to "+229 01 97 00 00 00"
            ),
            captures = listOf(
                CaptureClip("S1", File(fixtureRoot, "captures/S1.mp4"), ""),
                CaptureClip("S2", File(fixtureRoot, "captures/S2.mp4"), ""),
                CaptureClip("S3", File(fixtureRoot, "captures/S3.mp4"), ""),
                CaptureClip("S4", File(fixtureRoot, "captures/S4.mp4"), "")
            ),
            outputDir = outputDir,
            rotationDbFile = File(outputDir, "rotation.db")
        )

        val outcome = engine.render(request)
        assertTrue("Expected Success, got $outcome", outcome is RenderOutcome.Success)
        val success = outcome as RenderOutcome.Success
        assertEquals(720, success.report.width)
        assertEquals(1280, success.report.height)
        assertEquals(30.0, success.report.durationSec, 0.25)
        assertTrue(File(success.result.outputPath).exists())
    }
}
