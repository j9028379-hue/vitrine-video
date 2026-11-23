package com.vitrinevideo.engine.server

import com.vitrinevideo.engine.capture.GuidedCaptureSession
import org.json.JSONArray
import org.json.JSONObject

enum class ServerJobStatus {
    PENDING_UPLOAD,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}

enum class RenderJobMode(val wireValue: String) {
    LOCAL_DEVICE("local_device"),
    PREVIEW_PROXY("preview_proxy"),
    FINAL_EXPORT("final_export");

    companion object {
        fun fromWire(value: String?): RenderJobMode = when (value) {
            "preview_proxy" -> PREVIEW_PROXY
            "local_device" -> LOCAL_DEVICE
            else -> FINAL_EXPORT
        }
    }
}

data class ServerEndpointConfig(
    val apiBaseUrl: String,
    val connectTimeoutMs: Int = 15_000,
    val uploadTimeoutMs: Int = 120_000,
    val pollIntervalMs: Long = 3_000L,
    val maxPollAttempts: Int = 120
)

/**
 * Tier-1 local preview descriptor returned by GET /v1/templates/{id}/{ver}/preview-descriptor.
 * Contains ONLY visual geometry/timing/text layout for deterministic local on-device playback;
 * strictly omits proprietary master audio tracks, voiceovers, and raw partition.json files.
 */
data class PreviewDescriptor(
    val descriptorVersion: String,
    val templateId: String,
    val templateVersion: String,
    val localAudioProtected: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val totalFrames: Int,
    val scenesJson: JSONArray,
    val transitionsJson: JSONArray,
    val textLayoutJson: JSONObject
) {
    companion object {
        fun fromJson(raw: String): PreviewDescriptor {
            val obj = JSONObject(raw)
            val fmt = obj.getJSONObject("format")
            return PreviewDescriptor(
                descriptorVersion = obj.optString("descriptor_version", "1.0"),
                templateId = obj.getString("template_id"),
                templateVersion = obj.getString("template_version"),
                localAudioProtected = obj.optBoolean("local_audio_protected", true),
                width = fmt.optInt("width", 720),
                height = fmt.optInt("height", 1280),
                fps = fmt.optInt("fps", 30),
                totalFrames = fmt.optInt("total_frames", 900),
                scenesJson = obj.getJSONArray("scenes"),
                transitionsJson = obj.optJSONArray("transitions") ?: JSONArray(),
                textLayoutJson = obj.optJSONObject("text_layout") ?: JSONObject()
            )
        }
    }
}

data class SceneUploadTarget(
    val sceneId: String,
    val r2Key: String,
    val presignedPutUrl: String
)

data class CreateSessionResponse(
    val jobId: String,
    val sessionId: String,
    val mode: RenderJobMode = RenderJobMode.FINAL_EXPORT,
    val previewSessionId: String? = null,
    val maxSceneBytes: Long = 26_214_400L,
    val uploadTargets: Map<String, SceneUploadTarget>
) {
    companion object {
        fun fromJson(raw: String): CreateSessionResponse {
            val obj = JSONObject(raw)
            val urls = obj.getJSONObject("upload_urls")
            val map = linkedMapOf<String, SceneUploadTarget>()
            val keys = urls.keys()
            while (keys.hasNext()) {
                val sc = keys.next()
                val item = urls.getJSONObject(sc)
                map[sc] = SceneUploadTarget(
                    sceneId = sc,
                    r2Key = item.getString("r2_key"),
                    presignedPutUrl = item.getString("put_url")
                )
            }
            return CreateSessionResponse(
                jobId = obj.getString("job_id"),
                sessionId = obj.getString("session_id"),
                mode = RenderJobMode.fromWire(obj.optString("mode", "final_export")),
                previewSessionId = obj.optString("preview_session_id", "").takeIf { it.isNotEmpty() },
                maxSceneBytes = obj.optLong("max_scene_bytes", 26_214_400L),
                uploadTargets = map
            )
        }
    }
}

