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

data class ServerEndpointConfig(
    val apiBaseUrl: String,
    val connectTimeoutMs: Int = 15_000,
    val uploadTimeoutMs: Int = 120_000,
    val pollIntervalMs: Long = 3_000L,
    val maxPollAttempts: Int = 120
)

data class SceneUploadTarget(
    val sceneId: String,
    val r2Key: String,
    val presignedPutUrl: String
)

data class CreateSessionResponse(
    val jobId: String,
    val sessionId: String,
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
                uploadTargets = map
            )
        }
    }
}

data class ServerJobPollResult(
    val jobId: String,
    val status: ServerJobStatus,
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
                musicId = if (obj.isNull("music_id")) null else obj.optInt("music_id"),
                sha256 = obj.optString("sha256", "").takeIf { it.isNotEmpty() },
                downloadUrl = obj.optString("download_url", "").takeIf { it.isNotEmpty() },
                errorMessage = obj.optString("error_message", "").takeIf { it.isNotEmpty() }
            )
        }
    }
}

object ServerSessionPayloadBuilder {

    fun buildCreateSessionJson(session: GuidedCaptureSession): JSONObject = JSONObject().apply {
        put("session_id", session.sessionId)
        put("template_id", session.templateId)
        put("template_version", session.templateVersion)
        put("responses", JSONObject(session.responses))
    }

    fun buildSubmitCapturesJson(
        session: GuidedCaptureSession,
        uploadTargets: Map<String, SceneUploadTarget>,
        shaByScene: Map<String, String>
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
