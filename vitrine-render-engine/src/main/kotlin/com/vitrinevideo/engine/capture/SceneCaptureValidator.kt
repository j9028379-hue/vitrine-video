package com.vitrinevideo.engine.capture

import com.vitrinevideo.engine.interpreter.SceneSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One source of truth for scene capture readiness.
 *
 * `CutSegmentSpec.sourceInFrame/sourceOutFrame` are offsets INSIDE the merchant's
 * own video file. They must never be read as absolute positions in the final
 * 900-frame timeline; doing so made the 8 s S2 capture look like a 14 s file.
 */
object CaptureDurationPolicy {
    /** Codec finalisation may leave a few frames short of the target; one frame at 24 fps. */
    const val DURATION_TOLERANCE_US = 150_000L

    fun requiredSourceFrames(scene: SceneSpec): Int =
        scene.shots.maxOfOrNull { max(0, it.sourceOutFrame - it.sourceInFrame) }
            ?: (scene.endFrameExcluded - scene.startFrame)

    fun requiredDurationUs(scene: SceneSpec, fps: Int): Long =
        (requiredSourceFrames(scene).toLong() * 1_000_000L) / fps.coerceAtLeast(1)

    fun allowsFrozenTail(scene: SceneSpec): Boolean = scene.shots.any { it.freezeTailFrames > 0 }
}

data class SceneCaptureValidationSnapshot(
    val sceneId: String,
    val filePath: String,
    val fileExists: Boolean,
    val fileReadable: Boolean,
    val fileNonEmpty: Boolean,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hasVideoStream: Boolean,
    val audioPresent: Boolean,
    val sha256: String,
    /** null when the caller has no real/synthetic provenance information. */
    val fromRealCamera: Boolean? = null,
    val metadataUsable: Boolean = true
)

data class SceneCaptureValidationError(val code: String, val message: String)

/**
 * Validates the same contract at camera finalisation, merchant acceptance and
 * render pre-flight. Silent captures are valid: T2 explicitly allows the
 * merchant to say nothing; an audio track is used only when contract says so.
 */
object SceneCaptureValidator {
    private const val MIN_SHORT_SIDE_PX = 480
    private const val MIN_LONG_SIDE_PX = 720
    private const val MIN_PORTRAIT_RATIO = 0.52
    private const val MAX_PORTRAIT_RATIO = 0.60

    fun validate(
        snapshot: SceneCaptureValidationSnapshot,
        requiredDurationUs: Long,
        allowFrozenTail: Boolean = false
    ): SceneCaptureValidationError? {
        if (!snapshot.fileExists || !snapshot.fileReadable || !snapshot.fileNonEmpty) {
            return SceneCaptureValidationError(
                "CAPTURE_FILE_UNAVAILABLE",
                "Vidéo introuvable, vide ou illisible pour ${snapshot.sceneId}."
            )
        }
        snapshot.fromRealCamera?.let { real ->
            if (!real) {
                return SceneCaptureValidationError(
                    "SYNTHETIC_SCENE_REJECTED",
                    "La scène ${snapshot.sceneId} ne provient pas de la caméra réelle."
                )
            }
        }
        if (snapshot.sha256.isBlank()) {
            return SceneCaptureValidationError(
                "INVALID_CAPTURE_HASH",
                "Empreinte SHA-256 manquante pour ${snapshot.sceneId}."
            )
        }
        if (!snapshot.metadataUsable || !snapshot.hasVideoStream) {
            return SceneCaptureValidationError(
                "INVALID_VIDEO_STREAM",
                "Aucune piste vidéo exploitable dans ${snapshot.sceneId}."
            )
        }
        if (snapshot.durationUs <= 0L) {
            return SceneCaptureValidationError(
                "INVALID_CAPTURE_TIMESTAMPS",
                "Horodatages vidéo invalides pour ${snapshot.sceneId}."
            )
        }
        val shortSide = min(snapshot.width, snapshot.height)
        val longSide = max(snapshot.width, snapshot.height)
        val ratio = if (longSide > 0) shortSide.toDouble() / longSide else 0.0
        if (shortSide < MIN_SHORT_SIDE_PX || longSide < MIN_LONG_SIDE_PX ||
            ratio < MIN_PORTRAIT_RATIO || ratio > MAX_PORTRAIT_RATIO ||
            snapshot.rotation % 90 != 0
        ) {
            return SceneCaptureValidationError(
                "INVALID_CAPTURE_GEOMETRY",
                "Résolution ou orientation inacceptables pour ${snapshot.sceneId} " +
                    "(${snapshot.width}x${snapshot.height}, rotation ${snapshot.rotation})."
            )
        }
        if (!allowFrozenTail && snapshot.durationUs + CaptureDurationPolicy.DURATION_TOLERANCE_US < requiredDurationUs) {
            return SceneCaptureValidationError(
                "CAPTURE_DURATION_TOO_SHORT",
                "La scène ${snapshot.sceneId} dure ${snapshot.durationUs / 1000} ms " +
                    "(< ${requiredDurationUs / 1000} ms requis)."
            )
        }
        return null
    }

    fun requireValid(
        snapshot: SceneCaptureValidationSnapshot,
        requiredDurationUs: Long,
        allowFrozenTail: Boolean = false
    ) {
        validate(snapshot, requiredDurationUs, allowFrozenTail)?.let {
            throw SceneCaptureValidationException(it.code, it.message)
        }
    }
}

class SceneCaptureValidationException(
    val code: String,
    message: String
) : IllegalStateException(message)