data class ServerJobPollResult(
    val jobId: String,
    val status: ServerJobStatus,
    val mode: RenderJobMode = RenderJobMode.FINAL_EXPORT,
    val dedupCacheHit: Boolean = false,
    val musicId: Int? = null,
    val sha256: String? = null,
    val downloadUrl: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun fromJson(raw: String): ServerJobPollResult {
            val obj = JSONObject(raw)
            return ServerJobPollResult(
                jobId = obj.getString("job_id"),
                status = ServerJobStatus.valueOf(obj.getString("status")),
                mode = RenderJobMode.fromWire(obj.optString("mode", "final_export")),
                dedupCacheHit = obj.optBoolean("dedup_cache_hit", false),
                musicId = if (obj.isNull("music_id")) null else obj.optInt("music_id"),
                sha256 = obj.optString("sha256", "").takeIf { it.isNotEmpty() },
                downloadUrl = obj.optString("download_url", "").takeIf { it.isNotEmpty() },
                errorMessage = obj.optString("error_message", "").takeIf { it.isNotEmpty() }
            )
        }
    }
}

object ServerSessionPayloadBuilder {

    fun buildCreateSessionJson(
        session: GuidedCaptureSession,
        mode: RenderJobMode = RenderJobMode.FINAL_EXPORT,
        previewSessionId: String? = null,
        fileSizesByScene: Map<String, Long> = emptyMap()
    ): JSONObject = JSONObject().apply {
        put("session_id", session.sessionId)
        put("template_id", session.templateId)
        put("template_version", session.templateVersion)
        put("mode", mode.wireValue)
        if (!previewSessionId.isNullOrEmpty()) {
            put("preview_session_id", previewSessionId)
        }
        if (fileSizesByScene.isNotEmpty()) {
            put("file_sizes", JSONObject(fileSizesByScene))
        }
        put("responses", JSONObject(session.responses))
    }

    fun buildSubmitCapturesJson(
        session: GuidedCaptureSession,
        uploadTargets: Map<String, SceneUploadTarget>,
        shaByScene: Map<String, String>,
        sizeBytesByScene: Map<String, Long> = emptyMap()
    ): JSONObject {
        val arr = JSONArray()
        for (cap in session.captures) {
            val target = uploadTargets[cap.sceneId]
                ?: throw IllegalArgumentException("Missing R2 upload target for scene ${cap.sceneId}")
            val sha = shaByScene[cap.sceneId] ?: cap.sha256
            require(sha.length == 64) { "Missing SHA-256 for scene ${cap.sceneId}" }
            arr.put(
                JSONObject().apply {
                    put("scene_id", cap.sceneId)
                    put("r2_key", target.r2Key)
                    put("sha256", sha)
                    sizeBytesByScene[cap.sceneId]?.let { put("size_bytes", it) }
                    put("duration_us", cap.durationUs)
                    put("width", cap.width)
                    put("height", cap.height)
                    put("rotation", cap.rotation)
                    put("audio_present", cap.audioPresent)
                }
            )
        }
        return JSONObject().apply {
            put("captures", arr)
            put("responses", JSONObject(session.responses))
        }
    }

    fun buildAuthorizeLocalExportJson(
        session: GuidedCaptureSession,
        clientExportId: String
    ): JSONObject = JSONObject().apply {
        put("client_export_id", clientExportId)
        put("session_id", session.sessionId)
        put("template_id", session.templateId)
        put("template_version", session.templateVersion)
        put("responses", JSONObject(session.responses))
    }

    fun buildConfirmLocalExportJson(
        exportId: String,
        outputSha256: String,
        sizeBytes: Long,
        renderDurationMs: Long,
        usedHardwareEncoder: Boolean
    ): JSONObject = JSONObject().apply {
        put("export_id", exportId)
        put("output_sha256", outputSha256)
        put("size_bytes", sizeBytes)
        put("render_duration_ms", renderDurationMs)
        put("used_hardware_encoder", usedHardwareEncoder)
    }

