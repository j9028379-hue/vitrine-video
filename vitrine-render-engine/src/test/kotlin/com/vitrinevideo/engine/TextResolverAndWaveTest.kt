package com.vitrinevideo.engine

import com.vitrinevideo.engine.graphics.TextFieldResolver
import com.vitrinevideo.engine.graphics.WaveCurveEvaluator
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TextResolverAndWaveTest {

    @Test
    fun resolvesGoldenJobExactlyAndOmitsEmptyPrice() {
        val t3Part = File("/tmp/inspect/t3/montage/partition.json")
        val goldenJobFile = File("/tmp/inspect/t3/golden/job.json")
        val goldenTitlesFile = File("/tmp/inspect/t3/golden/titres.json")
        if (!t3Part.exists() || !goldenJobFile.exists() || !goldenTitlesFile.exists()) return

        val plan = UniversalJsonInterpreter.parsePartition(t3Part)
        val spec = plan.textOverlay!!
        val fullResp = jsonMap(JSONObject(goldenJobFile.readText()).getJSONObject("reponses"))
        val expectedTitles = JSONObject(goldenTitlesFile.readText())
        val fakeMeasure: (String, Float) -> Float = { s, sz -> s.length * sz * 0.52f }

        // Verify parity across ALL 6 text fields against golden/titres.json!
        for (w in spec.windows) {
            val resolved = TextFieldResolver.resolveFieldText(w, spec, fullResp)
            assertEquals("Field ${w.fieldKey}", expectedTitles.getString(w.fieldKey), resolved)
        }

        // Verify optional prix omission when empty
        val noPriceResp = fullResp.toMutableMap().apply { put("prix", "") }
        val priceWindow = spec.windows.first { it.fieldKey == "prix" }
        assertNull(TextFieldResolver.layoutWindow(priceWindow, spec, noPriceResp, fakeMeasure))

        // Verify contact window layout with WhatsApp prefix
        val contactWindow = spec.windows.first { it.fieldKey == "contact" }
        val contactLayout = TextFieldResolver.layoutWindow(contactWindow, spec, noPriceResp, fakeMeasure)
        assertNotNull(contactLayout)
        assertEquals("WhatsApp", contactLayout!!.lines.first())
    }

    @Test
    fun waveCurveMatchesT3Keyframes() {
        val t3Part = File("/tmp/inspect/t3/montage/partition.json")
        if (!t3Part.exists()) return
        val plan = UniversalJsonInterpreter.parsePartition(t3Part)
        val wave = plan.textOverlay!!.wave!!

        val beforeStart = WaveCurveEvaluator.interpolateWave(wave.keyframes, -0.5)
        assertEquals(0f, beforeStart.scale, 0.001f)

        val at5 = WaveCurveEvaluator.interpolateWave(wave.keyframes, 5.0)
        assertEquals(0.95f, at5.scale, 0.01f)

        val peak = wave.keyframes.maxBy { it.scale }
        assertTrue(peak.scale >= 1.1)

        val settled = WaveCurveEvaluator.interpolateWave(wave.keyframes, 30.0)
        assertEquals(1.0f, settled.scale, 0.001f)
        assertEquals(0f, settled.dxPx, 0.001f)
        assertEquals(0f, settled.dyPx, 0.001f)
        assertEquals(0f, settled.rotationDeg, 0.001f)
    }

    private fun jsonMap(obj: JSONObject): Map<String, String> {
        val m = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k] = obj.optString(k, "")
        }
        return m
    }
}
