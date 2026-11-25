package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.AudioDuckingEngine
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import com.vitrinevideo.engine.interpreter.EffectCurveEvaluator
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin

/** T2 mix fidelity plus T1/T3/T4 non-regression after adding T2 adapters. */
class T2AudioAndNonRegressionTest {

    @Test
    fun linearDbEnvelopeDucksGraduallyAndRecoversWithoutPumping() {
        val mix = t2Mix()
        val engine = AudioDuckingEngine(48_000, 2, mix)
        val samples = ArrayList<Float>()
        val frames = 480
        val cap = ShortArray(frames * 2)
        val mus = ShortArray(frames * 2)
        val out = ShortArray(frames * 2)
        var frameNo = 0
        repeat((9 * 48_000 / frames)) { hop ->
            val speech = frameNo in (3 * 48_000) until (6 * 48_000)
            for (f in 0 until frames) {
                val t = (frameNo + f) / 48_000.0
                val m = (sin(2.0 * PI * 220.0 * t) * 0.34 * 32767).toInt().toShort()
                val v = if (speech) (sin(2.0 * PI * 300.0 * t) * 0.42 * 32767).toInt().toShort() else 0
                mus[f * 2] = m; mus[f * 2 + 1] = m
                cap[f * 2] = v; cap[f * 2 + 1] = v
            }
            engine.processInterleavedPcm16(cap, mus, frames, out)
            for (i in out.indices step 2) samples.add(out[i] / 32768f)
            frameNo += frames
        }
        fun rms(fromSec: Double, toSec: Double): Double {
            val a = (fromSec * 48_000).toInt()
            val b = minOf((toSec * 48_000).toInt(), samples.size)
            var s = 0.0
            for (i in a until b) s += samples[i] * samples[i]
            return kotlin.math.sqrt(s / maxOf(1, b - a))
        }
        val before = rms(1.6, 2.4)
        val under = rms(4.0, 5.0)
        val after = rms(6.05, 6.20)
        val half = rms(6.50, 6.70)
        val full = rms(7.50, 7.90)
        assertTrue("ducking must reduce bed: $before -> $under", under < before * 0.85)
        assertTrue("recovery must be gradual: $after -> $half -> $full", after < half && half < full)
        assertTrue("bed never collapses: ${engine.minAppliedMusicGain}", engine.minAppliedMusicGain > 0.25f)
        var maxDelta = 0f
        for (i in 1 until samples.size) maxDelta = maxOf(maxDelta, abs(samples[i] - samples[i - 1]))
        assertTrue("no sample discontinuity: $maxDelta", maxDelta < 0.25f)
        assertTrue("peak never exceeds declared ceiling: ${engine.maxPeakLinear}",
            engine.maxPeakLinear <= AudioDuckingEngine.dbToLinear(-1.99).toFloat())
    }

    @Test
    fun declaredEffectCurvesAreFrameExact() {
        val keys = listOf(EffectKeyframe(100, 0.0), EffectKeyframe(108, 0.36), EffectKeyframe(123, 0.0))
        assertEquals(0.18, EffectCurveEvaluator.valueAt(keys, 104), 0.0001)
        assertEquals(0.0, EffectCurveEvaluator.valueAt(keys, 123), 0.0001)
        assertEquals(0.5, EffectCurveEvaluator.visibilityAt(112, 108, 116, 8), 0.0001)
        assertEquals(1.035, EffectCurveEvaluator.rampAt(1.0 to 1.07, 42, 0, 42), 0.0001)
        assertEquals(0.57, EffectCurveEvaluator.viewportCxAt(
            listOf(com.vitrinevideo.engine.interpreter.ViewportKeyframe(0.0, 0.34),
                com.vitrinevideo.engine.interpreter.ViewportKeyframe(0.6, 0.48),
                com.vitrinevideo.engine.interpreter.ViewportKeyframe(1.0, 0.57)), 1.0), 0.0001)
    }

    @Test
    fun t1T3T4PlansAndMixConfigsAreUnchangedByT2Integration() {
        val expected = mapOf(
            "COMMERCE-T1-2.0.0.zip" to (900 to 4),
            "COMMERCE-T3-1.0.0.zip" to (900 to 4),
            "COMMERCE-T4-1.0.0.zip" to (900 to 4)
        )
        for ((zip, expectedFramesScenes) in expected) {
            val pkg = TemplatePackageLoader.importZip(
                TestAssetLocator.packageZip(zip),
                kotlin.io.path.createTempDirectory("reg_${zip.substringBefore('-')}").toFile()
            )
            assertEquals(expectedFramesScenes.first, pkg.renderPlan.totalFrames)
            assertEquals(expectedFramesScenes.second, pkg.renderPlan.scenes.size)
            assertTrue(pkg.renderPlan.visualEffects.isEmpty())
            assertTrue(pkg.renderPlan.unknownCapabilities.isEmpty())
            val mix = pkg.audioProfile!!.mix
            assertEquals(null, mix.duckDepthDb)
            assertEquals("", mix.attackCurve)
            assertEquals("", mix.releaseCurve)
            assertEquals(0.0, mix.voiceGainDb, 0.0001)
            assertEquals(0, pkg.renderPlan.visualEffects.size)
        }
        val t4 = TemplatePackageLoader.importZip(
            TestAssetLocator.packageZip("COMMERCE-T4-1.0.0.zip"),
            kotlin.io.path.createTempDirectory("reg_t4b").toFile()
        )
        assertEquals(8.0, t4.audioProfile!!.mix.minimumVoiceMusicGapDb!!, 0.0001)
        assertEquals(-11.0, AudioDuckingEngine(48_000, 2, t4.audioProfile.mix).effectiveDuckGainDb, 0.0001)
    }

    private fun t2Mix(): AudioMixConfig {
        val bytes = FileInputStream(TestAssetLocator.packageZip("COMMERCE-T2-1.0.0.zip")).use { it.readBytes() }
        var voices: String? = null
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == "audio/voix.json") { voices = zis.readBytes().toString(Charsets.UTF_8); break }
            }
        }
        val mix = JSONObject(voices ?: error("voix.json missing")).getJSONObject("mix")
        return AudioMixConfig(
            profileId = "afrobeat_arrivage_commerce",
            includeCaptureVoice = true,
            targetLufs = mix.getDouble("master_target_lufs"),
            bgGainDb = mix.getDouble("music_open_gain_db"),
            underSpeechGainDb = mix.getDouble("music_under_voice_gain_db"),
            attackMs = mix.getInt("duck_attack_ms"),
            releaseMs = mix.getInt("duck_release_ms"),
            attackCurve = mix.getString("duck_attack_curve"),
            releaseCurve = mix.getString("duck_release_curve"),
            voiceGainDb = mix.getDouble("voice_gain_db"),
            peakLimitDb = mix.getDouble("master_true_peak_dbtp_max"),
            duckDepthDb = mix.getDouble("duck_depth_db")
        )
    }
}
