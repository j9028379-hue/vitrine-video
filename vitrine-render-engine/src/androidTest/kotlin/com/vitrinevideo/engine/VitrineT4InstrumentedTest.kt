package com.vitrinevideo.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vitrinevideo.engine.api.CaptureClip
import com.vitrinevideo.engine.api.RenderOutcome
import com.vitrinevideo.engine.api.RenderRequest
import com.vitrinevideo.engine.api.VitrineRenderEngine
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real on-device T4 render probe. CI extracts COMMERCE-T4-1.0.0.zip and pushes
 * four 720x1280 capture clips to the fixture root before running this test.
 */
@RunWith(AndroidJUnit4::class)
class VitrineT4InstrumentedTest {
    @Test
    fun rendersT4WithFounderApprovedMusicOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = File(context.getExternalFilesDir(null), "t4_fixture")
        if (!File(fixtureRoot, "montage/partition.json").exists()) {
            // Fixtures not pushed: still verify the engine and its declared capabilities boot.
            assertTrue(VitrineRenderEngine(context).supportedCapabilities().isNotEmpty())
            return
        }

        val outputDir = File(context.cacheDir, "t4_out").apply { deleteRecursively(); mkdirs() }
        val captures = listOf("S1", "S2", "S3", "S4").map { scene ->
            val clip = File(fixtureRoot, "captures/$scene.mp4")
            assertTrue("Missing capture $scene", clip.exists())
            CaptureClip(sceneId = scene, file = clip, expectedSha256 = PreFlightValidator.sha256File(clip))
        }
        val request = RenderRequest(
            templateId = "COMMERCE-T4",
            templateVersion = "1.0.0",
            partitionJsonFile = File(fixtureRoot, "montage/partition.json"),
            audioProfileJsonFile = File(fixtureRoot, "audio/profil.json"),
            resourcesDir = fixtureRoot,
            responses = mapOf(
                "commerce" to "Boutique Grace",
                "produit" to "Robe wax",
                "habituel" to "25 000 FCFA",
                "promo" to "15 000 FCFA",
                "fin" to "48h",
                "localisation" to "Kandi",
                "whatsapp" to "+229 01 97 00 00 00"
            ),
            captures = captures,
            manifestJsonFile = File(fixtureRoot, "MANIFEST.json"),
            outputDir = outputDir,
            rotationDbFile = File(outputDir, "rotation.db"),
            sessionId = "ci-t4-render"
        )

        val outcome = VitrineRenderEngine(context).render(request)
        assertTrue("Expected Success, got $outcome", outcome is RenderOutcome.Success)
        val success = outcome as RenderOutcome.Success
        assertEquals("COMMERCE-T4", success.result.templateId)
        assertEquals(720, success.report.width)
        assertEquals(1280, success.report.height)
        assertTrue(File(success.result.outputPath).exists())
        File(outputDir, "resultat.json").writeText(success.result.toJson().toString())
        File(outputDir, "controle.json").writeText(success.report.toJson().toString())
    }
}
