package com.vitrinevideo.app.flow

import com.vitrinevideo.engine.capture.CaptureSessionState
import com.vitrinevideo.engine.capture.SceneCaptureDescriptor
import com.vitrinevideo.engine.pkg.LoadedTemplatePackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CreationSessionStatus {
    DRAFT,
    RECORDING,
    READY_FOR_RENDER,
    RENDERING,
    FAILED_PRESERVED,
    COMPLETED_CLEANED
}

data class VerifiedSessionRenderInputs(
    val creationId: String,
    val responses: Map<String, String>,
    val descriptors: List<SceneCaptureDescriptor>,
    val rejectedPreviousSessionIds: Set<String>,
    val rejectedPreviousCaptureSha256s: Set<String>
)

/**
 * Manages complete isolation and lifecycle of each 4-scene creation session (`creation_id` UUID).
 *
 * Guarantees:
 * 1. Every new video creation gets an isolated UUID `creation_id` and private temp folder `sessions_tmp/<creation_id>/captures/`.
 * 2. Confirmed Media3 render + verified final MP4 + permanent save closes the session, deletes all 4 temporary
 *    scene videos (S1..S4), clears temporary responses/checkpoint, resets S1..S4 state, and prepares a fresh blank session.
 * 3. Permanent published MP4s (`vitrine_exports/<creation_id>/Film.mp4` and `Movies/VitrineVideo/<file>.mp4`) are NEVER deleted.
 * 4. Failed renders preserve the current `creation_id`, S1..S4 clips, and merchant responses for immediate or post-restart retry.
 * 5. Closed session IDs and consumed capture SHA-256 hashes are tracked so old captures can never leak into a new creation.
 */