    fun buildFailOrFallbackJson(
        exportId: String,
        reason: String,
        requestServerFallback: Boolean,
        session: GuidedCaptureSession? = null
    ): JSONObject = JSONObject().apply {
        put("export_id", exportId)
        put("reason", reason.take(400))
        put("request_server_fallback", requestServerFallback)
        if (requestServerFallback && session != null) {
            put("responses", JSONObject(session.responses))
        }
    }
}

data class LocalExportAuthorization(
    val exportId: String,
    val clientExportId: String,
    val exportLeaseHmac: String,
    val expiresAt: String,
    val musicId: Int?,
    val musicRelPath: String?,
    val trackUrl: String?,
    val creditsRemaining: Int,
    val idempotentReplay: Boolean = false
) {
    companion object {
        fun fromJson(raw: String): LocalExportAuthorization {
            val obj = JSONObject(raw)
            return LocalExportAuthorization(
                exportId = obj.getString("export_id"),
                clientExportId = obj.getString("client_export_id"),
                exportLeaseHmac = obj.getString("export_lease_hmac"),
                expiresAt = obj.optString("expires_at", ""),
                musicId = if (obj.isNull("music_id")) null else obj.optInt("music_id"),
                musicRelPath = obj.optString("music_rel_path", "").takeIf { it.isNotEmpty() },
                trackUrl = obj.optString("track_url", "").takeIf { it.isNotEmpty() },
                creditsRemaining = obj.optInt("credits_remaining", 0),
                idempotentReplay = obj.optBoolean("idempotent", false)
            )
        }
    }
}

data class LocalExportFailOrFallbackResult(
    val exportId: String,
    val status: String,
    val refunded: Boolean,
    val fallbackJobId: String? = null,
    val fallbackSessionId: String? = null,
    val uploadTargets: Map<String, SceneUploadTarget> = emptyMap()
) {
    companion object {
        fun fromJson(raw: String): LocalExportFailOrFallbackResult {
            val obj = JSONObject(raw)
            val urls = obj.optJSONObject("upload_urls")
            val map = linkedMapOf<String, SceneUploadTarget>()
            if (urls != null) {
                val keys = urls.keys()
                while (keys.hasNext()) {
                    val sc = keys.next()
                    val item = urls.getJSONObject(sc)
                    map[sc] = SceneUploadTarget(
                        sceneId = sc,
                        r2Key = item.getString("r2_key"),
                        presignedPutUrl = item.getString("put_url")
                    )
                }
            }
            return LocalExportFailOrFallbackResult(
                exportId = obj.getString("export_id"),
                status = obj.getString("status"),
                refunded = obj.optBoolean("refunded", false),
                fallbackJobId = obj.optString("fallback_job_id", "").takeIf { it.isNotEmpty() },
                fallbackSessionId = obj.optString("fallback_session_id", "").takeIf { it.isNotEmpty() },
                uploadTargets = map
            )
        }
    }
}

data class ProtectedTemplatePackUrls(
    val templateId: String,
    val templateVersion: String,
    val partitionJsonUrl: String,
    val audioProfileJsonUrl: String?,
    val fontUrl: String,
    val fontRelPath: String,
    val guideJsonUrl: String?
) {
    companion object {
        fun fromJson(raw: String): ProtectedTemplatePackUrls {
            val obj = JSONObject(raw)
            val urls = obj.getJSONObject("urls")
            return ProtectedTemplatePackUrls(
                templateId = obj.getString("template_id"),
                templateVersion = obj.getString("template_version"),
                partitionJsonUrl = urls.getString("partition_json"),
                audioProfileJsonUrl = urls.optString("audio_profile_json", "").takeIf { it.isNotEmpty() },
                fontUrl = urls.getString("font_url"),
                fontRelPath = urls.getString("font_rel_path"),
                guideJsonUrl = urls.optString("guide_json", "").takeIf { it.isNotEmpty() }
            )
        }
    }
}
