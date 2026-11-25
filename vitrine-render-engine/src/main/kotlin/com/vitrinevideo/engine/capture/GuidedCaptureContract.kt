package com.vitrinevideo.engine.capture

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.StatFs
import com.vitrinevideo.engine.api.CaptureClip
import com.vitrinevideo.engine.api.EngineConfig
import com.vitrinevideo.engine.api.RenderRequest
import com.vitrinevideo.engine.interpreter.AudioProfileSpec
import com.vitrinevideo.engine.interpreter.RenderPlan
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class CaptureSessionState {
    RECORDING,
    READY_FOR_RENDER,
    INTERRUPTED,
    RENDERED,
    FAILED
}

enum class SceneRecordingStatus {
    NOT_STARTED,
    RECORDING,
    COMPLETED,
    INTERRUPTED,
    FAILED
}

data class CapturePermissionsSnapshot(
    val cameraGranted: Boolean = true,
    val microphoneGranted: Boolean = true
)

/**
 * Stable per-scene capture descriptor emitted by Ensemble 1 (in-app CameraX guided recorder)
 * and consumed by Ensemble 3 (VitrineRenderEngine). No manual gallery/file picker allowed.
 */
data class SceneCaptureDescriptor(
    val sessionId: String,
    val templateId: String,
    val templateVersion: String,
    val sceneId: String,
    val localVideoPath: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val audioPresent: Boolean,
    val sha256: String = "",
    val recordingStatus: SceneRecordingStatus = SceneRecordingStatus.COMPLETED,
    val validatedByMerchant: Boolean = true,
    val recordedAtEpochMs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("session_id", sessionId)
        put("template_id", templateId)
        put("template_version", templateVersion)
        put("scene_id", sceneId)
        put("local_video_path", localVideoPath)
        put("duration_us", durationUs)
        put("width", width)
        put("height", height)
        put("rotation", rotation)
        put("audio_present", audioPresent)
        if (sha256.isNotEmpty()) put("sha256", sha256)
        put("recording_status", recordingStatus.name)
        put("validated_by_merchant", validatedByMerchant)
        put("recorded_at_epoch_ms", recordedAtEpochMs)
    }

    companion object {
        fun fromJson(json: JSONObject): SceneCaptureDescriptor = SceneCaptureDescriptor(
            sessionId = json.getString("session_id"),
            templateId = json.getString("template_id"),
            templateVersion = json.optString("template_version", "1.0.0"),
            sceneId = json.getString("scene_id"),
            localVideoPath = json.getString("local_video_path"),
            durationUs = json.getLong("duration_us"),
            width = json.getInt("width"),
            height = json.getInt("height"),
            rotation = json.optInt("rotation", 0),
            audioPresent = json.optBoolean("audio_present", false),
            sha256 = json.optString("sha256", ""),
            recordingStatus = runCatching {
                SceneRecordingStatus.valueOf(json.optString("recording_status", "COMPLETED"))
            }.getOrDefault(SceneRecordingStatus.COMPLETED),
            validatedByMerchant = json.optBoolean("validated_by_merchant", true),
            recordedAtEpochMs = json.optLong("recorded_at_epoch_ms", 0L)
        )
    }
}

/**
 * Full guided capture session handed from Ensemble 1 to Ensemble 3 via Ensemble 2 contract.
 */
data class GuidedCaptureSession(
    val sessionId: String,
    val templateId: String,
    val templateVersion: String,
    val partitionJsonFile: File,
    val audioProfileJsonFile: File?,
    val resourcesDir: File,
    val outputDir: File,
    val rotationDbFile: File,
    val manifestJsonFile: File? = null,
    val responses: Map<String, String>,
    val captures: List<SceneCaptureDescriptor>,
    val sessionState: CaptureSessionState = CaptureSessionState.READY_FOR_RENDER,
    val permissions: CapturePermissionsSnapshot = CapturePermissionsSnapshot(),
    val visitedScreens: List<Int> = emptyList(),
    val expectedScreenCount: Int? = null,
    val rejectedPreviousSessionIds: Set<String> = emptySet(),
    val rejectedPreviousCaptureSha256s: Set<String> = emptySet(),
    val preparsedPlan: RenderPlan? = null,
    val preparsedAudioProfile: AudioProfileSpec? = null
)

