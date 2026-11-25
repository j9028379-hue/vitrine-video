package com.vitrinevideo.engine.audio

import com.vitrinevideo.engine.interpreter.AudioMixConfig
import kotlin.math.*

/**
 * Streaming PCM audio mixer + ducking DSP (48 kHz, 16-bit stereo).
 * Constant O(1) memory footprint: processes arbitrary duration via small chunks.
 */
class AudioDuckingEngine(
    private val sampleRateHz: Int = 48_000,
    private val channels: Int = 2,
    private val config: AudioMixConfig
) {
    private val bgLinear = dbToLinear(config.bgGainDb).toFloat()
    private val duckLinear = dbToLinear(config.underSpeechGainDb).toFloat()
    private val peakLimitLinear = dbToLinear(config.peakLimitDb).toFloat()

    // Pre-gain to normalize typical -14..-16 LUFS pop/afrobeat beds toward targetLufs
    private val musicBaseScale: Float = run {
        val targetOffset = (config.targetLufs - (-16.0)) * 0.5
        dbToLinear(targetOffset).toFloat()
    }

    private val voiceVadThreshold = dbToLinear(-38.0) // RMS threshold for speech presence
    private val hopSamples = max(1, sampleRateHz / 100) // 10 ms = 480 frames
    private val attackCoeff = exp(-1.0 / max(1.0, (config.attackMs / 10.0)))
    private val releaseCoeff = exp(-1.0 / max(1.0, (config.releaseMs / 10.0)))

    private var duckEnvelope = 0.0 // 0.0 = no speech (bgLinear), 1.0 = speech active (duckLinear)
    private var lastVoiceHoldHops = 0

    // Telemetry for verification/tests
    var maxPeakLinear: Float = 0f
        private set
    var totalDuckingHops: Long = 0L
        private set
    var activeDuckingHops: Long = 0L
        private set

    fun processInterleavedPcm16(
        capturePcm: ShortArray,
        musicPcm: ShortArray,
        sampleCountFrames: Int,
        outPcm: ShortArray
    ) {
        var idx = 0
        while (idx < sampleCountFrames) {
            val block = min(hopSamples, sampleCountFrames - idx)
            var sumSq = 0.0
            var musicSumSq = 0.0
            for (f in 0 until block) {
                val base = (idx + f) * channels
                val l = capturePcm[base] / 32768.0
                val r = if (channels > 1) capturePcm[base + 1] / 32768.0 else l
                val mono = 0.5 * (l + r)
                sumSq += mono * mono
                val ml = musicPcm[base] / 32768.0
                val mr = if (channels > 1) musicPcm[base + 1] / 32768.0 else ml
                val musicMono = 0.5 * (ml + mr)
                musicSumSq += musicMono * musicMono
            }
            val rms = sqrt(sumSq / block)
            val musicRms = sqrt(musicSumSq / block)
            val speechDetected = config.includeCaptureVoice && rms >= voiceVadThreshold
            if (speechDetected) {
                lastVoiceHoldHops = 8 // 80 ms hold
            } else if (lastVoiceHoldHops > 0) {
                lastVoiceHoldHops--
            }
            val targetDuck = if (speechDetected || lastVoiceHoldHops > 0) 1.0 else 0.0
            val coeff = if (targetDuck > duckEnvelope) attackCoeff else releaseCoeff
            duckEnvelope = targetDuck + coeff * (duckEnvelope - targetDuck)

            totalDuckingHops++
            if (duckEnvelope > 0.25) activeDuckingHops++

            var musicGain = (bgLinear + (duckLinear - bgLinear) * duckEnvelope.toFloat()) * musicBaseScale
            val requiredGap = config.minimumVoiceMusicGapDb
            if (requiredGap != null && config.includeCaptureVoice && rms > voiceVadThreshold && musicRms > 1e-9) {
                val maxMusicGain = (rms / (musicRms * 10.0.pow(requiredGap / 20.0))).toFloat()
                musicGain = min(musicGain, maxMusicGain.coerceAtLeast(0f))
            }
            for (f in 0 until block) {
                val base = (idx + f) * channels
                for (c in 0 until channels) {
                    val pos = base + c
                    val cap = if (config.includeCaptureVoice) capturePcm[pos] / 32768.0f else 0f
                    val mus = (musicPcm[pos] / 32768.0f) * musicGain
                    var mixed = cap + mus
                    val absVal = abs(mixed)
                    if (absVal > peakLimitLinear) {
                        mixed = sign(mixed) * (peakLimitLinear + (absVal - peakLimitLinear) * 0.05f)
                        mixed = mixed.coerceIn(-peakLimitLinear, peakLimitLinear)
                    }
                    if (abs(mixed) > maxPeakLinear) maxPeakLinear = abs(mixed)
                    outPcm[pos] = (mixed * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
            }
            idx += block
        }
    }

    companion object {
        fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
        fun linearToDb(linear: Double): Double = 20.0 * log10(max(1e-9, linear))
    }
}
