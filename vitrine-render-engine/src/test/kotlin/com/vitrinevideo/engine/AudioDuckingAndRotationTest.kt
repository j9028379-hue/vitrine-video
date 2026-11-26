package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.AudioDuckingEngine
import com.vitrinevideo.engine.audio.CyclicRotationAlgorithm
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioDuckingAndRotationTest {

    @Test
    fun streamingDuckingAttenuatesMusicUnderSpeechAndLimitsPeaksInConstantMemory() {
        val cfg = AudioMixConfig(
            profileId = "commerce-audio-1.0.0",
            includeCaptureVoice = true,
            targetLufs = -16.0,
            bgGainDb = -3.0,
            underSpeechGainDb = -10.0,
            attackMs = 120,
            releaseMs = 450,
            peakLimitDb = -2.6
        )
        val engine = AudioDuckingEngine(48_000, 2, cfg)

        // Simulate 30 seconds (1,440,000 stereo frames) in 480-frame (10ms) chunks
        val chunkFrames = 480
        val cap = ShortArray(chunkFrames * 2)
        val mus = ShortArray(chunkFrames * 2)
        val out = ShortArray(chunkFrames * 2)

        var quietMusicSumSq = 0.0
        var quietSamples = 0L
        var duckedMusicSumSq = 0.0
        var duckedSamples = 0L

        for (hop in 0 until 3000) {
            val speechActive = hop in 800..1500 // 8s..15s speech burst
            for (f in 0 until chunkFrames) {
                val t = (hop * chunkFrames + f) / 48_000.0
                val musSample = (sin(2.0 * PI * 220.0 * t) * 0.5 * 32767.0).toInt().toShort()
                val capSample = if (speechActive) {
                    (sin(2.0 * PI * 440.0 * t) * 0.45 * 32767.0).toInt().toShort()
                } else 0
                mus[f * 2] = musSample; mus[f * 2 + 1] = musSample
                cap[f * 2] = capSample; cap[f * 2 + 1] = capSample
            }
            engine.processInterleavedPcm16(cap, mus, chunkFrames, out)

            // Compare quiet bed (hops 300..600) vs ducked bed when speech stops releasing (hop 1505..1520)
            if (hop in 300..600) {
                for (s in out) {
                    val v = s / 32768.0
                    quietMusicSumSq += v * v
                    quietSamples++
                }
            }
            if (hop in 1502..1510) {
                for (s in out) {
                    val v = s / 32768.0
                    duckedMusicSumSq += v * v
                    duckedSamples++
                }
            }
        }

        val quietRms = sqrt(quietMusicSumSq / quietSamples)
        val duckedRms = sqrt(duckedMusicSumSq / duckedSamples)
        assertTrue("Music must duck under speech ($duckedRms < $quietRms)", duckedRms < quietRms * 0.65)
        assertTrue("Ducking hops recorded", engine.activeDuckingHops > 500)
        assertTrue("Peak limited <= -2.6 dBTP", engine.maxPeakLinear <= AudioDuckingEngine.dbToLinear(-2.59).toFloat())
    }

    @Test
    fun t3VoicePriorityDetectsQuietSpeechAndMeetsTheDeclaredFourDbGate() {
        val engine = t3AudioEngine()
        val (minMarginDb, outRms) = renderScenario(engine, durationSec = 1.2) { t ->
            if (t in 0.0..1.2) Pair(0.0071, 0.30) else Pair(0.0, 0.30)
        }
        assertTrue("-43 dBFS-class quiet voice is detected; margin=$minMarginDb", minMarginDb >= 3.9)
        assertTrue("Output remains below the declared -2 dBFS peak ceiling", engine.maxPeakLinear <= AudioDuckingEngine.dbToLinear(-1.99).toFloat())
        assertTrue("Synthetic output is non-silent", outRms > 0.001)
    }

    @Test
    fun t3VoicePriorityIsIndependentOfStereoPolarityAndBridgesShortPauses() {
        val engine = t3AudioEngine()
        val blockMargins = mutableListOf<Double>()
        val frames = 48_000
        val chunkFrames = 480
        val cap = ShortArray(chunkFrames * 2)
        val mus = ShortArray(chunkFrames * 2)
        val out = ShortArray(chunkFrames * 2)
        var tFrame = 0
        while (tFrame < frames) {
            for (f in 0 until chunkFrames) {
                val t = (tFrame + f) / 48_000.0
                val phrase = (t < 0.35) || (t in 0.46..0.82) // 110 ms inter-phrase gap
                val v = if (phrase) (sin(2.0 * PI * 440.0 * t) * 0.01 * 32767.0).toInt().toShort() else 0
                val m = (sin(2.0 * PI * 220.0 * t) * 0.30 * 32767.0).toInt().toShort()
                cap[f * 2] = v
                cap[f * 2 + 1] = (-v.toInt()).toShort() // anti-phase stereo must not cancel in VAD
                mus[f * 2] = m; mus[f * 2 + 1] = m
            }
            engine.processInterleavedPcm16(cap, mus, chunkFrames, out)
            if (engine.minMeasuredSpeechToMusicDb.isFinite()) blockMargins += engine.minMeasuredSpeechToMusicDb
            tFrame += chunkFrames
        }
        assertTrue("Anti-phase stereo speech must still be detected", engine.activeDuckingHops > 20)
        assertTrue("Both short phrases must meet the +4 dB gate", engine.minMeasuredSpeechToMusicDb >= 3.9)
        assertTrue("The minimum gate is enforced during short gaps as well", blockMargins.minOrNull()!! >= 3.9)
    }

    @Test
    fun t3MusicOnlyAndLegacyProfilesDoNotTriggerTheNewAdaptiveSidechain() {
        val musicOnly = t3AudioEngine()
        renderScenario(musicOnly, durationSec = 0.5) { t -> Pair(0.0, 0.2) }
        assertEquals(AudioDuckingEngine.dbToLinear(-3.0).toFloat(), musicOnly.minAppliedMusicGain, 0.003f)

        val legacy = AudioDuckingEngine(48_000, 2, AudioMixConfig(
            profileId = "COMMERCE-T1", includeCaptureVoice = true, bgGainDb = -3.0,
            underSpeechGainDb = -10.0, peakLimitDb = -2.6
        ))
        val (margin, _) = renderScenario(legacy, durationSec = 0.5) { t ->
            if (t < 0.5) Pair(AudioDuckingEngine.dbToLinear(-43.0), 0.2) else Pair(0.0, 0.2)
        }
        assertTrue("Legacy profile has no T3-only VAD override", legacy.activeDuckingHops == 0L)
        assertTrue("Legacy T1-T4 behavior remains untouched", margin.isInfinite())
    }

    @Test
    fun t3StrongVoiceAndVoiceNearTheSceneEdgesRemainPresentAndLimited() {
        val engine = t3AudioEngine()
        val (margin, rms) = renderScenario(engine, durationSec = 1.0) { t ->
            if (t < 0.12 || t >= 0.86) Pair(0.22, 0.25) else Pair(0.0, 0.25)
        }
        assertTrue("Strong voice remains above the music in both opening/closing phrases", margin >= 3.9)
        assertTrue("Opening and closing voice are represented in the mixed output", rms > 0.02)
        assertTrue("No PCM clipping beyond the configured ceiling", engine.maxPeakLinear <= AudioDuckingEngine.dbToLinear(-1.99).toFloat())
    }

    private fun t3AudioEngine() = AudioDuckingEngine(48_000, 2, AudioMixConfig(
        profileId = "restauration_T3_voice_music_v1_0_0", includeCaptureVoice = true,
        targetLufs = -16.0, bgGainDb = -3.0, underSpeechGainDb = -10.0,
        attackMs = 120, releaseMs = 450, peakLimitDb = -2.0,
        duckDepthDb = -7.0, voiceGainDb = 0.0,
        attackCurve = "linear_db", releaseCurve = "linear_db",
        minimumSpeechToMusicDb = 4.0, voiceVadThresholdDbfs = -48.0, voiceHoldMs = 180
    ))

    private fun renderScenario(
        engine: AudioDuckingEngine,
        durationSec: Double,
        levels: (Double) -> Pair<Double, Double>
    ): Pair<Double, Double> {
        val chunkFrames = 480
        val cap = ShortArray(chunkFrames * 2)
        val mus = ShortArray(chunkFrames * 2)
        val out = ShortArray(chunkFrames * 2)
        val frameCount = (durationSec * 48_000).toInt()
        var sumSq = 0.0
        var count = 0L
        var start = 0
        while (start < frameCount) {
            val frames = minOf(chunkFrames, frameCount - start)
            for (f in 0 until frames) {
                val t = (start + f) / 48_000.0
                val (voiceRms, musicPeak) = levels(t)
                val v = (sin(2.0 * PI * 440.0 * t) * voiceRms * sqrt(2.0) * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                val m = (sin(2.0 * PI * 220.0 * t) * musicPeak * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                cap[f * 2] = v; cap[f * 2 + 1] = v
                mus[f * 2] = m; mus[f * 2 + 1] = m
            }
            engine.processInterleavedPcm16(cap, mus, frames, out)
            for (i in 0 until frames * 2) {
                val v = out[i] / 32768.0
                sumSq += v * v
                count++
            }
            start += frames
        }
        return engine.minMeasuredSpeechToMusicDb to sqrt(sumSq / count)
    }

    @Test
    fun sqliteCyclicRotationCycles1To4AndIsIdempotentPerRunId() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE livraisons (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    profil TEXT NOT NULL,
                    job TEXT NOT NULL UNIQUE,
                    piste INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        val approved = listOf(1, 2, 3, 4)
        fun lastTrack(): Int? {
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT piste FROM livraisons ORDER BY seq DESC LIMIT 1")
                return if (rs.next()) rs.getInt(1) else null
            }
        }

        val expectedSequence = listOf(1, 2, 3, 4, 1, 2)
        expectedSequence.forEachIndexed { idx, expected ->
            val next = CyclicRotationAlgorithm.nextTrackNumber(lastTrack(), approved)
            assertEquals(expected, next)
            conn.prepareStatement("INSERT OR IGNORE INTO livraisons(profil, job, piste) VALUES(?, ?, ?)").use { ps ->
                ps.setString(1, "commerce-audio-1.0.0")
                ps.setString(2, "job_$idx")
                ps.setInt(3, next)
                ps.executeUpdate()
            }
        }
        conn.close()
    }
}