/**
 * Scene guide specification parsed from template `capture-guide/ecrans.json` or `T3.json`
 * so Ensemble 1 displays exact instructions, duration and audio/visual cues without hardcoding.
 */
data class SceneCaptureGuideSpec(
    val sceneNumber: Int,
    val sceneId: String,
    val durationSec: Int,
    val title: String,
    val onScreenPrompt: String,
    val whatToFilm: String,
    val thingsToFilm: List<String>,
    val thingsToAvoid: List<String>,
    val guideImageRelPath: String? = null,
    val guideAudioRelPath: String? = null,
    val microphoneActive: Boolean = true,
    val keptDurationSec: Int = 0,
    val filmingInstruction: String = "",
    val spokenGuidance: String = "",
    val exampleSpeech: String = "",
    val guideScreenNumber: Int? = null,
    val recordScreenNumber: Int? = null,
    val reviewScreenNumber: Int? = null,
    val reviewInstruction: String = "",
    val reviewButtonLabel: String = ""
)

object GuidedCaptureGuideParser {
    fun parseFromEcransJson(rawJson: String): List<SceneCaptureGuideSpec> {
        val trimmed = rawJson.trimStart()
        if (trimmed.startsWith("[")) {
            val screens = JSONArray(rawJson)
            val byScene = linkedMapOf<String, MutableList<JSONObject>>()
            for (i in 0 until screens.length()) {
                val item = screens.getJSONObject(i)
                val id = item.optString("scene", "")
                if (id.matches(Regex("S[1-4]"))) byScene.getOrPut(id) { mutableListOf() }.add(item)
            }
            if (byScene.isNotEmpty()) return byScene.entries.sortedBy { it.key.drop(1).toInt() }.map { (id, states) ->
                val guide = states.firstOrNull { it.optString("etat") == "guide" } ?: JSONObject()
                val recording = states.firstOrNull { it.optString("etat") == "enregistrement" } ?: JSONObject()
                val review = states.firstOrNull { it.optString("etat") == "verification" } ?: JSONObject()
                val n = id.drop(1).toInt()
                val blocks = guide.optJSONObject("blocs") ?: JSONObject()
                val list = { key: String -> blocks.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
                SceneCaptureGuideSpec(
                    sceneNumber = n, sceneId = id,
                    durationSec = guide.optInt("duree_s", recording.optInt("duree_s", 0)),
                    title = guide.optString("titre", ""),
                    onScreenPrompt = recording.optString("indication", guide.optString("titre", "")),
                    whatToFilm = blocks.optString("CE QUE TU VAS FILMER", ""),
                    thingsToFilm = list("À FILMER"), thingsToAvoid = list("À ÉVITER"),
                    microphoneActive = true,
                    filmingInstruction = blocks.optString("CE QUE TU DOIS FAIRE", ""),
                    spokenGuidance = guide.optString("parole", ""),
                    exampleSpeech = guide.optString("exemple", ""),
                    guideScreenNumber = guide.optInt("numero"),
                    recordScreenNumber = recording.optInt("numero"),
                    reviewScreenNumber = review.optInt("numero"),
                    reviewInstruction = review.optString("consigne", ""),
                    reviewButtonLabel = review.optString("bouton", "")
                )
            }
        }
        val root = JSONObject(rawJson)
        root.optJSONArray("scenes")?.let { scenes ->
            return (0 until scenes.length()).mapIndexed { index, i ->
                val scene = scenes.getJSONObject(i)
                val number = scene.optInt("id", index + 1)
                val needed = scene.optJSONArray("needed")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val avoid = scene.optJSONArray("avoid")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val capture = scene.optInt("capture", scene.optInt("captureDurationSec", scene.optInt("capture_s", 0)))
                val kept = scene.optInt("kept", scene.optInt("keptDurationSec", scene.optInt("kept_s", 0)))
                SceneCaptureGuideSpec(
                    sceneNumber = number, sceneId = "S$number", durationSec = capture,
                    title = scene.optString("title", "SCÈNE $number"),
                    onScreenPrompt = scene.optString("instruction", scene.optString("action", "")),
                    whatToFilm = scene.optString("action", scene.optString("instruction", "")),
                    thingsToFilm = needed, thingsToAvoid = avoid,
                    guideImageRelPath = scene.optString("image", "").ifBlank { null },
                    guideAudioRelPath = scene.optString("audio", "").ifBlank { null },
                    microphoneActive = true, keptDurationSec = kept
                )
            }
        }
        val arr = root.optJSONArray("ecrans") ?: root.optJSONArray("ecrans_capture") ?: return emptyList()
        val byScene = linkedMapOf<Int, MutableMap<String, JSONObject>>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val scNum = obj.optInt("scene", -1)
            val etat = obj.optString("etat", "")
            if (scNum > 0) {
                byScene.getOrPut(scNum) { mutableMapOf() }[etat] = obj
            }
        }
        return byScene.entries.sortedBy { it.key }.map { (scNum, states) ->
            val stateA = states["A"] ?: states["guide"] ?: JSONObject()
            val stateB = states["B"] ?: states["capture"] ?: stateA
            val blocsObj = stateA.optJSONObject("blocs")
            val blocsArr = stateA.optJSONArray("blocs")

            var whatToFilm = ""
            val toFilm = mutableListOf<String>()
            val toAvoid = mutableListOf<String>()

            if (blocsObj != null) {
                whatToFilm = blocsObj.optString("CE QUE TU VAS FILMER", "")
                blocsObj.optJSONArray("À FILMER")?.let { a ->
                    for (j in 0 until a.length()) toFilm += a.getString(j)
                }
                blocsObj.optJSONArray("À ÉVITER")?.let { a ->
                    for (j in 0 until a.length()) toAvoid += a.getString(j)
                }
            } else if (blocsArr != null) {
                for (j in 0 until blocsArr.length()) {
                    val b = blocsArr.getJSONObject(j)
                    val lib = b.optString("libelle", "")
                    val txt = b.optJSONArray("texte") ?: continue
                    val lines = (0 until txt.length()).map { txt.getString(it) }
                    when {
                        lib.contains("VAS FILMER", ignoreCase = true) -> whatToFilm = lines.joinToString(" ")
                        lib.contains("À FILMER", ignoreCase = true) -> toFilm += lines
                        lib.contains("À ÉVITER", ignoreCase = true) -> toAvoid += lines
                    }
                }
            }

            val durSec = stateB.optInt(
                "arret_automatique_s",
                stateB.optInt(
                    "duree_s",
                    stateB.optInt(
                        "duree_proposee_s",
                        stateA.optInt("duree_s", stateA.optInt("duree_proposee_s", 6))
                    )
                )
            )
            SceneCaptureGuideSpec(
                sceneNumber = scNum,
                sceneId = "S$scNum",
                durationSec = durSec,
                title = stateA.optString("titre", "SCÈNE $scNum"),
                onScreenPrompt = stateB.optString("consigne", whatToFilm),
                whatToFilm = whatToFilm,
                thingsToFilm = toFilm,
                thingsToAvoid = toAvoid,
                guideImageRelPath = stateA.optString("image_guide", "").takeIf { it.isNotEmpty() },
                guideAudioRelPath = stateA.optString("audio_guide", "").takeIf { it.isNotEmpty() },
                microphoneActive = true,
                guideScreenNumber = stateA.optInt("numero").takeIf { it > 0 },
                recordScreenNumber = stateB.optInt("numero").takeIf { it > 0 },
                reviewScreenNumber = states["C"]?.optInt("numero")?.takeIf { it > 0 }
            )
        }
    }
}

