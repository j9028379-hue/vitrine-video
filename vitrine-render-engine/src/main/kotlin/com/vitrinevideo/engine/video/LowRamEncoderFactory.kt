package com.vitrinevideo.engine.video

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.VideoEncoderSettings
import com.vitrinevideo.engine.api.EngineConfig

data class CodecProbeSummary(
    val hardwareAvcEncoders: List<String>,
    val softwareAvcEncoders: List<String>,
    val hardwareAvcDecoders: List<String>
)

@OptIn(UnstableApi::class)
object LowRamEncoderFactory {

    fun create(context: Context, config: EngineConfig): DefaultEncoderFactory {
        val videoBuilder = VideoEncoderSettings.Builder()
            .setBitrate(config.videoBitrateBps)
            .setiFrameIntervalSeconds(1.0f)

        // H.264 Baseline Profile Level 3.1 maximizes compatibility across entry-level SoCs
        // (MediaTek MT67xx, Unisoc SC9863A, Snapdragon 4xx) while fallback handles exceptions.
        if (config.preferBaselineH264Profile) {
            videoBuilder.setEncodingProfileLevel(
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
        }

        val audioSettings = AudioEncoderSettings.Builder()
            .setBitrate(config.audioBitrateBps)
            .build()

        return DefaultEncoderFactory.Builder(context)
            .setVideoEncoderSelector(EncoderSelector.DEFAULT)
            .setRequestedVideoEncoderSettings(videoBuilder.build())
            .setRequestedAudioEncoderSettings(audioSettings)
            .setEnableFallback(config.allowSoftwareFallback)
            .build()
    }

    fun probeDeviceCodecs(): CodecProbeSummary {
        val hwEnc = mutableListOf<String>()
        val swEnc = mutableListOf<String>()
        val hwDec = mutableListOf<String>()
        runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, ignoreCase = true) }) continue
                val isSw = info.name.startsWith("OMX.google.", ignoreCase = true) ||
                    info.name.startsWith("c2.android.", ignoreCase = true)
                if (info.isEncoder) {
                    if (isSw) swEnc += info.name else hwEnc += info.name
                } else if (!isSw) {
                    hwDec += info.name
                }
            }
        }
        return CodecProbeSummary(hwEnc, swEnc, hwDec)
    }
}
