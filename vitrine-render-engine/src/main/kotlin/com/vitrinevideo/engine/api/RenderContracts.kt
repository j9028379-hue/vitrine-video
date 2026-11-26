package com.vitrinevideo.engine.api

import org.json.JSONArray
import org.json.JSONObject

sealed class RenderOutcome {
    data class Success(
        val result: RenderResult,
        val report: RenderReport
    ) : RenderOutcome()

    data class Failure(
        val error: RenderError
    ) : RenderOutcome()
}

data class RenderResult(
    val status: String = "COMPLETED",
    val templateId: String,
    val templateVersion: String,
    val runId: String,
    val outputFileName: String,
    val outputPath: String,
    val sha256: String,
    val musicId: Int?,
    val musicCredit: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("status", status)
        put("template_id", templateId)
        put("template_version", templateVersion)
        put("run_id", runId)
        put("output", outputFileName)
        put("output_path", outputPath)
        put("sha256", sha256)
        if (musicId != null) put("music_id", musicId)
        if (musicCredit != null) put("music_credit", musicCredit)
    }

    /**
     * Strict schema-compliant output matching `schemas/render-result.json` (`additionalProperties: false`).
     */
    fun toSchemaResultJson(): JSONObject = JSONObject().apply {
        put("status", status)
        put("template_id", templateId)
        put("run_id", runId)
        put("sha256", sha256)
        put("output", outputFileName)
        if (musicId != null) put("music_id", musicId)
    }
}

data class SceneReport(
    val sceneId: String,
    val sourceSha256: String,
    val startFrame: Int,
    val endFrameExcluded: Int,
    val audioActive: Boolean
)

data class RenderReport(
    val templateId: String,
    val templateVersion: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frames: Int,
    val durationSec: Double,
    val codec: String,
    val videoSha256: String,
    val scenes: List<SceneReport>,
    val activeAudioScenes: List<String>,
    val priceDisplayed: Boolean,
    val usedHardwareEncoder: Boolean,
    val renderDurationMs: Long,
    val peakHeapMb: Long,
    val colorMode: String = "source_native",
    val addedTintApplied: Boolean = false,
    val decorativeBorderApplied: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("template_id", templateId)
        put("template_version", templateVersion)
        put("dimensions", JSONArray(listOf(width, height)))
        put("fps", fps)
        put("frames", frames)
        put("duree_s", durationSec)
        put("codec", codec)
        put("sha256", videoSha256)
        put("prix_affiche", priceDisplayed)
        put("teintes_ajoutees", addedTintApplied)
        put("etalonnage", JSONObject().apply {
            put("mode", colorMode)
            put("teinte_ajoutee", addedTintApplied)
            put("bordure_decorative", decorativeBorderApplied)
        })
        put("used_hardware_encoder", usedHardwareEncoder)
        put("render_duration_ms", renderDurationMs)
        put("peak_heap_mb", peakHeapMb)
        put("scenes_audio", JSONArray(activeAudioScenes))
        put("scenes", JSONArray().apply {
            scenes.forEach { s ->
                put(JSONObject().apply {
                    put("scene", s.sceneId)
                    put("source", s.sourceSha256)
                    put("debut", s.startFrame)
                    put("fin_exclue", s.endFrameExcluded)
                    put("audio_active", s.audioActive)
                })
            }
        })
    }
}

data class RenderError(
    val status: String = "FAILED",
    val stage: String,
    val code: String,
    val message: String,
    val errorCode: Int? = null,
    val causeClass: String? = null,
    val causeChain: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("status", status)
        put("stage", stage)
        put("code", code)
        put("message", message.take(500))
        if (errorCode != null) put("error_code", errorCode)
        if (!causeClass.isNullOrBlank()) put("cause_class", causeClass)
        if (!causeChain.isNullOrBlank()) put("cause_chain", causeChain.take(1500))
    }

    /**
     * Strict schema-compliant output matching `schemas/render-error.json` (`additionalProperties: false`).
     */
    fun toSchemaErrorJson(): JSONObject = JSONObject().apply {
        put("status", status)
        put("stage", stage)
        put("message", message.take(500).ifBlank { code })
    }
}

enum class RenderStage {
    PREFLIGHT,
    INTERPRETER,
    CAPABILITY_CHECK,
    TIMELINE,
    AUDIO_DECODE,
    VIDEO_RENDER,
    POST_VERIFY,
    FINALIZE
}

data class RenderProgress(
    val stage: RenderStage,
    val percent: Int,
    val message: String
)

interface RenderListener {
    fun onProgress(progress: RenderProgress) {}
    fun onCompleted(result: RenderOutcome.Success) {}
    fun onFailed(error: RenderOutcome.Failure) {}
}
