package com.vitrinevideo.engine.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes compressed music (MP3/M4A/AAC) into a temporary 48 kHz stereo 16-bit PCM file
 * via streaming MediaExtractor + MediaCodec with capped memory (< 256 KB buffers).
 */
object PcmTrackDecoder {

    fun decodeTo48kStereoPcm(
        sourceFile: File,
        destPcmFile: File,
        targetSampleRate: Int = 48_000,
        targetChannels: Int = 2,
        maxDurationSec: Double = 35.0
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            require(audioTrackIndex >= 0 && format != null) { "Aucune piste audio dans ${sourceFile.name}" }
            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val maxFrames = (maxDurationSec * targetSampleRate).toLong()
            var writtenFrames = 0L
            var inputDone = false
            var outputDone = false
            val outBytes = ByteArray(16384)
            val resampler = if (srcCh == targetChannels && srcRate == targetSampleRate) {
                null
            } else {
                StreamingStereoResampler(srcRate, targetSampleRate)
            }

            BufferedOutputStream(FileOutputStream(destPcmFile), 32 * 1024).use { bos ->
                while (!outputDone && writtenFrames < maxFrames) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shortCount = info.size / 2
                            val shorts = ShortArray(shortCount)
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

                            val resampled = resampler?.resampleStereo(shorts, srcCh) ?: shorts
                            val frameCount = resampled.size / targetChannels
                            val framesToWrite = min(frameCount.toLong(), maxFrames - writtenFrames).toInt()
                            val sampleLimit = framesToWrite * targetChannels

                            var bIdx = 0
                            for (i in 0 until sampleLimit) {
                                val v = resampled[i].toInt()
                                outBytes[bIdx++] = (v and 0xFF).toByte()
                                outBytes[bIdx++] = ((v ushr 8) and 0xFF).toByte()
                                if (bIdx >= outBytes.size - 2) {
                                    bos.write(outBytes, 0, bIdx)
                                    bIdx = 0
                                }
                            }
                            if (bIdx > 0) bos.write(outBytes, 0, bIdx)
                            writtenFrames += framesToWrite
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            extractor.release()
        }
    }
}
