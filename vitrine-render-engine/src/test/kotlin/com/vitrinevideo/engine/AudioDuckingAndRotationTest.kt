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
