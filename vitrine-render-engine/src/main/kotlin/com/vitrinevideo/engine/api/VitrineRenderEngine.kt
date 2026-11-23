package com.vitrinevideo.engine.api

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.vitrinevideo.engine.audio.AndroidSqliteRotationStore
import com.vitrinevideo.engine.audio.PcmTrackDecoder
import com.vitrinevideo.engine.audio.TrackRotationCoordinator
import com.vitrinevideo.engine.capabilities.EngineCapabilities
import com.vitrinevideo.engine.capabilities.UnsupportedCapabilityException
import com.vitrinevideo.engine.capture.GuidedCaptureSession
import com.vitrinevideo.engine.capture.GuidedCaptureValidationException
import com.vitrinevideo.engine.capture.GuidedCaptureValidator
import com.vitrinevideo.engine.capture.SceneCaptureDescriptor
import com.vitrinevideo.engine.export.ExportVerificationException
import com.vitrinevideo.engine.export.Media3RenderPipeline
import com.vitrinevideo.engine.export.PostRenderVerifier
import com.vitrinevideo.engine.form.AssembledTemplateJob
import com.vitrinevideo.engine.form.TemplateFormValidationException
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.pkg.LoadedTemplatePackage
import com.vitrinevideo.engine.pkg.TemplatePackageException
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.timeline.PreFlightException
import com.vitrinevideo.engine.timeline.PreFlightValidator
import java.io.File
import java.util.UUID

/**
 * Standalone, UI-free, template-agnostic Android video rendering engine for VITRINE VIDÉO.
 * Consumes either raw [RenderRequest] or validated in-app [GuidedCaptureSession].
 */
