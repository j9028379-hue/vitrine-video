package com.vitrinevideo.engine.audio

import kotlin.math.floor
import kotlin.math.max

/**
 * Continuous-phase stereo resampler for MediaCodec MP3 output chunks.
 *
 * The previous implementation restarted interpolation at phase 0 for every
 * decoded chunk, producing a discontinuity every ~26 ms (audible scratching).
 * This keeps the fractional source position and the previous frame across
 * chunks, so the PCM stream stays continuous.
 */
class StreamingStereoResampler(
    private val sourceRateHz: Int,
    private val targetRateHz: Int
) {
    private val step: Double = sourceRateHz.toDouble() / targetRateHz.toDouble()
    private var phase: Double = 0.0
    private var carryLeft: Int = 0
    private var carryRight: Int = 0
    private var hasCarry: Boolean = false
    private var producedFrames: Long = 0L

    val outputFrameCount: Long get() = producedFrames

    fun resampleStereo(source: ShortArray, sourceChannels: Int): ShortArray {
        val srcFrames = source.size / max(1, sourceChannels)
        if (srcFrames == 0 || sourceRateHz == targetRateHz) return ShortArray(0)

        var frameCount = 0
        while (phase + frameCount * step < srcFrames) frameCount++
        val output = ShortArray(frameCount * 2)

        for (i in 0 until frameCount) {
            val position = phase + i * step
            val i0 = floor(position).toInt()
            val frac = (position - i0).toFloat()
            val useCarry = i0 == 0 && hasCarry && phase > 0.0
            val l0 = if (useCarry) carryLeft else source[i0 * sourceChannels].toInt()
            val r0 = if (useCarry) carryRight else {
                if (sourceChannels > 1) source[i0 * sourceChannels + 1].toInt() else l0
            }
            val l1 = if (i0 + 1 < srcFrames) source[(i0 + 1) * sourceChannels].toInt() else l0
            val r1 = if (i0 + 1 < srcFrames) {
                if (sourceChannels > 1) source[(i0 + 1) * sourceChannels + 1].toInt() else l1
            } else r0
            output[i * 2] = lerp16(l0, l1, frac)
            output[i * 2 + 1] = lerp16(r0, r1, frac)
        }

        producedFrames += frameCount.toLong()
        phase += frameCount * step
        while (phase >= srcFrames) phase -= srcFrames.toDouble()
        carryLeft = source[(srcFrames - 1) * sourceChannels].toInt()
        carryRight = if (sourceChannels > 1) source[(srcFrames - 1) * sourceChannels + 1].toInt() else carryLeft
        hasCarry = true
        return output
    }

    private fun lerp16(a: Int, b: Int, f: Float): Short =
        (a + (b - a) * f).toInt().coerceIn(-32768, 32767).toShort()
}