/**
 * Atomic JSON checkpoint store for in-progress capture sessions so an interrupted
 * recording flow (app killed / phone call) resumes cleanly at the next uncompleted scene.
 */
object CaptureSessionCheckpointStore {

    fun saveCheckpoint(
        checkpointFile: File,
        sessionId: String,
        templateId: String,
        templateVersion: String,
        state: CaptureSessionState,
        captures: List<SceneCaptureDescriptor>,
        responses: Map<String, String>
    ) {
        checkpointFile.parentFile?.mkdirs()
        val obj = JSONObject().apply {
            put("session_id", sessionId)
            put("template_id", templateId)
            put("template_version", templateVersion)
            put("session_state", state.name)
            put("responses", JSONObject(responses))
            put("captures", JSONArray().apply { captures.forEach { put(it.toJson()) } })
        }
        val tmp = File(checkpointFile.parentFile, "${checkpointFile.name}.tmp")
        tmp.writeText(obj.toString(2))
        if (checkpointFile.exists()) checkpointFile.delete()
        if (!tmp.renameTo(checkpointFile)) {
            tmp.copyTo(checkpointFile, overwrite = true)
            tmp.delete()
        }
    }

    fun loadResumeState(checkpointFile: File, expectedSceneIds: List<String>): ResumeSnapshot? {
        if (!checkpointFile.exists()) return null
        val json = runCatching { JSONObject(checkpointFile.readText()) }.getOrNull() ?: return null
        val capsArr = json.optJSONArray("captures") ?: JSONArray()
        val captures = (0 until capsArr.length()).mapNotNull { idx ->
            runCatching { SceneCaptureDescriptor.fromJson(capsArr.getJSONObject(idx)) }.getOrNull()
        }
        val validCompletedIds = captures
            .filter { it.recordingStatus == SceneRecordingStatus.COMPLETED && File(it.localVideoPath).let { f -> f.exists() && f.length() > 0L } }
            .map { it.sceneId }
            .toSet()
        val nextMissingScene = expectedSceneIds.firstOrNull { it !in validCompletedIds }
        val respMap = mutableMapOf<String, String>()
        json.optJSONObject("responses")?.let { r ->
            val keys = r.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                respMap[k] = r.optString(k, "")
            }
        }
        return ResumeSnapshot(
            sessionId = json.getString("session_id"),
            templateId = json.getString("template_id"),
            templateVersion = json.optString("template_version", "1.0.0"),
            state = runCatching { CaptureSessionState.valueOf(json.optString("session_state", "INTERRUPTED")) }
                .getOrDefault(CaptureSessionState.INTERRUPTED),
            completedCaptures = captures.filter { it.sceneId in validCompletedIds },
            nextSceneToCapture = nextMissingScene,
            responses = respMap
        )
    }

    data class ResumeSnapshot(
        val sessionId: String,
        val templateId: String,
        val templateVersion: String,
        val state: CaptureSessionState,
        val completedCaptures: List<SceneCaptureDescriptor>,
        val nextSceneToCapture: String?,
        val responses: Map<String, String>
    )
}

