package com.vitrinevideo.engine.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Streaming Media3 AudioProcessor mixing capture audio with a pre-decoded stereo 16-bit PCM music file.
 * Automatically adapts to Media3's pipeline sample rate (e.g., 44100 Hz or 48000 Hz) and channel count
 * with constant O(1) memory chunks and real-time ducking.
 */
@OptIn(UnstableApi::class)
class VitrineAudioProcessor(
    private val musicPcmFile: File?,
    private val mixConfig: AudioMixConfig,
    private val musicPcmSampleRateHz: Int = 48_000
) : BaseAudioProcessor() {

    private var duckingEngine: AudioDuckingEngine? = null
    private var musicRaf: RandomAccessFile? = null
    private var musicDataBytes: Long = 0L
    private var musicFrameCursor: Double = 0.0

    private var capShortBuf = ShortArray(4096)
    private var musShortBuf = ShortArray(4096)
    private var outShortBuf = ShortArray(4096)
    private var srcReadByteBuf = ByteArray(16384)
    private var srcReadShortBuf = ShortArray(8192)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        duckingEngine = AudioDuckingEngine(
            sampleRateHz = inputAudioFormat.sampleRate,
            channels = inputAudioFormat.channelCount,
            config = mixConfig
        )
        openMusicStream()
        return inputAudioFormat
    }

    private fun openMusicStream() {
        closeMusicStream()
        val f = musicPcmFile ?: return
        if (f.exists() && f.length() > 0L) {
            musicRaf = RandomAccessFile(f, "r")
            musicDataBytes = f.length()
            musicFrameCursor = 0.0
        }
    }

    private fun closeMusicStream() {
        try {
            musicRaf?.close()
        } catch (_: Throwable) {
        }
        musicRaf = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val channels = inputAudioFormat.channelCount
        val bytesPerFrame = channels * 2
        val frames = remaining / bytesPerFrame
        if (frames <= 0) return

        val sampleCount = frames * channels
        if (capShortBuf.size < sampleCount) {
            capShortBuf = ShortArray(sampleCount)
            musShortBuf = ShortArray(sampleCount)
            outShortBuf = ShortArray(sampleCount)
        }
        val neededBytes = frames * bytesPerFrame

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(capShortBuf, 0, sampleCount)
        inputBuffer.position(inputBuffer.position() + neededBytes)

        readMusicFramesResampled(frames, inputAudioFormat.sampleRate, channels)

        duckingEngine?.processInterleavedPcm16(capShortBuf, musShortBuf, frames, outShortBuf)

        val out = replaceOutputBuffer(neededBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.asShortBuffer().put(outShortBuf, 0, sampleCount)
        out.position(neededBytes)
        out.flip()
    }

    private fun readMusicFramesResampled(dstFrames: Int, dstRateHz: Int, dstChannels: Int) {
        val raf = musicRaf
        val totalSrcFrames = musicDataBytes / 4L // decoded PCM is always stereo 16-bit (4 bytes/frame)
        if (raf == null || totalSrcFrames <= 1L) {
            musShortBuf.fill(0, 0, dstFrames * dstChannels)
            return
        }

        val step = musicPcmSampleRateHz.toDouble() / dstRateHz.toDouble()
        val srcFramesNeeded = (dstFrames * step).toInt() + 4
        val neededSrcBytes = srcFramesNeeded * 4
        if (srcReadByteBuf.size < neededSrcBytes) {
            srcReadByteBuf = ByteArray(neededSrcBytes)
            srcReadShortBuf = ShortArray(srcFramesNeeded * 2)
        }

        val startFrameIdx = musicFrameCursor.toLong() % totalSrcFrames
        val fracStart = musicFrameCursor - kotlin.math.floor(musicFrameCursor)
        readLoopingStereoBytes(raf, totalSrcFrames * 4L, startFrameIdx * 4L, neededSrcBytes)

        ByteBuffer.wrap(srcReadByteBuf, 0, neededSrcBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(srcReadShortBuf, 0, srcFramesNeeded * 2)

        for (df in 0 until dstFrames) {
            val pos = fracStart + df * step
            val i0 = pos.toInt().coerceIn(0, srcFramesNeeded - 2)
            val i1 = i0 + 1
            val f = (pos - i0).toFloat()
            val l = (srcReadShortBuf[i0 * 2] + (srcReadShortBuf[i1 * 2] - srcReadShortBuf[i0 * 2]) * f)
                .toInt().coerceIn(-32768, 32767).toShort()
            val r = (srcReadShortBuf[i0 * 2 + 1] + (srcReadShortBuf[i1 * 2 + 1] - srcReadShortBuf[i0 * 2 + 1]) * f)
                .toInt().coerceIn(-32768, 32767).toShort()

            if (dstChannels == 1) {
                musShortBuf[df] = ((l + r) / 2).toShort()
            } else {
                musShortBuf[df * dstChannels] = l
                musShortBuf[df * dstChannels + 1] = r
                for (c in 2 until dstChannels) {
                    musShortBuf[df * dstChannels + c] = 0
                }
            }
        }
        musicFrameCursor = (musicFrameCursor + dstFrames * step) % totalSrcFrames.toDouble()
    }

    private fun readLoopingStereoBytes(raf: RandomAccessFile, totalBytes: Long, startByte: Long, count: Int) {
        var cursor = startByte % totalBytes
        var offset = 0
        while (offset < count) {
            raf.seek(cursor)
            val chunk = min((count - offset).toLong(), totalBytes - cursor).toInt()
            val read = raf.read(srcReadByteBuf, offset, chunk)
            if (read <= 0) {
                srcReadByteBuf.fill(0, offset, count)
                break
            }
            offset += read
            cursor = (cursor + read) % totalBytes
        }
    }

    override fun onFlush() {
        // Keep the music cursor: Media3 may flush between guided-capture scenes,
        // but the T4 bed must stay continuous instead of restarting at 0.
    }

    override fun onReset() {
        closeMusicStream()
        duckingEngine = null
    }
}
