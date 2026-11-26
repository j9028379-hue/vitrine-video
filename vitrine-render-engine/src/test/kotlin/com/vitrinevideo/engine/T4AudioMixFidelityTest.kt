package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.AudioDuckingEngine
import com.vitrinevideo.engine.audio.StreamingStereoResampler
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Targeted mixer tests for the T4 voice-under-music defect reported by the founder. */
class T4AudioMixFidelityTest {

    @Test
    fun t4DeclaredGapIsEnforcedBySmoothedDuckingNotPerHopGainJumps() {
        val mix = t4Mix()
        assertEquals(8.0, mix.minimumVoiceMusicGapDb!!, 0.0001)
        val engine = AudioDuckingEngine(48_000, 2, mix)
        assertEquals(-11.0, engine.effectiveDuckGainDb, 0.0001)

        val cfg = AudioMixConfig(
            profileId = "t4-smooth-duck",
            includeCaptureVoice = true,
            targetLufs = mix.targetLufs,
            bgGainDb = mix.bgGainDb,
            underSpeechGainDb = mix.underSpeechGainDb,
            attackMs = mix.attackMs,
            releaseMs = mix.releaseMs,
            peakLimitDb = mix.peakLimitDb,
            minimumVoiceMusicGapDb = mix.minimumVoiceMusicGapDb
        )
        val smooth = AudioDuckingEngine(48_000, 2, cfg)
        runSpeechScenario(smooth, speechFromSec = 3.0, speechToSec = 6.0, totalSec = 9.0)

        val quietGain = AudioDuckingEngine.dbToLinear(cfg.bgGainDb).toFloat()
        val duckedGain = AudioDuckingEngine.dbToLinear(-11.0).toFloat()
        assertTrue("Quiet bed gain ${smooth.maxAppliedMusicGain} must reach ${quietGain * 0.95}",
            smooth.maxAppliedMusicGain >= quietGain * 0.95f)
        assertTrue("Under-speech gain ${smooth.minAppliedMusicGain} must fall to ${duckedGain * 1.2}",
            smooth.minAppliedMusicGain <= duckedGain * 1.2f)
        assertTrue("Ducking must not collapse music to zero", smooth.minAppliedMusicGain > 0.2f)
        assertTrue(smooth.activeDuckingHops > 100)
    }

    @Test
    fun musicRecoversGraduallyAfterSpeechAndSignalStaysContinuous() {
        val engine = AudioDuckingEngine(48_000, 2, t4Mix())
        val samples = ArrayList<Float>()
        runSpeechScenario(engine, 3.0, 6.0, 9.0) { frameIndex, value ->
            samples.add(value)
        }

        fun rms(fromSec: Double, toSec: Double): Double {
            val from = (fromSec * 48_000).toInt()
            val to = minOf((toSec * 48_000).toInt(), samples.size)
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            return sqrt(sum / maxOf(1, to - from))
        }

        val justAfter = rms(6.05, 6.20)
        val halfSecond = rms(6.50, 6.70)
        val fullyReleased = rms(7.20, 7.50)
        assertTrue("Recovery must be gradual: $justAfter -> $halfSecond", justAfter < halfSecond)
        assertTrue("Recovery must complete: $halfSecond -> $fullyReleased", halfSecond < fullyReleased)

        var maxDelta = 0f
        for (i in 1 until samples.size) maxDelta = maxOf(maxDelta, abs(samples[i] - samples[i - 1]))
        assertTrue("No sample discontinuity/scratch, maxDelta=$maxDelta", maxDelta < 0.25f)
    }

    @Test
    fun softLimiterAvoidsFlatToppingAndNeverExceedsCeiling() {
        val cfg = t4Mix().copy(underSpeechGainDb = -3.0)
        val engine = AudioDuckingEngine(48_000, 2, cfg)
        val chunkFrames = 480
        val cap = ShortArray(chunkFrames * 2)
        val mus = ShortArray(chunkFrames * 2) { 29000 }
        val out = ShortArray(chunkFrames * 2)
        for (hop in 0 until 200) {
            for (f in 0 until chunkFrames) {
                val s = (sin(2.0 * PI * 300.0 * (hop * chunkFrames + f) / 48_000.0) * 30000).toInt().toShort()
                cap[f * 2] = s; cap[f * 2 + 1] = s
            }
            engine.processInterleavedPcm16(cap, mus, chunkFrames, out)
        }
        assertTrue("Peak ${engine.maxPeakLinear} must stay under -2.6 dBTP",
            engine.maxPeakLinear <= AudioDuckingEngine.dbToLinear(-2.59).toFloat())

        var maxDelta = 0
        for (i in 1 until out.size) maxDelta = maxOf(maxDelta, abs(out[i] - out[i - 1]))
        assertTrue("Limiter must stay continuous, maxDelta=$maxDelta", maxDelta < 4000)
    }

