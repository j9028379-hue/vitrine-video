package com.vitrinevideo.engine

import com.vitrinevideo.engine.capture.CaptureDurationPolicy
import com.vitrinevideo.engine.capture.SceneCaptureValidationSnapshot
import com.vitrinevideo.engine.capture.SceneCaptureValidator
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Regression proof for the 8 377 ms S2 capture that used to be rejected as 14 s. */
class CaptureDurationContractTest {

    private fun loadRestaurationT2() = TemplatePackageLoader.importZip(
        TestAssetLocator.packageZip("RESTAURATION-T2-1.0.0.zip"),
        kotlin.io.path.createTempDirectory("capture_duration_t2").toFile(),
        TestAssetLocator.projectFile("vitrine-video-app/src/main/assets/music")
    )

    private fun snapshot(file: File, sceneId: String, durationUs: Long, width: Int, height: Int, rotation: Int) =
        SceneCaptureValidationSnapshot(
            sceneId = sceneId,
            filePath = file.absolutePath,
            fileExists = file.exists(),
            fileReadable = file.canRead(),
            fileNonEmpty = file.length() > 0L,
            durationUs = durationUs,
            width = width,
            height = height,
            rotation = rotation,
            hasVideoStream = true,
            audioPresent = true,
            sha256 = "a".repeat(64),
            fromRealCamera = true
        )

    @Test
    fun restaurationT2ScenesUseSourceRelativeCaptureWindows() {
        val plan = loadRestaurationT2().renderPlan
        val scenes = plan.scenes.associateBy { it.id }
        assertEquals(mapOf("S1" to 6_000_000L, "S2" to 8_000_000L, "S3" to 7_000_000L, "S4" to 9_000_000L),
            scenes.mapValues { CaptureDurationPolicy.requiredDurationUs(it.value, plan.fps) })
        val s2 = scenes.getValue("S2")
        assertEquals(0, s2.shots.minOf { it.sourceInFrame })
        assertEquals(8 * plan.fps, s2.shots.maxOf { it.sourceOutFrame })
        assertEquals(6 * plan.fps, s2.startFrame)
        assertEquals(14 * plan.fps, s2.endFrameExcluded)
    }

    @Test
    fun codecShortTakeOf8377MsIsAcceptedForEightSecondScene() {
        val file = kotlin.io.path.createTempFile("s2_take", ".mp4").toFile().apply { writeText("take") }
        val failure = SceneCaptureValidator.validate(
            snapshot(file, "S2", 8_377_000L, 720, 1280, 0),
            requiredDurationUs = 8_000_000L
        )
        assertNull(failure?.message, failure?.message)
    }

    @Test
    fun genuinelyShortOrMisframedTakeIsRejectedByTheSameRules() {
        val file = kotlin.io.path.createTempFile("s2_bad", ".mp4").toFile().apply { writeText("take") }
        val short = SceneCaptureValidator.validate(
            snapshot(file, "S2", 7_700_000L, 720, 1280, 0),
            requiredDurationUs = 8_000_000L
        )
        assertNotNull(short)
        assertEquals("CAPTURE_DURATION_TOO_SHORT", short!!.code)

        val rotatedLandscape = SceneCaptureValidator.validate(
            snapshot(file, "S2", 8_100_000L, 1280, 720, 90),
            requiredDurationUs = 8_000_000L
        )
        assertNull(rotatedLandscape?.message, rotatedLandscape?.message)

        val unrotatedLandscape = SceneCaptureValidator.validate(
            snapshot(file, "S2", 8_100_000L, 1280, 720, 0),
            requiredDurationUs = 8_000_000L
        )
        assertNotNull(unrotatedLandscape)
        assertEquals("INVALID_CAPTURE_GEOMETRY", unrotatedLandscape!!.code)
    }
}
