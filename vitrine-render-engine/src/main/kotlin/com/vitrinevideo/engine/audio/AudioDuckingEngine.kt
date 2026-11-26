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

    /**
     * T4 declares `ecart_min_db: 8`; T1/T3 do not. The gap is enforced by the
     * smoothed ducking envelope, never by a per-hop instantaneous gain jump
     * (which made the music pump and sound like scratching under speech).
     */
    val effectiveDuckGainDb: Double = listOfNotNull(
        config.underSpeechGainDb,
        config.duckDepthDb?.let { config.bgGainDb + it },
        config.minimumVoiceMusicGapDb?.let { config.bgGainDb - it }
    ).minOrNull() ?: config.underSpeechGainDb
    private val duckLinear = dbToLinear(effectiveDuckGainDb).toFloat()
    private val voiceLinear = dbToLinear(config.voiceGainDb).toFloat()
    private val useLinearDbEnvelope = config.attackCurve.equals("linear_db", ignoreCase = true) &&
        config.releaseCurve.equals("linear_db", ignoreCase = true)
    private val peakLimitLinear = dbToLinear(config.peakLimitDb).toFloat()
    private val limiterKneeLinear = peakLimitLinear * 0.6f

    // Normalize an approved track from its catalogue-measured LUFS before applying
    // the profile's open-bed / under-voice gains. Older packages without a measured
    // value retain the historical reference-level compensation.
    private val musicBaseScale: Float = run {
        val sourceLufs = config.sourceMusicLufs
        val normalizationDb = if (sourceLufs != null && sourceLufs.isFinite()) {
            (config.targetLufs - sourceLufs).coerceIn(-18.0, 18.0)
        } else {
            (config.targetLufs - (-16.0)) * 0.5
        }
        dbToLinear(normalizationDb).toFloat()
    }

    private val voiceVadThreshold = dbToLinear(config.voiceVadThresholdDbfs ?: -38.0)
    private val hopSamples = max(1, sampleRateHz / 100) // 10 ms = 480 frames
    private val attackCoeff = exp(-1.0 / max(1.0, (config.attackMs / 10.0)))
    private val releaseCoeff = exp(-1.0 / max(1.0, (config.releaseMs / 10.0)))
    private val voiceHoldHops = ceil((config.voiceHoldMs ?: 80).coerceAtLeast(0) / 10.0).toInt()

    private var duckEnvelope = 0.0 // 0.0 = open music bed, 1.0 = declared under-voice level
    private var lastVoiceHoldHops = 0
    private var lastDetectedVoiceRms = 0.0

    // Telemetry for verification/tests
    var maxPeakLinear: Float = 0f
        private set
    var totalDuckingHops: Long = 0L
        private set
    var activeDuckingHops: Long = 0L
        private set
    var maxAppliedMusicGain: Float = 0f
        private set
    var minAppliedMusicGain: Float = Float.MAX_VALUE
        private set
    var minMeasuredSpeechToMusicDb: Double = Double.POSITIVE_INFINITY
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
                for (c in 0 until channels) {
                    val voiceSample = capturePcm[base + c] / 32768.0
                    val musicSample = musicPcm[base + c] / 32768.0
                    sumSq += voiceSample * voiceSample
                    musicSumSq += musicSample * musicSample
                }
            }
            // Measure channel energy independently. Averaging L/R before RMS can
            // cancel a valid anti-phase stereo voice recording to digital silence.
            val channelSamples = block.toDouble() * channels
            val rms = sqrt(sumSq / channelSamples)
            val musicRms = sqrt(musicSumSq / channelSamples)
            val speechDetected = config.includeCaptureVoice && rms >= voiceVadThreshold
            if (speechDetected) {
                lastVoiceHoldHops = voiceHoldHops
                lastDetectedVoiceRms = rms
            } else if (lastVoiceHoldHops > 0) {
                lastVoiceHoldHops--
            }
            val speechActive = speechDetected || lastVoiceHoldHops > 0
            val targetDuck = if (speechActive) 1.0 else 0.0
            duckEnvelope = if (useLinearDbEnvelope) {
                val stepMs = 10.0
                val durationMs = if (targetDuck > duckEnvelope) config.attackMs else config.releaseMs
                val step = (stepMs / max(1.0, durationMs.toDouble())).coerceIn(0.0, 1.0)
                duckEnvelope + (targetDuck - duckEnvelope) * step
            } else {
                val coeff = if (targetDuck > duckEnvelope) attackCoeff else releaseCoeff
                targetDuck + coeff * (duckEnvelope - targetDuck)
            }

            totalDuckingHops++
            if (duckEnvelope > 0.25) activeDuckingHops++

            val nominalMusicGain = (if (useLinearDbEnvelope) {
                dbToLinear(config.bgGainDb + (effectiveDuckGainDb - config.bgGainDb) * duckEnvelope).toFloat()
            } else {
                bgLinear + (duckLinear - bgLinear) * duckEnvelope.toFloat()
            }) * musicBaseScale

            // `minimumSpeechToMusicDb` is a diagnostic quality-gate target, not
            // a command to keep attenuating music until every quiet syllable is
            // 4 dB above the bed. That old adaptive limiter could push music
            // tens of dB below the Commerce-validated -10 dB under-voice level.
            // Keep the profile's normal smooth attack/release and use the gate
            // only for telemetry below; this preserves audible music and avoids
            // level-dependent pumping for quiet handset recordings.
            val musicGain = nominalMusicGain
            if (musicGain > maxAppliedMusicGain) maxAppliedMusicGain = musicGain
            if (musicGain < minAppliedMusicGain) minAppliedMusicGain = musicGain
            if (speechActive && musicRms > 1e-9) {
                val voiceOutRms = lastDetectedVoiceRms * voiceLinear
                val musicOutRms = musicRms * musicGain
                minMeasuredSpeechToMusicDb = minOf(
                    minMeasuredSpeechToMusicDb,
                    linearToDb(voiceOutRms / max(musicOutRms.toDouble(), 1e-9))
                )
            }
            for (f in 0 until block) {
                val base = (idx + f) * channels
                for (c in 0 until channels) {
                    val pos = base + c
                    val cap = if (config.includeCaptureVoice) (capturePcm[pos] / 32768.0f) * voiceLinear else 0f
                    val mus = (musicPcm[pos] / 32768.0f) * musicGain
                    val mixed = softLimit(cap + mus)
                    if (abs(mixed) > maxPeakLinear) maxPeakLinear = abs(mixed)
                    outPcm[pos] = (mixed * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
            }
            idx += block
        }
    }

    /** Continuous soft-knee ceiling: avoids flat-topping while never exceeding the declared peak. */
    private fun softLimit(sample: Float): Float {
        val magnitude = abs(sample)
        if (magnitude <= limiterKneeLinear) return sample
        val headroom = (peakLimitLinear - limiterKneeLinear).coerceAtLeast(1e-6f)
        val shaped = limiterKneeLinear + headroom * tanh((magnitude - limiterKneeLinear) / headroom)
        return sign(sample) * min(shaped, peakLimitLinear)
    }

    companion object {
        fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
        fun linearToDb(linear: Double): Double = 20.0 * log10(max(1e-9, linear))
    }
}
