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
}
