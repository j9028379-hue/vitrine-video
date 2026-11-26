package com.vitrinevideo.engine

import com.vitrinevideo.engine.api.CaptureClip
import com.vitrinevideo.engine.api.EngineConfig
import com.vitrinevideo.engine.api.RenderRequest
import com.vitrinevideo.engine.capture.ProbedMediaMetadata
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.timeline.PreFlightException
import com.vitrinevideo.engine.timeline.PreFlightValidator
import com.vitrinevideo.engine.capture.VideoMetadataProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FounderAudioRenderPathTest {
    @get:Rule val tmp = TemporaryFolder()

    private val productionPackages = listOf(
        "COMMERCE-T1-2.0.0.zip",
        "COMMERCE-T2-1.0.0.zip",
        "COMMERCE-T3-1.0.0.zip",
        "COMMERCE-T4-1.0.0.zip",
        "RESTAURATION-T2-1.0.0.zip",
        "RESTAURATION-T3-1.0.0.zip",
        "RESTAURATION-T5-1.0.0.zip"
    )

    private val approvalPolicy: String by lazy {
        TestAssetLocator.projectFile("vitrine-render-engine/src/main/assets/config/founder-audio-approvals.json").readText()
    }

    private val probe = VideoMetadataProbe {
        ProbedMediaMetadata(width = 1280, height = 720, rotation = 0, durationUs = 0L, hasVideo = true, hasAudio = true)
    }

    @Test
    fun everyBundledProductionTemplateCrossesTheRenderAudioPreflightWithItsOwnProfile() {
        productionPackages.forEachIndexed { index, name ->
            val pkg = TemplatePackageLoader.importZip(
                TestAssetLocator.packageZip(name),
                tmp.newFolder("package-$index"),
                TestAssetLocator.projectFile("vitrine-video-app/src/main/assets/music")
            )
            val originalProfile = assertNotNullProfile(pkg.audioProfile, name)
            val request = requestFor(pkg, index)

            // This is the exact profile resolution method called by VitrineRenderEngine.render;
            // pass the loader's pre-parsed profile to exercise the fast path that regressed.
            val resolved = UniversalJsonInterpreter.resolveAudioProfileForRender(
                preparsedProfile = pkg.audioProfile,
                profileFile = pkg.audioProfileFile,
                founderApprovalJson = approvalPolicy,
                templateId = pkg.templateId
            )
            assertNotNull("No resolved audio profile for $name", resolved)
            assertEquals(originalProfile.id, resolved!!.id)

            if (pkg.templateId == "COMMERCE-T4") {
                assertFalse("T4 technical mix flag must remain false", resolved.mixApproved)
                assertTrue("T4 founder decision must cover only its playable package selection", resolved.hasFounderApprovedSelection)
                assertEquals("FONDATEUR-T4-MUSIQUES-2026-11-25", resolved.founderApprovalId)
                assertEquals(setOf(1, 3, 4), resolved.founderApprovedTrackNumbers)
                assertEquals(setOf(1, 3, 4), resolved.tracks.map { it.number }.toSet())
            } else {
                assertEquals("Unrelated package must not inherit the T4 approval", "", resolved.founderApprovalId)
                assertEquals(originalProfile.mixApproved, resolved.mixApproved)
            }

            val report = PreFlightValidator.validate(
                context = null,
                request = request,
                plan = pkg.renderPlan,
                audioProfile = resolved,
                config = EngineConfig(minFreeStorageBytes = 0L),
                probe = probe
            )
            assertEquals("Preflight did not finish its capture checks for $name", pkg.renderPlan.scenes.size, report.validatedCaptures.size)
        }
    }

    @Test
    fun explicitApprovalIsScopedToItsTemplateAndDoesNotApproveAnotherCatalog() {
        val pkg = TemplatePackageLoader.importZip(
            TestAssetLocator.packageZip("COMMERCE-T4-1.0.0.zip"),
            tmp.newFolder("t4-wrong-catalog"),
            TestAssetLocator.projectFile("vitrine-video-app/src/main/assets/music")
        )
        val wrongTemplateProfile = UniversalJsonInterpreter.resolveAudioProfileForRender(
            preparsedProfile = pkg.audioProfile,
            profileFile = pkg.audioProfileFile,
            founderApprovalJson = approvalPolicy,
            templateId = "COMMERCE-T1"
        )!!
        assertFalse(wrongTemplateProfile.hasFounderApprovedSelection)
        assertEquals("", wrongTemplateProfile.founderApprovalId)

        val failure = assertThrows(PreFlightException::class.java) {
            PreFlightValidator.validate(
                context = null,
                request = requestFor(pkg, 20),
                plan = pkg.renderPlan,
                audioProfile = wrongTemplateProfile,
                config = EngineConfig(minFreeStorageBytes = 0L),
                probe = probe
            )
        }
        assertEquals("AUDIO_NOT_APPROVED", failure.code)
    }

    private fun requestFor(pkg: com.vitrinevideo.engine.pkg.LoadedTemplatePackage, index: Int): RenderRequest {
        val capturesDir = tmp.newFolder("captures-$index")
        val captures = pkg.renderPlan.scenes.mapIndexed { sceneIndex, scene ->
            val file = File(capturesDir, "${scene.id}.mp4").apply { writeBytes(byteArrayOf((sceneIndex + 1).toByte())) }
            CaptureClip(
                sceneId = scene.id,
                file = file,
                expectedSha256 = PreFlightValidator.sha256File(file),
                sessionId = ""
            )
        }
        return RenderRequest(
            templateId = pkg.templateId,
            templateVersion = pkg.version,
            partitionJsonFile = pkg.partitionFile,
            audioProfileJsonFile = pkg.audioProfileFile,
            resourcesDir = pkg.packageDir,
            responses = emptyMap(),
            captures = captures,
            outputDir = tmp.newFolder("output-$index"),
            rotationDbFile = File(tmp.root, "rotation-$index.db"),
            preparsedPlan = pkg.renderPlan,
            preparsedAudioProfile = pkg.audioProfile
        )
    }

    private fun assertNotNullProfile(
        profile: com.vitrinevideo.engine.interpreter.AudioProfileSpec?,
        packageName: String
    ): com.vitrinevideo.engine.interpreter.AudioProfileSpec =
        profile ?: throw AssertionError("Package $packageName has no resolved audio profile")
}