data class ProbedMediaMetadata(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean
)

fun interface VideoMetadataProbe {
    fun probe(file: File): ProbedMediaMetadata?
}

object AndroidMediaExtractorProbe : VideoMetadataProbe {
    override fun probe(file: File): ProbedMediaMetadata? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var width = 0
            var height = 0
            var rotation = 0
            var durUs = 0L
            var hasVideo = false
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    width = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = fmt.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                }
            }
            ProbedMediaMetadata(width, height, rotation, durUs, hasVideo, hasAudio)
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }
}

class GuidedCaptureValidationException(
    val code: String,
    message: String,
    val recoverableSceneIds: List<String> = emptyList()
) : IllegalStateException(message)

object GuidedCaptureValidator {

    fun validateAndMap(
        session: GuidedCaptureSession,
        config: EngineConfig = EngineConfig(),
        probe: VideoMetadataProbe = AndroidMediaExtractorProbe
    ): Pair<RenderPlan, RenderRequest> {
        if (!session.permissions.cameraGranted) {
            throw GuidedCaptureValidationException(
                "CAMERA_PERMISSION_DENIED",
                "Permission caméra refusée : impossible d'enregistrer ou de valider les scènes."
            )
        }
        if (!session.permissions.microphoneGranted) {
            throw GuidedCaptureValidationException(
                "MICROPHONE_PERMISSION_DENIED",
                "Permission microphone refusée : l'audio des prises ne peut pas être capturé."
            )
        }
        if (session.sessionState == CaptureSessionState.INTERRUPTED) {
            val doneIds = session.captures
                .filter { it.recordingStatus == SceneRecordingStatus.COMPLETED }
                .map { it.sceneId }
            throw GuidedCaptureValidationException(
                "SESSION_INTERRUPTED",
                "Session de capture interrompue ; reprise requise à partir des scènes manquantes.",
                recoverableSceneIds = doneIds
            )
        }

        val plan = session.preparsedPlan ?: UniversalJsonInterpreter.parsePartition(session.partitionJsonFile)
        if (session.templateId != plan.templateId) {
            throw GuidedCaptureValidationException(
                "TEMPLATE_MISMATCH",
                "Gabarit session (${session.templateId}) != partition (${plan.templateId})"
            )
        }

        // Check storage before checking/hashing media
        session.outputDir.mkdirs()
        val statBytes = runCatching { StatFs(session.outputDir.absolutePath).availableBytes }.getOrDefault(0L)
        val freeBytes = if (statBytes > 0L) statBytes else session.outputDir.usableSpace
        if (freeBytes < config.minFreeStorageBytes) {
            throw GuidedCaptureValidationException(
                "INSUFFICIENT_STORAGE",
                "Espace de stockage insuffisant (${freeBytes / (1024 * 1024)} Mo libres < ${config.minFreeStorageBytes / (1024 * 1024)} Mo)"
            )
        }

        val bySceneId = session.captures.associateBy { it.sceneId }
        val captureClips = mutableListOf<CaptureClip>()

        for (scene in plan.scenes) {
            val desc = bySceneId[scene.id]
                ?: throw GuidedCaptureValidationException(
                    "SCENE_MISSING",
                    "Scène manquante dans la session : ${scene.id}"
                )
            if (desc.sessionId != session.sessionId) {
                throw GuidedCaptureValidationException(
                    "SESSION_ID_MISMATCH",
                    "Capture ${scene.id} appartient à une autre session (${desc.sessionId})"
                )
            }
            if (desc.recordingStatus != SceneRecordingStatus.COMPLETED) {
                throw GuidedCaptureValidationException(
                    "RECORDING_INTERRUPTED",
                    "Enregistrement non terminé pour ${scene.id} (état=${desc.recordingStatus})"
                )
            }
            val file = File(desc.localVideoPath)
            if (!file.exists() || file.length() == 0L) {
                throw GuidedCaptureValidationException(
                    "CORRUPT_FILE",
                    "Fichier vidéo absent ou vide pour ${scene.id}: ${desc.localVideoPath}"
                )
            }
            val probed = probe.probe(file)
                ?: throw GuidedCaptureValidationException(
                    "CORRUPT_FILE",
                    "Fichier vidéo illisible ou conteneur MP4 corrompu pour ${scene.id}"
                )
            if (!probed.hasVideo || probed.width <= 0 || probed.height <= 0) {
                throw GuidedCaptureValidationException(
                    "CORRUPT_FILE",
                    "Aucune piste vidéo exploitable dans ${scene.id}"
                )
            }
            val requiredSourceFrames = scene.shots.maxOfOrNull { it.sourceOutFrame }
                ?: (scene.endFrameExcluded - scene.startFrame)
            val requiredDurationUs = (requiredSourceFrames.toLong() * 1_000_000L) / plan.fps
            val allowFreeze = scene.shots.any { it.freezeTailFrames > 0 }
            val effectiveDurUs = if (probed.durationUs > 0L) probed.durationUs else desc.durationUs
            if (!allowFreeze && effectiveDurUs + 150_000L < requiredDurationUs) {
                throw GuidedCaptureValidationException(
                    "INSUFFICIENT_DURATION",
                    "Durée insuffisante pour ${scene.id} (${effectiveDurUs / 1000} ms < ${requiredDurationUs / 1000} ms)"
                )
            }

            val resolvedSha = if (desc.sha256.isNotBlank()) desc.sha256 else PreFlightValidator.sha256File(file)
            captureClips += CaptureClip(
                sceneId = scene.id,
                file = file,
                expectedSha256 = resolvedSha,
                validated = desc.validatedByMerchant,
                sessionId = desc.sessionId
            )
        }

        val request = RenderRequest(
            templateId = session.templateId,
            templateVersion = session.templateVersion,
            partitionJsonFile = session.partitionJsonFile,
            audioProfileJsonFile = session.audioProfileJsonFile,
            resourcesDir = session.resourcesDir,
            responses = session.responses,
            captures = captureClips,
            visitedScreens = session.visitedScreens,
            expectedScreenCount = session.expectedScreenCount,
            outputDir = session.outputDir,
            rotationDbFile = session.rotationDbFile,
            manifestJsonFile = session.manifestJsonFile,
            sessionId = session.sessionId,
            rejectedPreviousSessionIds = session.rejectedPreviousSessionIds,
            rejectedPreviousCaptureSha256s = session.rejectedPreviousCaptureSha256s,
            preparsedPlan = session.preparsedPlan,
            preparsedAudioProfile = session.preparsedAudioProfile
        )
        return plan to request
    }
}
