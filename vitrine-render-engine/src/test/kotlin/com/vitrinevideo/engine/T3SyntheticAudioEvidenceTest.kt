package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.AudioDuckingEngine
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runs the production AudioDuckingEngine on deterministic, speech-like synthetic PCM.
 * The spectral margin is an intelligibility proxy only; it does not test spoken language.
 */
class T3SyntheticAudioEvidenceTest {
    private val sampleRate = 48_000
    private val chunkFrames = 480 // production 10 ms VAD/ducking hop

    @Test
    fun writesAuditableSyntheticVoicePriorityDuckPauseAndPeakEvidence() {
        val cfg = AudioMixConfig(
            profileId = "restauration_T3_voice_music_v1_0_0",
            includeCaptureVoice = true,
            targetLufs = -16.0,
            bgGainDb = -3.0,
            underSpeechGainDb = -10.0,
            attackMs = 120,
            releaseMs = 450,
            peakLimitDb = -2.0,
            duckDepthDb = -7.0,
            voiceGainDb = 0.0,
            attackCurve = "linear_db",
            releaseCurve = "linear_db",
            minimumSpeechToMusicDb = 4.0,
            voiceVadThresholdDbfs = -48.0,
            voiceHoldMs = 180
        )
        val engine = AudioDuckingEngine(sampleRate, 2, cfg)
        val durationSeconds = 4.0
        val totalFrames = (durationSeconds * sampleRate).toInt()
        val output = ShortArray(totalFrames * 2)
        val voice = ShortArray(chunkFrames * 2)
        val music = ShortArray(chunkFrames * 2)
        val mixed = ShortArray(chunkFrames * 2)
        var base = 0
        while (base < totalFrames) {
            val frames = minOf(chunkFrames, totalFrames - base)
            for (i in 0 until frames) {
                val t = (base + i) / sampleRate.toDouble()
                val voiced = (t < 0.90) || (t in 1.02..1.82) || (t in 2.55..3.35)
                // Modulated harmonic surrogate: deterministic, nonlinguistic voice-band signal.
                val syllable = 0.58 + 0.42 * (0.5 + 0.5 * sin(2.0 * PI * 3.2 * t))
                val voiceWave = if (voiced) {
                    (0.0095 * syllable * sin(2.0 * PI * 440.0 * t) +
                        0.0030 * syllable * sin(2.0 * PI * 880.0 * t))
                } else 0.0
                val musicWave = 0.30 * sin(2.0 * PI * 220.0 * t)
                val v = (voiceWave * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                val m = (musicWave * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                voice[i * 2] = v; voice[i * 2 + 1] = v
                music[i * 2] = m; music[i * 2 + 1] = m
            }
            engine.processInterleavedPcm16(voice, music, frames, mixed)
            System.arraycopy(mixed, 0, output, base * 2, frames * 2)
            base += frames
        }

        fun toneAmplitude(hz: Double, start: Double, end: Double): Double {
            val first = (start * sampleRate).toInt().coerceAtLeast(0)
            val last = (end * sampleRate).toInt().coerceAtMost(totalFrames)
            var re = 0.0; var im = 0.0
            for (frame in first until last) {
                val x = output[frame * 2] / 32768.0
                val phase = 2.0 * PI * hz * frame / sampleRate
                re += x * cos(phase); im += x * sin(phase)
            }
            return if (last <= first) 0.0 else 2.0 * sqrt(re * re + im * im) / (last - first)
        }
        fun db(x: Double) = 20.0 * log10(x.coerceAtLeast(1e-9))
        fun margin(start: Double, end: Double) = db(toneAmplitude(440.0, start, end) / toneAmplitude(220.0, start, end))

        val baselineMusic = toneAmplitude(220.0, 0.20, 0.70)
        val phraseMusic = toneAmplitude(220.0, 0.30, 0.80)
        val phraseMargin = margin(0.30, 0.80)
        val shortPauseMusic = toneAmplitude(220.0, 0.94, 1.00)
        val shortPauseDuckRatio = shortPauseMusic / baselineMusic
        val latePauseMusic = toneAmplitude(220.0, 1.90, 2.00)
        val recoveredPauseMusic = toneAmplitude(220.0, 2.35, 2.50)
        val voicePeak = toneAmplitude(440.0, 0.30, 0.80)
        val maxPeakDbfs = 20.0 * log10(engine.maxPeakLinear.toDouble().coerceAtLeast(1e-9))

        assertTrue("T3 sidechain detects synthetic voice", engine.activeDuckingHops > 250)
        assertTrue("T3 voice/music gate is at least +4 dB; measured=$phraseMargin", phraseMargin >= 3.8)
        assertTrue("Music is attenuated during voice; ratio=$phraseMusic/$baselineMusic", phraseMusic < baselineMusic * 0.45)
        assertTrue("The 180 ms hold keeps a short pause ducked; ratio=$shortPauseDuckRatio", shortPauseDuckRatio < 0.7)
        assertTrue("Music recovers after the 450 ms release", recoveredPauseMusic > latePauseMusic * 1.15)
        assertTrue("Synthetic voice-band signal survives the mix", voicePeak > 0.001)
        assertTrue("Peak ceiling remains at or below -2 dBFS; measured=$maxPeakDbfs", maxPeakDbfs <= -1.95)

        val dir = File("build/outputs/audio-validation").apply { mkdirs() }
        val wav = File(dir, "T3_SYNTHETIC_VOICE_PRIORITY.wav")
        writeWav16Stereo(wav, output, sampleRate)
        val report = JSONObject().apply {
            put("template_id", "RESTAURATION-T3")
            put("engine", "AudioDuckingEngine production class; 48 kHz stereo PCM16; 10 ms blocks")
            put("fixture", "deterministic modulated harmonic voice-band surrogate + continuous 220 Hz music tone; not speech and not founder voice")
            put("profile", JSONObject().apply {
                put("voice_vad_threshold_dbfs", cfg.voiceVadThresholdDbfs)
                put("voice_hold_ms", cfg.voiceHoldMs)
                put("attack_ms", cfg.attackMs); put("release_ms", cfg.releaseMs)
                put("bg_gain_db", cfg.bgGainDb); put("under_speech_gain_db", cfg.underSpeechGainDb)
                put("minimum_speech_to_music_db", cfg.minimumSpeechToMusicDb)
                put("true_peak_limit_dbfs", cfg.peakLimitDb)
            })
            put("voice_windows_s", listOf(listOf(0.0, 0.90), listOf(1.02, 1.82), listOf(2.55, 3.35)))
            put("pause_windows_s", listOf(listOf(0.90, 1.02), listOf(1.82, 2.55), listOf(3.35, 4.0)))
            put("measured", JSONObject().apply {
                put("active_ducking_hops", engine.activeDuckingHops)
                put("min_speech_music_margin_db_over_voiced_hops", engine.minMeasuredSpeechToMusicDb)
                put("phrase_voice_music_tone_margin_db", phraseMargin)
                put("baseline_music_tone_amplitude", baselineMusic)
                put("phrase_music_tone_amplitude", phraseMusic)
                put("phrase_music_to_baseline_ratio", phraseMusic / baselineMusic)
                put("short_pause_after_voice_music_ratio", shortPauseDuckRatio)
                put("late_pause_music_tone_amplitude", latePauseMusic)
                put("post_release_music_tone_amplitude", recoveredPauseMusic)
                put("voice_440hz_tone_amplitude", voicePeak)
                put("max_peak_dbfs", maxPeakDbfs)
                put("intelligibility_scope", "spectral priority proxy only; no phoneme/language intelligibility conclusion")
            })
            put("audio_file", JSONObject().apply {
                put("path", wav.name); put("bytes", wav.length()); put("sha256", sha256(wav))
            })
            put("status", "PASS_SYNTHETIC_DSP_ONLY")
            put("not_tested", listOf("human speech intelligibility", "founder voice", "phone speaker acoustics", "AAC end-to-end render"))
        }
        File(dir, "T3_SYNTHETIC_AUDIO_REPORT.json").writeText(report.toString(2) + "\n")
        val checksum = "${sha256(wav)}  ${wav.name}\n${sha256(File(dir, "T3_SYNTHETIC_AUDIO_REPORT.json"))}  T3_SYNTHETIC_AUDIO_REPORT.json\n"
        File(dir, "SHA256SUMS.txt").writeText(checksum)
        assertTrue("WAV and metrics report are materialized for CI artifacts", wav.isFile && wav.length() > 44 && File(dir, "T3_SYNTHETIC_AUDIO_REPORT.json").length() > 0)
    }

    private fun writeWav16Stereo(path: File, samples: ShortArray, sampleRateHz: Int) {
        val pcm = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) buffer.putShort(sample)
        val bytes = ByteArrayOutputStream().use { bos ->
            DataOutputStream(bos).use { out ->
                out.writeBytes("RIFF"); writeLe32(out, 36 + pcm.size); out.writeBytes("WAVE")
                out.writeBytes("fmt "); writeLe32(out, 16); writeLe16(out, 1); writeLe16(out, 2)
                writeLe32(out, sampleRateHz); writeLe32(out, sampleRateHz * 4); writeLe16(out, 4); writeLe16(out, 16)
                out.writeBytes("data"); writeLe32(out, pcm.size); out.write(pcm)
            }
            bos.toByteArray()
        }
        path.writeBytes(bytes)
    }

    private fun writeLe16(out: DataOutputStream, n: Int) { out.writeByte(n and 0xff); out.writeByte((n shr 8) and 0xff) }
    private fun writeLe32(out: DataOutputStream, n: Int) {
        out.writeByte(n and 0xff); out.writeByte((n shr 8) and 0xff)
        out.writeByte((n shr 16) and 0xff); out.writeByte((n shr 24) and 0xff)
    }
    private fun sha256(path: File): String = MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) }
}