class SessionLifecycleManager(
    private val filesDir: File,
    private val executionMode: AppExecutionMode = AppExecutionMode.REAL_APPLICATION
) {
    val sessionsRootDir = File(filesDir, "sessions_tmp")
    val legacyCapturesRootDir = File(filesDir, "captures")
    val checkpointsDir = File(filesDir, "checkpoints")
    val activeCheckpointFile = File(checkpointsDir, "active_session.json")
    val legacyCheckpointFile = File(checkpointsDir, "active_t3_session.json")
    val closedSessionsFile = File(checkpointsDir, "closed_sessions.json")

    var creationId: String = UUID.randomUUID().toString()
        private set
    var templateId: String = "COMMERCE-T3"
        private set
    var status: CreationSessionStatus = CreationSessionStatus.DRAFT
        private set

    val responses: MutableMap<String, String> = linkedMapOf()
    val progression: CaptureProgressionStateMachine =
        CaptureProgressionStateMachine(executionMode = executionMode)

    private val closedSessionIds = linkedSetOf<String>()
    private val consumedCaptureHashes = linkedSetOf<String>()

    /**
     * Initializes lifecycle state on app start:
     * - Loads closed-session registry
     * - Sweeps stale temp folders from already-closed sessions
     * - Restores an unfinished/failed session if one exists, otherwise starts clean.
     */
    fun initialize(): Boolean {
        sessionsRootDir.mkdirs()
        checkpointsDir.mkdirs()
        loadClosedRegistry()
        legacyCheckpointFile.delete()

        val restored = tryRestoreUnfinishedCheckpoint()
        sweepOrphanedTempSessions(keepSessionId = if (restored) creationId else null)
        if (!restored) {
            startNewBlankSession(templateId)
        }
        return restored
    }

    fun getClosedSessionIds(): Set<String> = closedSessionIds.toSet()

    fun getConsumedCaptureHashes(): Set<String> = consumedCaptureHashes.toSet()

    /** Starts an isolated blank session when the merchant changes between supported Commerce templates. */
    fun selectTemplate(newTemplateId: String) {
        require(newTemplateId == "COMMERCE-T1" || newTemplateId == "COMMERCE-T3" || newTemplateId == "COMMERCE-T4") { "Gabarit Commerce non pris en charge: $newTemplateId" }
        if (templateId != newTemplateId) startNewBlankSession(newTemplateId)
    }

    fun currentSessionCaptureDir(): File =
        File(File(sessionsRootDir, creationId), "captures").apply { mkdirs() }

    fun allocateSceneCaptureFile(sceneId: String): File =
        File(currentSessionCaptureDir(), "${sceneId}_${System.currentTimeMillis()}.mp4")

    fun updateResponses(newResponses: Map<String, String>) {
        responses.clear()
        responses.putAll(newResponses)
        if (status == CreationSessionStatus.COMPLETED_CLEANED) {
            status = CreationSessionStatus.DRAFT
        }
        persistActiveCheckpoint()
    }

    fun onGuideVisited(sceneIndex: Int) {
        progression.markGuideVisited(sceneIndex)
    }

    fun onSceneRecordingCompleted(take: CapturedSceneTake) {
        if (take.sha256.lowercase() in consumedCaptureHashes) {
            throw RealSessionViolationException(
                "CONSUMED_CAPTURE_HASH_REUSED",
                "La capture ${take.sceneId} provient d'une création déjà clôturée."
            )
        }
        progression.onRecordingCompleted(take)
        status = CreationSessionStatus.RECORDING
    }

    fun acceptScene(sceneIndex: Int): CapturedSceneTake {
        val accepted = progression.acceptScene(sceneIndex)
        status = if (progression.isAllScenesValidated()) {
            CreationSessionStatus.READY_FOR_RENDER
        } else {
            CreationSessionStatus.RECORDING
        }
        persistActiveCheckpoint()
        return accepted
    }

    /**
     * Invalidates ONLY the target scene (`REFAIRE`) while preserving all other validated scenes,
     * `creation_id`, and merchant answers.
     */
    fun retakeScene(sceneIndex: Int) {
        val existingPath = progression.getReviewableTake(sceneIndex)?.filePath
        progression.retakeScene(sceneIndex)
        if (!existingPath.isNullOrBlank()) {
            val file = File(existingPath)
            val sessionDirCanonical = runCatching { File(sessionsRootDir, creationId).canonicalPath }.getOrNull()
            val fileCanonical = runCatching { file.canonicalPath }.getOrNull()
            if (sessionDirCanonical != null && fileCanonical != null && fileCanonical.startsWith(sessionDirCanonical)) {
                val stillReferenced = progression.allValidatedTakes().any { it.filePath == existingPath }
                if (!stillReferenced) {
                    file.delete()
                }
            }
        }
        status = CreationSessionStatus.RECORDING
        persistActiveCheckpoint()
    }

    fun buildVerifiedRenderInputs(pkg: LoadedTemplatePackage): VerifiedSessionRenderInputs {
        if (creationId in closedSessionIds) {
            throw RealSessionViolationException(
                "SESSION_ALREADY_CLOSED",
                "La session $creationId est déjà clôturée ; démarrez une nouvelle création."
            )
        }
        val takes = progression.allValidatedTakes()
        val (validatedResponses, descriptors) = RealSessionGuard.verifyAndBuildDescriptors(
            mode = executionMode,
            pkg = pkg,
            sessionId = creationId,
            responses = responses,
            takes = takes
        )

        val expectedSessionDir = runCatching { currentSessionCaptureDir().canonicalPath }.getOrNull()
        for (take in takes) {
            val takeCanonical = runCatching { File(take.filePath).canonicalPath }.getOrNull()
            if (expectedSessionDir != null && takeCanonical != null && !takeCanonical.startsWith(expectedSessionDir)) {
                throw RealSessionViolationException(
                    "CROSS_SESSION_FILE_OWNERSHIP_VIOLATION",
                    "La scène ${take.sceneId} n'appartient pas au dossier de la session courante ($creationId)."
                )
            }
            if (take.sha256.lowercase() in consumedCaptureHashes) {
                throw RealSessionViolationException(
                    "OLD_SESSION_HASH_REUSED",
                    "La capture ${take.sceneId} provient d'une création déjà terminée."
                )
            }
        }

        status = CreationSessionStatus.RENDERING
        return VerifiedSessionRenderInputs(
            creationId = creationId,
            responses = validatedResponses,
            descriptors = descriptors,
            rejectedPreviousSessionIds = closedSessionIds.toSet(),
            rejectedPreviousCaptureSha256s = consumedCaptureHashes.toSet()
        )
    }

    /**
     * Preserves the current session (`creation_id`, S1..S4 clips, validated states, and merchant answers)
     * when a render fails so the user can retry immediately or after reopening the app.
     */
    fun onRenderFailed() {
        status = CreationSessionStatus.FAILED_PRESERVED
        persistActiveCheckpoint()
    }

    /**
     * Called ONLY after Media3 succeeds, PostRenderVerifier validates the final MP4,
     * and permanent export/publication succeeds.
     *
     * Closes `creationId`, deletes ONLY temporary S1..S4 clips & active checkpoint,
     * records consumed IDs/hashes, resets S1..S4 & answers, and prepares a fresh blank session.
     * NEVER touches `finalMp4File` or public MediaStore copies.
     */
    fun onRenderSucceededAndVerified(finalMp4File: File, publishedLocation: String): String {
        require(finalMp4File.exists() && finalMp4File.isFile && finalMp4File.length() > 0L) {
            "Le MP4 final vérifié doit exister avant la clôture de session: ${finalMp4File.path}"
        }
        require(publishedLocation.isNotBlank()) {
            "La sauvegarde permanente du MP4 final doit être confirmée avant la clôture de session."
        }

        val completedId = creationId
        closedSessionIds += completedId
        for (take in progression.allValidatedTakes()) {
            if (take.sha256.isNotBlank()) {
                consumedCaptureHashes += take.sha256.lowercase()
            }
        }
        trimAndSaveClosedRegistry()

        // Delete ONLY temporary scene captures and work state for completedId
        File(sessionsRootDir, completedId).deleteRecursively()
        File(legacyCapturesRootDir, completedId).deleteRecursively()
        activeCheckpointFile.delete()
        legacyCheckpointFile.delete()

        // Reset state cleanly and prepare a brand-new blank session
        progression.clearAll()
        responses.clear()
        creationId = UUID.randomUUID().toString()
        status = CreationSessionStatus.DRAFT
        return completedId
    }

    /**
     * Explicitly abandons the current draft (e.g., user cancels or starts over) and starts a blank session.
     */
    fun startNewBlankSession(newTemplateId: String = templateId) {
        val previousId = creationId
        File(sessionsRootDir, previousId).deleteRecursively()
        File(legacyCapturesRootDir, previousId).deleteRecursively()
        activeCheckpointFile.delete()
        legacyCheckpointFile.delete()

        progression.clearAll()
        responses.clear()
        templateId = newTemplateId
        creationId = UUID.randomUUID().toString()
        status = CreationSessionStatus.DRAFT
    }

    fun persistActiveCheckpoint() {
        if (status == CreationSessionStatus.COMPLETED_CLEANED) {
            activeCheckpointFile.delete()
            return
        }
        runCatching {
            checkpointsDir.mkdirs()
            val mappedState = when (status) {
                CreationSessionStatus.DRAFT -> CaptureSessionState.RECORDING
                CreationSessionStatus.RECORDING -> CaptureSessionState.RECORDING
                CreationSessionStatus.READY_FOR_RENDER,
                CreationSessionStatus.RENDERING -> CaptureSessionState.READY_FOR_RENDER
                CreationSessionStatus.FAILED_PRESERVED -> CaptureSessionState.FAILED
                CreationSessionStatus.COMPLETED_CLEANED -> CaptureSessionState.RENDERED
            }
            val root = JSONObject().apply {
                put("creation_id", creationId)
                put("template_id", templateId)
                put("status", status.name)
                put("session_state", mappedState.name)
                put("responses", JSONObject().apply {
                    for ((k, v) in responses) put(k, v)
                })
                put("captures", JSONArray().apply {
                    for (t in progression.allValidatedTakes()) {
                        put(JSONObject().apply {
                            put("scene_index", t.sceneIndex)
                            put("scene_id", t.sceneId)
                            put("file_path", t.filePath)
                            put("duration_us", t.durationUs)
                            put("width", t.width)
                            put("height", t.height)
                            put("rotation", t.rotation)
                            put("audio_present", t.audioPresent)
                            put("sha256", t.sha256)
                            put("source", t.source.name)
                            put("validated_by_merchant", t.validatedByMerchant)
                            put("recorded_at_epoch_ms", t.recordedAtEpochMs)
                        })
                    }
                })
            }
            activeCheckpointFile.writeText(root.toString(2))
        }
    }

    private fun tryRestoreUnfinishedCheckpoint(): Boolean {
        if (!activeCheckpointFile.exists()) return false
        return runCatching {
            val obj = JSONObject(activeCheckpointFile.readText())
            val savedId = obj.optString("creation_id", "").trim()
            val savedStatus = obj.optString("status", "")
            val legacyState = obj.optString("session_state", "")

            if (savedId.isBlank() ||
                savedId in closedSessionIds ||
                savedStatus == CreationSessionStatus.COMPLETED_CLEANED.name ||
                legacyState == CaptureSessionState.RENDERED.name
            ) {
                activeCheckpointFile.delete()
                return false
            }

            creationId = savedId
            templateId = obj.optString("template_id", "COMMERCE-T3")
            status = runCatching { CreationSessionStatus.valueOf(savedStatus) }
                .getOrDefault(CreationSessionStatus.DRAFT)

            responses.clear()
            val respObj = obj.optJSONObject("responses")
            if (respObj != null) {
                val keys = respObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    responses[k] = respObj.optString(k, "")
                }
            }

            progression.clearAll()
            val expectedSessionDir = runCatching { currentSessionCaptureDir().canonicalPath }.getOrNull()
            val caps = obj.optJSONArray("captures")
            if (caps != null) {
                for (i in 0 until caps.length()) {
                    val c = caps.getJSONObject(i)
                    val path = c.optString("file_path", "")
                    val f = File(path)
                    val pathCanonical = runCatching { f.canonicalPath }.getOrNull()
                    val ownedBySession = expectedSessionDir != null && pathCanonical != null &&
                        pathCanonical.startsWith(expectedSessionDir)
                    val sourceName = c.optString("source", CaptureSource.CAMERAX_REAL.name)
                    val source = runCatching { CaptureSource.valueOf(sourceName) }.getOrNull()
                    if (ownedBySession &&
                        source == CaptureSource.CAMERAX_REAL &&
                        f.exists() &&
                        f.length() > 0L
                    ) {
                        val take = CapturedSceneTake(
                            sceneIndex = c.getInt("scene_index"),
                            sceneId = c.getString("scene_id"),
                            filePath = path,
                            durationUs = c.optLong("duration_us", 0L),
                            width = c.optInt("width", 0),
                            height = c.optInt("height", 0),
                            rotation = c.optInt("rotation", 0),
                            audioPresent = c.optBoolean("audio_present", false),
                            sha256 = c.optString("sha256", ""),
                            source = source,
                            validatedByMerchant = c.optBoolean("validated_by_merchant", false),
                            recordedAtEpochMs = c.optLong("recorded_at_epoch_ms", 0L)
                        )
                        if (take.sha256.lowercase() !in consumedCaptureHashes) {
                            progression.restoreValidatedTake(take)
                        }
                    }
                }
            }

            responses.isNotEmpty() || progression.allValidatedTakes().isNotEmpty()
        }.getOrElse {
            activeCheckpointFile.delete()
            false
        }
    }

    private fun sweepOrphanedTempSessions(keepSessionId: String?) {
        if (sessionsRootDir.exists()) {
            sessionsRootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != keepSessionId) {
                    dir.deleteRecursively()
                }
            }
        }
        // Clean legacy flat captures folder from pre-D47 builds
        if (legacyCapturesRootDir.exists()) {
            legacyCapturesRootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != keepSessionId) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun loadClosedRegistry() {
        closedSessionIds.clear()
        consumedCaptureHashes.clear()
        if (!closedSessionsFile.exists()) return
        runCatching {
            val obj = JSONObject(closedSessionsFile.readText())
            val ids = obj.optJSONArray("closed_session_ids")
            if (ids != null) {
                for (i in 0 until ids.length()) {
                    val v = ids.optString(i, "")
                    if (v.isNotBlank()) closedSessionIds += v
                }
            }
            val hashes = obj.optJSONArray("consumed_capture_sha256s")
            if (hashes != null) {
                for (i in 0 until hashes.length()) {
                    val v = hashes.optString(i, "").lowercase()
                    if (v.isNotBlank()) consumedCaptureHashes += v
                }
            }
        }
    }

    private fun trimAndSaveClosedRegistry() {
        while (closedSessionIds.size > 100) {
            val first = closedSessionIds.firstOrNull() ?: break
            closedSessionIds.remove(first)
        }
        while (consumedCaptureHashes.size > 400) {
            val first = consumedCaptureHashes.firstOrNull() ?: break
            consumedCaptureHashes.remove(first)
        }
        runCatching {
            checkpointsDir.mkdirs()
            val obj = JSONObject().apply {
                put("closed_session_ids", JSONArray(closedSessionIds.toList()))
                put("consumed_capture_sha256s", JSONArray(consumedCaptureHashes.toList()))
            }
            closedSessionsFile.writeText(obj.toString(2))
        }
    }
}
