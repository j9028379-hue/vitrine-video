package com.vitrinevideo.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vitrinevideo.engine.api.CaptureClip
import com.vitrinevideo.engine.api.RenderOutcome
import com.vitrinevideo.engine.api.RenderRequest
import com.vitrinevideo.engine.api.VitrineRenderEngine
import com.vitrinevideo.engine.graphics.TextMotionEvaluator
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.timeline.PreFlightValidator
import com.vitrinevideo.engine.video.TransitionGeometry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Emulator-only technical render check using the repository's existing marked
 * engineering fixtures. It is not founder footage and is not evidence of
 * physical-camera/audio intelligibility.
 */
@RunWith(AndroidJUnit4::class)
class VitrineRestaurationT3InstrumentedTest {
    @Test
    fun rendersRestorationT3AndRecordsTransitionAndMotionProbeFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.getExternalFilesDir(null), "t3_fixture")
        val zip = File(root, "RESTAURATION-T3-1.0.0.zip")
        val musicRoot = File(root, "music")
        assertTrue("CI T3 package missing", zip.isFile && zip.length() > 0)
        assertTrue("CI T3 vault missing", File(musicRoot, "RESTAURATION-T3/1.0.0/library.json").isFile)

        val pkg = TemplatePackageLoader.importZip(
            zip,
            File(context.cacheDir, "t3-package-store"),
            musicRoot
        )
        assertEquals("RESTAURATION-T3", pkg.templateId)
        assertNotNull(pkg.audioProfile)
        assertEquals(17, pkg.renderPlan.transitions.size)
        assertEquals(4, pkg.renderPlan.textOverlay?.motionGroups?.size)

        val sceneIds = listOf("S1", "S2", "S3", "S4")
        val captures = sceneIds.map { scene ->
            val video = File(root, "captures/$scene.mp4")
            assertTrue("CI engineering fixture $scene missing", video.isFile && video.length() > 0)
            CaptureClip(scene, video, PreFlightValidator.sha256File(video))
        }
        val output = File(context.cacheDir, "t3_out").apply {
            deleteRecursively()
            mkdirs()
        }
        val request = RenderRequest(
            templateId = pkg.templateId,
            templateVersion = pkg.version,
            partitionJsonFile = pkg.partitionFile,
            audioProfileJsonFile = pkg.audioProfileFile,
            resourcesDir = pkg.packageDir,
            responses = mapOf(
                "nom" to "TEST MERCHANT",
                "produit" to "TEST PRODUCT",
                "prix" to "2500",
                "whatsapp" to "+22997000000",
                "quartier" to "Kandi",
                "accroche" to "TEST HOOK"
            ),
            captures = captures,
            visitedScreens = (1..12).toList(),
            expectedScreenCount = 12,
            outputDir = output,
            rotationDbFile = File(output, "rotation.db"),
            manifestJsonFile = pkg.manifestFile,
            sessionId = "ci-restauration-t3-render",
            preparsedPlan = pkg.renderPlan,
            preparsedAudioProfile = pkg.audioProfile
        )
        val outcome = VitrineRenderEngine(context).render(request)
        assertTrue("Expected T3 render success, got $outcome", outcome is RenderOutcome.Success)
        val success = outcome as RenderOutcome.Success
        val mp4 = File(success.result.outputPath)
        assertTrue("Rendered T3 MP4 missing", mp4.isFile && mp4.length() > 0)
        assertEquals(720, success.report.width)
        assertEquals(1280, success.report.height)
        assertEquals(30.0, success.report.durationSec, 0.25)

        val transitionProbes = JSONArray().apply {
            for (spec in pkg.renderPlan.transitions) {
                val sampleFrames = listOf(
                    spec.startFrame,
                    spec.startFrame + (spec.durationFrames - 1) / 2,
                    spec.startFrame + spec.durationFrames - 1
                ).distinct()
                val frames = JSONArray().apply {
                    for (frame in sampleFrames) {
                        val state = TransitionGeometry.evaluate(spec, frame)
                        put(JSONObject().apply {
                            put("frame", frame)
                            put("state", state?.let { s ->
                                JSONObject().apply {
                                    put("type", s.type.name)
                                    put("progress", s.progressQ.toDouble())
                                    put("direction", s.direction)
                                    put("slide_whip", s.slideWhip)
                                    put("rects", JSONArray().apply {
                                        s.rects.forEach { r -> put(JSONArray(listOf(r.x, r.y, r.w, r.h))) }
                                    })
                                    put("diagonal_top_x", s.diagonalTopX.toDouble())
                                    put("diagonal_bottom_x", s.diagonalBottomX.toDouble())
                                    put("alpha_incoming", s.alphaIncoming.toDouble())
                                    put("blur_radius", s.blurRadius.toDouble())
                                }
                            })
                        })
                    }
                }
                transitionProbes.put(JSONObject().apply {
                    put("source_family", spec.sourceFamily)
                    put("source_variant", spec.sourceVariant)
                    put("source_profile", spec.sourceProfile)
                    put("motif", spec.motifId)
                    put("start_frame", spec.startFrame)
                    put("duration_frames", spec.durationFrames)
                    put("source_parameters", spec.sourceParametersJson)
                    put("samples", frames)
                })
            }
        }
        val motionProbes = JSONArray().apply {
            for (group in pkg.renderPlan.textOverlay!!.motionGroups) {
                val middle = group.startFrame + (group.revealEndFrame - group.startFrame) / 2 - 1
                val sampleFrames = listOf(group.startFrame, middle, group.revealEndFrame - 1)
                put(JSONObject().apply {
                    put("id", group.id)
                    put("motion", group.motionName)
                    put("start_frame", group.startFrame)
                    put("reveal_end_frame", group.revealEndFrame)
                    put("end_frame_excluded", group.endFrameExcluded)
                    put("rise_px", group.risePx)
                    put("price_rise_px", group.priceRisePx)
                    put("scrim_top", group.scrimTop)
                    put("scrim_bottom", group.scrimBottom)
                    put("samples", JSONArray().apply {
                        for (frame in sampleFrames) {
                            val value = TextMotionEvaluator.evaluate(group, frame, pkg.renderPlan.width, pkg.renderPlan.totalFrames)
                            put(JSONObject().apply {
                                put("frame", frame)
                                put("progress", value!!.progress.toDouble())
                                put("opacity", value.opacity.toDouble())
                                put("reveal_width_px", value.revealWidthPx.toDouble())
                                put("rise_offset_px", value.riseOffsetPx.toDouble())
                                put("price_settle_offset_px", value.priceSettleOffsetPx.toDouble())
                            })
                        }
                    })
                })
            }
        }
        File(output, "transition_probe.json").writeText(JSONObject().apply {
            put("fixture_provenance", "repository engineering fixture; not founder camera footage")
            put("rendered_mp4", mp4.name)
            put("transition_count", transitionProbes.length())
            put("transitions", transitionProbes)
            put("motion_groups", motionProbes)
        }.toString(2))
        File(output, "resultat.json").writeText(success.result.toJson().toString(2))
        File(output, "controle.json").writeText(success.report.toJson().toString(2))
    }
}