class VitrineRenderEngine(
    private val context: Context,
    private val config: EngineConfig = EngineConfig()
) {
    private var activePipeline: Media3RenderPipeline? = null

    fun supportedCapabilities(): List<Pair<String, String>> = EngineCapabilities.catalogSummary()

    fun probeDeviceCodecs(): com.vitrinevideo.engine.video.CodecProbeSummary =
        com.vitrinevideo.engine.video.LowRamEncoderFactory.probeDeviceCodecs()

    /**
     * Validates JSON partition, capability compatibility, audio profile, manifest and captures
     * without running the video encoder.
     */
    fun validateOnly(request: RenderRequest): RenderPlan {
        val plan = UniversalJsonInterpreter.parsePartition(request.partitionJsonFile)
        val audioProfile = request.audioProfileJsonFile?.let { UniversalJsonInterpreter.parseAudioProfile(it) }
        PreFlightValidator.validate(context, request, plan, audioProfile, config)
        return plan
    }

    /**
     * Validates an Ensemble 1 -> Ensemble 2 guided capture session before starting render.
     */
    fun validateGuidedSession(session: GuidedCaptureSession): Pair<RenderPlan, RenderRequest> {
        val (plan, request) = GuidedCaptureValidator.validateAndMap(session, config)
        val audioProfile = request.audioProfileJsonFile?.let { UniversalJsonInterpreter.parseAudioProfile(it) }
        PreFlightValidator.validate(context, request, plan, audioProfile, config)
        return plan to request
    }

    /**
     * Universally assembles and validates a RenderJob directly from a verified [LoadedTemplatePackage],
     * merchant responses, and real CameraX captures (zero template hardcoding).
     */
    fun validatePackageJob(
        pkg: LoadedTemplatePackage,
        sessionId: String,
        responses: Map<String, String>,
        captures: List<SceneCaptureDescriptor>,
        outputDir: File,
        rotationDbFile: File,
        visitedScreens: List<Int> = emptyList()
    ): AssembledTemplateJob {
        val assembled = TemplateJobAssembler.assemble(
            pkg = pkg,
            sessionId = sessionId,
            rawResponses = responses,
            captures = captures,
            outputDir = outputDir,
            rotationDbFile = rotationDbFile,
            visitedScreens = visitedScreens
        )
        PreFlightValidator.validate(context, assembled.renderRequest, pkg.renderPlan, pkg.audioProfile, config)
        return assembled
    }

    /**
     * End-to-end universal render from an imported [LoadedTemplatePackage] + responses + CameraX captures.
     */
    fun renderPackage(
        pkg: LoadedTemplatePackage,
        sessionId: String,
        responses: Map<String, String>,
        captures: List<SceneCaptureDescriptor>,
        outputDir: File,
        rotationDbFile: File,
        visitedScreens: List<Int> = emptyList(),
        listener: RenderListener? = null
    ): RenderOutcome {
        return try {
            listener?.onProgress(RenderProgress(RenderStage.PREFLIGHT, 2, "Assemblage et validation du RenderJob"))
            val assembled = TemplateJobAssembler.assemble(
                pkg = pkg,
                sessionId = sessionId,
                rawResponses = responses,
                captures = captures,
                outputDir = outputDir,
                rotationDbFile = rotationDbFile,
                visitedScreens = visitedScreens
            )
            File(outputDir.apply { mkdirs() }, "job.json").writeText(assembled.jobJson.toString(2))
            renderGuidedSession(assembled.guidedSession, listener)
        } catch (e: TemplateFormValidationException) {
            fail(outputDir, "FORM_AND_JOB_CONTRACT", e.code, e.message ?: "Réponses ou captures invalides", listener)
        } catch (e: TemplatePackageException) {
            fail(outputDir, "TEMPLATE_PACKAGE", e.code, e.message ?: "Paquet gabarit invalide", listener)
        } catch (t: Throwable) {
            failFromThrowable(outputDir, t, listener)
        }
    }

    /**
     * End-to-end universal render directly from a template ZIP file.
     */
    fun renderZipPackage(
        templateZip: File,
        packageStorageRoot: File,
        sessionId: String,
        responses: Map<String, String>,
        captures: List<SceneCaptureDescriptor>,
        outputDir: File,
        rotationDbFile: File,
        visitedScreens: List<Int> = emptyList(),
        listener: RenderListener? = null
    ): RenderOutcome {
        return try {
            listener?.onProgress(RenderProgress(RenderStage.PREFLIGHT, 1, "Import et vérification SHA-256 du paquet ZIP"))
            val pkg = TemplatePackageLoader.importZip(templateZip, packageStorageRoot)
            renderPackage(pkg, sessionId, responses, captures, outputDir, rotationDbFile, visitedScreens, listener)
        } catch (e: TemplatePackageException) {
            fail(outputDir, "TEMPLATE_PACKAGE", e.code, e.message ?: "Paquet ZIP invalide", listener)
        } catch (t: Throwable) {
            failFromThrowable(outputDir, t, listener)
        }
    }

    /**
     * Runs automatic montage & export directly from a validated guided capture session.
     */
    fun renderGuidedSession(session: GuidedCaptureSession, listener: RenderListener? = null): RenderOutcome {
        return try {
            listener?.onProgress(RenderProgress(RenderStage.PREFLIGHT, 2, "Validation du contrat capture guidée"))
            val (_, request) = GuidedCaptureValidator.validateAndMap(session, config)
            render(request, listener)
        } catch (e: GuidedCaptureValidationException) {
            fail(
                outputDir = session.outputDir,
                stage = "GUIDED_CAPTURE_CONTRACT",
                code = e.code,
                message = e.message ?: "Session de capture invalide",
                listener = listener
            )
        } catch (e: UnsupportedCapabilityException) {
            fail(
                outputDir = session.outputDir,
                stage = "CAPABILITY_CHECK",
                code = "UNSUPPORTED_CAPABILITY",
                message = e.message ?: "Capacité non supportée",
                listener = listener
            )
        } catch (t: Throwable) {
            failFromThrowable(session.outputDir, t, listener)
        }
    }

    fun cancel() {
        activePipeline?.cancel()
    }

    fun render(request: RenderRequest, listener: RenderListener? = null): RenderOutcome {
        val startMs = System.currentTimeMillis()
        if (config.cleanTempOnStart) {
            sweepOrphanedTempDirs(request.outputDir)
        }
        val workDir = File(request.outputDir, ".vitrine_work_${UUID.randomUUID()}")
        workDir.mkdirs()

        try {
            listener?.onProgress(RenderProgress(RenderStage.INTERPRETER, 5, "Interprétation de la partition JSON"))
            val plan = UniversalJsonInterpreter.parsePartition(request.partitionJsonFile)
            val audioProfile = request.audioProfileJsonFile?.let { UniversalJsonInterpreter.parseAudioProfile(it) }

            listener?.onProgress(RenderProgress(RenderStage.PREFLIGHT, 12, "Validation intégrité captures et stockage"))
            val preFlight = PreFlightValidator.validate(context, request, plan, audioProfile, config)

            val rotationStore = AndroidSqliteRotationStore(context, request.rotationDbFile)
            val rotationCoordinator = TrackRotationCoordinator(rotationStore, request.resourcesDir)

            var musicPcmFile: File? = null
            var selectedTrackNumber: Int? = null
            var selectedTrackCredit: String? = null
            val mixConfig = audioProfile?.mix ?: AudioMixConfig(
                profileId = "default_silent_music",
                includeCaptureVoice = true
            )

            if (audioProfile != null) {
                listener?.onProgress(RenderProgress(RenderStage.AUDIO_DECODE, 20, "Sélection et décodage piste musicale"))
                val (track, trackFile) = rotationCoordinator.selectTrackForRender(audioProfile, preFlight.runId)
                selectedTrackNumber = track.number
                selectedTrackCredit = track.credit
                musicPcmFile = File(workDir, "music_48k_stereo.pcm")
                PcmTrackDecoder.decodeTo48kStereoPcm(
                    sourceFile = trackFile,
                    destPcmFile = musicPcmFile,
                    targetSampleRate = config.audioSampleRateHz,
                    targetChannels = config.audioChannels,
                    maxDurationSec = plan.durationSec + 2.0
                )
            }

            val tempMp4 = File(workDir, "export_tmp.mp4")
            val pipeline = Media3RenderPipeline(context, config)
            activePipeline = pipeline

            listener?.onProgress(RenderProgress(RenderStage.VIDEO_RENDER, 30, "Rendu vidéo Media3 en cours"))
            val usedHardware = pipeline.execute(
                plan = plan,
                preFlight = preFlight,
                responses = request.responses,
                resourcesDir = request.resourcesDir,
                musicPcmFile = musicPcmFile,
                mixConfig = mixConfig,
                tempMp4Out = tempMp4
            ) { pct ->
                val scaled = 30 + (pct * 58) / 100
                listener?.onProgress(RenderProgress(RenderStage.VIDEO_RENDER, scaled, "Encodage MP4 ($pct%)"))
            }

            listener?.onProgress(RenderProgress(RenderStage.POST_VERIFY, 90, "Vérification du MP4 produit"))
            val priceDisplayed = !request.responses["prix"].isNullOrBlank()
            val report = PostRenderVerifier.verifyAndBuildReport(
                mp4File = tempMp4,
                plan = plan,
                preFlight = preFlight,
                config = config,
                priceDisplayed = priceDisplayed,
                usedHardwareEncoder = usedHardware,
                renderDurationMs = System.currentTimeMillis() - startMs
            )

            listener?.onProgress(RenderProgress(RenderStage.FINALIZE, 96, "Finalisation atomique"))
            val finalFileName = "Film.mp4"
            val finalMp4 = File(request.outputDir, finalFileName)
            if (finalMp4.exists()) finalMp4.delete()
            if (!tempMp4.renameTo(finalMp4)) {
                tempMp4.copyTo(finalMp4, overwrite = true)
            }

            if (audioProfile != null && selectedTrackNumber != null) {
                rotationCoordinator.commitAfterVerifiedRender(audioProfile.id, preFlight.runId, selectedTrackNumber)
            }

            val result = RenderResult(
                templateId = plan.templateId,
                templateVersion = plan.version,
                runId = preFlight.runId,
                outputFileName = finalFileName,
                outputPath = finalMp4.absolutePath,
                sha256 = report.videoSha256,
                musicId = selectedTrackNumber,
                musicCredit = selectedTrackCredit
            )
            File(request.outputDir, "resultat.json").writeText(result.toSchemaResultJson().toString(2))
            File(request.outputDir, "controle.json").writeText(report.toJson().toString(2))
            selectedTrackCredit?.let { File(request.outputDir, "CREDITS.txt").writeText(it) }

            listener?.onProgress(RenderProgress(RenderStage.FINALIZE, 100, "Rendu terminé"))
            val outcome = RenderOutcome.Success(result, report)
            listener?.onCompleted(outcome)
            return outcome
        } catch (e: UnsupportedCapabilityException) {
            return fail(request.outputDir, "CAPABILITY_CHECK", "UNSUPPORTED_CAPABILITY", e.message ?: "Capacité non supportée", listener)
        } catch (e: PreFlightException) {
            return fail(request.outputDir, "PREFLIGHT", e.code, e.message ?: "Erreur pré-vol", listener)
        } catch (e: ExportVerificationException) {
            return fail(request.outputDir, "POST_VERIFY", e.code, e.message ?: "Erreur post-vérification", listener)
        } catch (e: InterruptedException) {
            return fail(request.outputDir, "VIDEO_RENDER", "RENDER_CANCELLED", e.message ?: "Rendu annulé", listener)
        } catch (t: Throwable) {
            return failFromThrowable(request.outputDir, t, listener)
        } finally {
            activePipeline = null
            workDir.deleteRecursively()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun failFromThrowable(
        outputDir: File,
        t: Throwable,
        listener: RenderListener?
    ): RenderOutcome.Failure {
        val exportEx = t as? ExportException
        val exportCode = exportEx?.errorCode
        val code = if (exportCode != null) "EXPORT_$exportCode" else t.javaClass.simpleName
        val stage = if (exportEx != null) "VIDEO_RENDER" else "ENGINE"
        val chain = buildString {
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth < 6) {
                if (depth > 0) append(" -> ")
                append(cur.javaClass.simpleName)
                cur.message?.let { append(": ").append(it) }
                cur = cur.cause
                depth++
            }
            exportEx?.codecInfo?.let { append(" | codec=").append(it.toString()) }
        }
        return fail(
            outputDir = outputDir,
            stage = stage,
            code = code,
            message = t.message ?: "Erreur interne moteur",
            listener = listener,
            errorCode = exportCode,
            causeClass = t.cause?.javaClass?.name,
            causeChain = chain
        )
    }

    private fun fail(
        outputDir: File,
        stage: String,
        code: String,
        message: String,
        listener: RenderListener?,
        errorCode: Int? = null,
        causeClass: String? = null,
        causeChain: String? = null
    ): RenderOutcome.Failure {
        val err = RenderError(
            stage = stage,
            code = code,
            message = message,
            errorCode = errorCode,
            causeClass = causeClass,
            causeChain = causeChain
        )
        runCatching {
            outputDir.mkdirs()
            File(outputDir, "erreur.json").writeText(err.toSchemaErrorJson().toString(2))
            File(outputDir, "erreur_detail.json").writeText(err.toJson().toString(2))
        }
        val failure = RenderOutcome.Failure(err)
        listener?.onFailed(failure)
        return failure
    }

    private fun sweepOrphanedTempDirs(outputDir: File) {
        if (!outputDir.exists()) return
        outputDir.listFiles()?.filter { it.isDirectory && it.name.startsWith(".vitrine_work_") }?.forEach {
            it.deleteRecursively()
        }
    }
}