    @Test
    fun sameRateMonoDecodeExpandsToStereoWithoutLosingSamples() {
        val resampler = StreamingStereoResampler(48_000, 48_000)
        val mono = shortArrayOf(-12_000, -1, 0, 1, 12_000)

        val stereo = resampler.resampleStereo(mono, sourceChannels = 1)

        assertArrayEquals(shortArrayOf(-12_000, -12_000, -1, -1, 0, 0, 1, 1, 12_000, 12_000), stereo)
        assertEquals(5L, resampler.outputFrameCount)
    }

    @Test
    fun chunkedMp3ResamplingKeepsContinuousPhaseAndFrameCount() {
        val resampler = StreamingStereoResampler(44_100, 48_000)
        val chunkFrames = 1152 // decoded MP3 frame size
        val total = ArrayList<Short>()
        repeat(25) { chunk ->
            val src = ShortArray(chunkFrames * 2)
            for (f in 0 until chunkFrames) {
                val t = (chunk * chunkFrames + f) / 44_100.0
                val v = (sin(2.0 * PI * 220.0 * t) * 16000).toInt().toShort()
                src[f * 2] = v; src[f * 2 + 1] = v
            }
            val pcm = resampler.resampleStereo(src, 2)
            for (s in pcm) total.add(s)
        }
        val expected = (25L * chunkFrames * 48_000 / 44_100).toInt()
        assertTrue("Frame count ${resampler.outputFrameCount} vs $expected", abs(total.size / 2 - expected) <= 2)

        var maxDelta = 0
        for (i in 2 until total.size step 2) maxDelta = maxOf(maxDelta, abs(total[i] - total[i - 2]))
        assertTrue("Resampler must not click at MP3 chunk boundaries: $maxDelta", maxDelta < 1200)
    }

    @Test
    fun t1AndT3MixConfigsRemainUnchanged() {
        for (name in listOf("COMMERCE-T1-2.0.0.zip", "COMMERCE-T3-1.0.0.zip")) {
            val profilePath = if (name.startsWith("COMMERCE-T1")) {
                "templates/commerce/commerce-t1/audio/profil.json"
            } else "audio/profil.json"
            val bytes = FileInputStream(TestAssetLocator.packageZip(name)).use { it.readBytes() }
            val zip = java.util.zip.ZipInputStream(bytes.inputStream())
            var raw: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == profilePath) {
                    raw = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
            val profile = UniversalJsonInterpreter.parseAudioProfileJson(raw ?: error("$profilePath missing"))
            val mix = profile.mix
            assertEquals(null, mix.minimumVoiceMusicGapDb)
            val engine = AudioDuckingEngine(48_000, 2, mix)
            assertEquals(mix.underSpeechGainDb, engine.effectiveDuckGainDb, 0.0001)
        }
    }

    private fun t4Mix(): com.vitrinevideo.engine.interpreter.AudioMixConfig {
        val bytes = FileInputStream(TestAssetLocator.packageZip("COMMERCE-T4-1.0.0.zip")).use { it.readBytes() }
        val zip = java.util.zip.ZipInputStream(bytes.inputStream())
        var raw: String? = null
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == "audio/profil.json") {
                raw = zip.readBytes().toString(Charsets.UTF_8)
                break
            }
        }
        return UniversalJsonInterpreter.parseAudioProfileJson(raw ?: error("T4 profile missing")).mix
    }

    private fun runSpeechScenario(
        engine: AudioDuckingEngine,
        speechFromSec: Double,
        speechToSec: Double,
        totalSec: Double,
        onSample: ((Int, Float) -> Unit)? = null
    ) {
        val frames = 480
        val cap = ShortArray(frames * 2)
        val mus = ShortArray(frames * 2)
        val out = ShortArray(frames * 2)
        var globalFrame = 0
        repeat((totalSec * 48_000 / frames).toInt()) { hop ->
            val speech = globalFrame >= speechFromSec * 48_000 && globalFrame < speechToSec * 48_000
            for (f in 0 until frames) {
                val t = (globalFrame + f) / 48_000.0
                val m = (sin(2.0 * PI * 220.0 * t) * 0.35 * 32767).toInt().toShort()
                val v = if (speech) (sin(2.0 * PI * 440.0 * t) * 0.45 * 32767).toInt().toShort() else 0
                mus[f * 2] = m; mus[f * 2 + 1] = m
                cap[f * 2] = v; cap[f * 2 + 1] = v
            }
            engine.processInterleavedPcm16(cap, mus, frames, out)
            for (i in 0 until out.size step 2) {
                onSample?.invoke(globalFrame + i / 2, out[i] / 32768f)
            }
            globalFrame += frames
        }
    }
}
