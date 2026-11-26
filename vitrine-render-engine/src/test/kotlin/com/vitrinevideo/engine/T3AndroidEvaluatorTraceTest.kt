package com.vitrinevideo.engine

import com.vitrinevideo.engine.graphics.TextMotionEvaluator
import com.vitrinevideo.engine.interpreter.ContratCandidatParser
import com.vitrinevideo.engine.video.TransitionGeometry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Executes the Android pure geometry/text evaluators on every packaged T3 event. */
class T3AndroidEvaluatorTraceTest {
    @Test
    fun evaluatesAllSeventeenTransitionEventsAndFourTextGroupsOnTheHostJvm() {
        val zipFile = TestAssetLocator.packageZip("RESTAURATION-T3-1.0.0.zip")
        val traceTransitions = JSONArray()
        val traceGroups = JSONArray()
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("templates/restauration/restauration-t3/montage/contrat-candidat.json")
                ?: error("T3 runtime candidate contract missing")
            val contract = zip.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
            val plan = ContratCandidatParser.parse(contract)
            assertEquals(17, plan.transitions.size)
            assertEquals(4, plan.textOverlay!!.motionGroups.size)

            for ((index, spec) in plan.transitions.withIndex()) {
                assertTrue("Source parameters must remain available", spec.sourceParametersJson.isNotBlank())
                val last = spec.startFrame + spec.durationFrames - 1
                val frames = listOf(spec.startFrame, spec.startFrame + (spec.durationFrames - 1) / 2, last).distinct()
                val samples = JSONArray()
                for (frame in frames) {
                    val state = TransitionGeometry.evaluate(spec, frame)
                    assertNotNull("Transition event ${index + 1} frame $frame was not evaluated", state)
                    state!!
                    val grid = JSONArray()
                    for (point in listOf(0.1f, 0.5f, 0.9f)) {
                        grid.put(JSONObject().apply {
                            put("x", point.toDouble()); put("y", point.toDouble())
                            put("incoming_alpha", TransitionGeometry.sampleIncomingAlpha(state, point, point).toDouble())
                        })
                    }
                    samples.put(JSONObject().apply {
                        put("frame", frame)
                        put("progress", state.progressQ.toDouble())
                        put("rects", JSONArray().apply {
                            state.rects.forEach { r -> put(JSONArray(listOf(r.x, r.y, r.w, r.h))) }
                        })
                        put("diagonal_top_x", state.diagonalTopX.toDouble())
                        put("diagonal_bottom_x", state.diagonalBottomX.toDouble())
                        put("alpha_incoming", state.alphaIncoming.toDouble())
                        put("direction", state.direction)
                        put("slide_whip", state.slideWhip)
                        put("slide_progress", state.slideProgress.toDouble())
                        put("blur_radius", state.blurRadius.toDouble())
                        put("sample_points", grid)
                    })
                }
                traceTransitions.put(JSONObject().apply {
                    put("event_index", index + 1)
                    put("family", spec.sourceFamily)
                    put("variant", spec.sourceVariant)
                    put("profile", spec.sourceProfile)
                    put("runtime_type", spec.type.name)
                    put("frame_window_inclusive", JSONArray(listOf(spec.startFrame, last)))
                    put("source_parameters_sha256", sha(spec.sourceParametersJson.toByteArray()))
                    put("source_parameters_consumed_by_transition_geometry", false)
                    put("samples", samples)
                })
            }

            for (group in plan.textOverlay!!.motionGroups) {
                val middle = group.startFrame + (group.revealEndFrame - group.startFrame) / 2 - 1
                val frames = listOf(group.startFrame, middle, group.revealEndFrame - 1, group.endFrameExcluded - 1).distinct()
                val samples = JSONArray()
                for (frame in frames) {
                    val value = TextMotionEvaluator.evaluate(group, frame, plan.width, plan.totalFrames)
                    assertNotNull("Text group ${group.id} frame $frame was not evaluated", value)
                    value!!
                    samples.put(JSONObject().apply {
                        put("frame", frame)
                        put("progress", value.progress.toDouble())
                        put("opacity", value.opacity.toDouble())
                        put("reveal_width_px", value.revealWidthPx.toDouble())
                        put("rise_offset_px", value.riseOffsetPx.toDouble())
                        put("price_settle_offset_px", value.priceSettleOffsetPx.toDouble())
                    })
                }
                traceGroups.put(JSONObject().apply {
                    put("id", group.id)
                    put("motion", group.motionName)
                    put("window_exclusive", JSONArray(listOf(group.startFrame, group.endFrameExcluded)))
                    put("reveal_end_frame", group.revealEndFrame)
                    put("rise_px", group.risePx)
                    put("price_rise_px", group.priceRisePx)
                    put("samples", samples)
                })
            }
        }
        assertEquals(17, traceTransitions.length())
        assertEquals(4, traceGroups.length())
        val dir = File("build/outputs/render-validation").apply { mkdirs() }
        val report = JSONObject().apply {
            put("template_id", "RESTAURATION-T3")
            put("execution", "Host JVM; production Kotlin TransitionGeometry and TextMotionEvaluator; one transition event per trace entry")
            put("android_gl_or_mediacodec_pixels_rendered", false)
            put("source_parameters_consumed_by_transition_geometry", false)
            put("transition_events_evaluated", traceTransitions.length())
            put("text_motion_groups_evaluated", traceGroups.length())
            put("transitions", traceTransitions)
            put("text_motion_groups", traceGroups)
            put("scope", "This is real evaluator execution evidence, not a Media3/GL pixel proof. Per-profile source-parameter omissions remain visible and are not called a fidelity pass.")
        }
        val file = File(dir, "T3_ANDROID_EVALUATOR_TRACE.json")
        file.writeText(report.toString(2) + "\n")
        File(dir, "SHA256SUMS.txt").writeText("${sha(file)}  ${file.name}\n")
        assertTrue(file.isFile && file.length() > 0)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String = sha(file.readBytes())
}
