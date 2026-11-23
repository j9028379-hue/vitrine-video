package com.vitrinevideo.engine.export

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import com.vitrinevideo.engine.api.EngineConfig
import com.vitrinevideo.engine.audio.VitrineAudioProcessor
import com.vitrinevideo.engine.graphics.VitrineBitmapTextOverlay
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.timeline.PreFlightReport
import com.vitrinevideo.engine.timeline.TimelineEngine
import com.vitrinevideo.engine.video.LowRamEncoderFactory
import com.vitrinevideo.engine.video.VitrineSceneGlEffect
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Orchestrates Media3 Transformer with a single EditedMediaItemSequence (1 hardware decoder at a time):
 * - Builds per-shot EditedMediaItems from `sc.shots` (supporting multi-plan T1/T5 and 1-shot T3/T4)
 * - Runs VitrineSceneGlEffect directly on the decoded frame (aspect-preserving crop/zoom/2x2/transitions)
 *   without pre-cropping via Presentation
 * - Applies band-bounded VitrineBitmapTextOverlay with pre-baked glyph cache
 * - Mixes audio in O(1) memory via VitrineAudioProcessor
 */
@OptIn(UnstableApi::class)
class Media3RenderPipeline(
    private val context: Context,
    private val config: EngineConfig
) {
    private val activeTransformer = AtomicReference<Transformer?>(null)
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        val t = activeTransformer.get()
        if (t != null) {
            Handler(t.applicationLooper).post { t.cancel() }
        }
    }

    fun execute(
        plan: RenderPlan,
        preFlight: PreFlightReport,
        responses: Map<String, String>,
        resourcesDir: File,
        musicPcmFile: File?,
        mixConfig: AudioMixConfig,
        tempMp4Out: File,
        onProgressPercent: (Int) -> Unit
    ): Boolean {
        cancelled.set(false)
        val timeline = TimelineEngine(plan)

        val editedItems = plan.scenes.flatMap { sc ->
            val cap = preFlight.validatedCaptures.getValue(sc.id)
            val shots = if (sc.shots.isNotEmpty()) sc.shots else listOf(
                com.vitrinevideo.engine.interpreter.CutSegmentSpec(
                    shotId = "${sc.id}_P1",
                    sceneId = sc.id,
                    sourceInFrame = 0,
                    sourceOutFrame = sc.endFrameExcluded - sc.startFrame,
                    outStartFrame = sc.startFrame,
                    outEndFrameExcluded = sc.endFrameExcluded
                )
            )
            val maxAvailMs = if (cap.durationUs > 0L) (cap.durationUs / 1000L) else Long.MAX_VALUE
            shots.map { shot ->
                val durUs = timeline.frameToUs(shot.outEndFrameExcluded - shot.outStartFrame)
                val clipStartMs = (timeline.frameToUs(shot.sourceInFrame) / 1000L).coerceAtLeast(0L)
                val rawEndMs = (timeline.frameToUs(shot.sourceOutFrame) / 1000L).coerceAtLeast(clipStartMs + 1L)
                val clipEndMs = min(rawEndMs, maxAvailMs).coerceAtLeast(clipStartMs + 1L)

                val clipCfg = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipStartMs)
                    .setEndPositionMs(clipEndMs)
                    .build()
                val item = MediaItem.Builder()
                    .setUri(Uri.fromFile(cap.file))
                    .setClippingConfiguration(clipCfg)
                    .build()
                EditedMediaItem.Builder(item)
                    .setDurationUs(durUs)
                    .setFrameRate(plan.fps)
                    .setRemoveAudio(!mixConfig.includeCaptureVoice)
                    .build()
            }
        }

        val sequence = EditedMediaItemSequence.Builder(editedItems).build()

        val videoEffects = mutableListOf<Effect>(
            VitrineSceneGlEffect(plan)
        )
        plan.textOverlay?.let { textSpec ->
            videoEffects += VitrineBitmapTextOverlay.createEffect(plan, textSpec, responses, resourcesDir)
        }

        val audioProcessors = listOf<AudioProcessor>(
            VitrineAudioProcessor(musicPcmFile, mixConfig, config.audioSampleRateHz)
        )

        val composition = Composition.Builder(sequence)
            .setEffects(Effects(audioProcessors, videoEffects))
            .experimentalSetForceAudioTrack(true)
            .build()

        val thread = HandlerThread("VitrineMedia3Render").apply { start() }
        val looper = thread.looper
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)
        val fallbackUsed = AtomicBoolean(false)

        try {
            val encoderFactory = LowRamEncoderFactory.create(context, config)
            val transformer = Transformer.Builder(context)
                .setLooper(looper)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .setPortraitEncodingEnabled(plan.height > plan.width)
                .experimentalSetMaxFramesInEncoder(4)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        errorRef.set(exportException)
                        latch.countDown()
                    }

                    override fun onFallbackApplied(
                        composition: Composition,
                        originalTransformationRequest: TransformationRequest,
                        resultingTransformationRequest: TransformationRequest
                    ) {
                        fallbackUsed.set(true)
                    }
                })
                .build()

            activeTransformer.set(transformer)
            val handler = Handler(looper)
            val progressHolder = ProgressHolder()
            val progressTicker = object : Runnable {
                override fun run() {
                    if (latch.count == 0L || cancelled.get()) return
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgressPercent(progressHolder.progress.coerceIn(0, 99))
                    }
                    handler.postDelayed(this, 200L)
                }
            }

            handler.post {
                if (!cancelled.get()) {
                    transformer.start(composition, tempMp4Out.absolutePath)
                    handler.postDelayed(progressTicker, 200L)
                } else {
                    latch.countDown()
                }
            }

            latch.await()
            if (cancelled.get()) {
                throw InterruptedException("Rendu annulé par l'appelant")
            }
            errorRef.get()?.let { throw it }
            return !fallbackUsed.get()
        } finally {
            activeTransformer.set(null)
            thread.quitSafely()
        }
    }
}
