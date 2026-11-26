package com.vitrinevideo.engine

import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
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
import java.nio.ByteBuffer
import java.security.MessageDigest

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
        val output = File(root, "android_evidence").apply {
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

        // Decode the actual Android-rendered MP4 (not the Python reference) and
        // fingerprint samples around every source transition. This is objective
        // evidence that Media3/GLES produced decodable pixels on the emulator;
        // it does not claim profile fidelity or human-perceived quality.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(mp4.absolutePath)
        val androidFrames = JSONArray()
        try {
            for (spec in pkg.renderPlan.transitions) {
                val frameNumbers = listOf(
                    spec.startFrame,
                    spec.startFrame + (spec.durationFrames - 1) / 2,
                    spec.startFrame + spec.durationFrames - 1
                ).distinct()
                for (frame in frameNumbers) {
                    val timestampUs = frame * 1_000_000L / pkg.renderPlan.fps
                    val decoded = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: throw AssertionError("Android MP4 decode returned no bitmap at frame $frame")
                    androidFrames.put(JSONObject().apply {
                        put("source_family", spec.sourceFamily)
                        put("source_profile", spec.sourceProfile)
                        put("source_parameters_json", spec.sourceParametersJson)
                        put("transition_start_frame", spec.startFrame)
                        put("transition_duration_frames", spec.durationFrames)
                        put("frame", frame)
                        put("timestamp_us", timestampUs)
                        put("decoded_width", decoded.width)
                        put("decoded_height", decoded.height)
                        put("sample", fingerprintFrame(decoded))
                    })
                    decoded.recycle()
                }
            }
        } finally {
            retriever.release()
        }
        val uniqueFrameHashes = (0 until androidFrames.length()).map {
            androidFrames.getJSONObject(it).getJSONObject("sample").getString("rgba_sample_sha256")
        }.toSet()
        File(output, "android_decoded_frame_evidence.json").writeText(JSONObject().apply {
            put("evidence_kind", "android_emulator_media3_gl_mp4_decode")
            put("fixture_provenance", "repository engineering fixture; not founder camera footage")
            put("rendered_mp4", mp4.name)
            put("rendered_mp4_bytes", mp4.length())
            put("rendered_mp4_sha256", PreFlightValidator.sha256File(mp4))
            put("decoder", "android.media.MediaMetadataRetriever")
            put("sampling", "start, midpoint, and last frame of each of 17 transitions; 64px-wide pixel fingerprint")
            put("fidelity_claim", false)
            put("frames", androidFrames)
        }.toString(2))
        File(output, "source_execution_matrix.json").writeText(JSONObject().apply {
            put("status", "INCOMPLETE_PROFILE_EXECUTION")
            put("note", "Source properties and Android pixel samples are linked by event; property-level GPU consumption is not claimed.")
            put("transitions", JSONArray().apply {
                pkg.renderPlan.transitions.forEach { spec ->
                    val propertyRows = JSONArray()
                    JSONObject(spec.sourceParametersJson).let { source ->
                        appendSourceProperties(source, "", propertyRows)
                    }
                    val eventFrames = JSONArray().apply {
                        for (index in 0 until androidFrames.length()) {
                            val sampleObject = androidFrames.getJSONObject(index)
                            if (sampleObject.getInt("transition_start_frame") == spec.startFrame) {
                                put(sampleObject.getInt("frame"))
                            }
                        }
                    }
                    put(JSONObject().apply {
                        put("source_family", spec.sourceFamily)
                        put("source_profile", spec.sourceProfile)
                        put("runtime_type", spec.type.name)
                        put("runtime_motif", spec.motifId)
                        put("source_properties", propertyRows)
                        put("android_decoded_sample_frames", eventFrames)
                    })
                }
            })
        }.toString(2))
        assertTrue("Android transition samples must include visibly distinct decoded frames", uniqueFrameHashes.size > 5)
        File(output, "resultat.json").writeText(success.result.toJson().toString(2))
        File(output, "controle.json").writeText(success.report.toJson().toString(2))
    }

    private fun appendSourceProperties(value: Any?, path: String, rows: JSONArray) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    appendSourceProperties(value.get(key), if (path.isEmpty()) key else "$path.$key", rows)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                appendSourceProperties(value.get(index), "$path[$index]", rows)
            }
            else -> rows.put(JSONObject().apply {
                put("source_path", path)
                put("source_value", value ?: JSONObject.NULL)
                put("runtime_interpretation", "retained in source metadata; no profile-specific GPU consumption implemented")
                put("android_property_execution_observed", false)
            })
        }
    }

    private fun fingerprintFrame(bitmap: Bitmap): JSONObject {
        val width = 64
        val height = (bitmap.height.toFloat() * width / bitmap.width).toInt().coerceAtLeast(1)
        val sample = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            var red = 0L
            var green = 0L
            var blue = 0L
            var luma = 0L
            pixels.forEach { pixel ->
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                red += r
                green += g
                blue += b
                luma += (299L * r + 587L * g + 114L * b) / 1000L
            }
            val raw = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
            pixels.forEach { raw.putInt(it) }
            val hash = MessageDigest.getInstance("SHA-256").digest(raw.array())
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            val count = pixels.size.toDouble()
            return JSONObject().apply {
                put("sample_width", sample.width)
                put("sample_height", sample.height)
                put("mean_red", red / count)
                put("mean_green", green / count)
                put("mean_blue", blue / count)
                put("mean_luma", luma / count)
                put("rgba_sample_sha256", hash)
            }
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }
}
