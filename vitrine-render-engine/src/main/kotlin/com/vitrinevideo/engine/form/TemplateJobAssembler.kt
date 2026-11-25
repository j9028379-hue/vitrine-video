package com.vitrinevideo.engine.form

import com.vitrinevideo.engine.api.CaptureClip
import com.vitrinevideo.engine.api.RenderRequest
import com.vitrinevideo.engine.capture.CapturePermissionsSnapshot
import com.vitrinevideo.engine.capture.CaptureSessionState
import com.vitrinevideo.engine.capture.GuidedCaptureSession
import com.vitrinevideo.engine.capture.SceneCaptureDescriptor
import com.vitrinevideo.engine.capture.SceneRecordingStatus
import com.vitrinevideo.engine.pkg.LoadedTemplatePackage
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AssembledTemplateJob(
    val jobJson: JSONObject,
    val guidedSession: GuidedCaptureSession,
    val renderRequest: RenderRequest
)

/**
 * Automatically assembles and validates a `RenderJob` (`schemas/render-job.json`) from:
 * - a verified [LoadedTemplatePackage],
 * - exact merchant form responses (never invented or altered),
 * - and real in-app CameraX captures (`SceneCaptureDescriptor` list).
 */
object TemplateJobAssembler {

    fun assemble(
        pkg: LoadedTemplatePackage,
        sessionId: String,
        rawResponses: Map<String, String>,
        captures: List<SceneCaptureDescriptor>,
        outputDir: File,
        rotationDbFile: File,
        visitedScreens: List<Int> = emptyList(),
        permissions: CapturePermissionsSnapshot = CapturePermissionsSnapshot(),
        rejectedPreviousSessionIds: Set<String> = emptySet(),
        rejectedPreviousCaptureSha256s: Set<String> = emptySet()
    ): AssembledTemplateJob {
        if (sessionId.isBlank()) {
            throw TemplateFormValidationException(
                "MISSING_SESSION_ID",
                "Un creation_id / session_id valide est obligatoire."
            )
        }
        val validatedResponses = validateResponses(pkg, rawResponses)
        val validatedCaptures = validateCaptures(pkg, captures, expectedSessionId = sessionId)
        val effectiveScreens = when {
            visitedScreens.isNotEmpty() -> visitedScreens
            !pkg.jobSchemaConstraints?.expectedScreenNumbers.isNullOrEmpty() ->
                pkg.jobSchemaConstraints!!.expectedScreenNumbers
            pkg.captureGuides.isNotEmpty() -> (1..(pkg.captureGuides.size * 3)).toList()
            else -> emptyList()
        }

        val jobJson = JSONObject().apply {
            put("template_id", pkg.templateId)
            put("template_version", pkg.version)
            val payloadKey = pkg.jobSchemaConstraints?.responsePayloadKey ?: "reponses"
            put(payloadKey, JSONObject().apply {
                for ((k, v) in validatedResponses) put(k, v)
            })
            val schema = pkg.jobSchemaConstraints
            if (schema?.capturesAsObject == true) {
                put("captures", JSONObject().apply {
                    for (c in validatedCaptures) {
                        put(c.sceneId, JSONObject().apply {
                            put("uri", schema.captureUris[c.sceneId] ?: "${c.sceneId}.mp4")
                            put("validation", "C")
                            put("prise", 1)
                            put("sha256", c.sha256)
                        })
                    }
                })
            } else {
                put("captures", JSONArray().apply {
                    for (c in validatedCaptures) put(JSONObject().apply {
                        put("scene", c.sceneId); put("valide", c.validatedByMerchant); put("sha256", c.sha256)
                    })
                })
            }
            if (effectiveScreens.isNotEmpty()) {
                put("ecrans", JSONArray(effectiveScreens))
            }
        }

        val guidedSession = GuidedCaptureSession(
            sessionId = sessionId,
            templateId = pkg.templateId,
            templateVersion = pkg.version,
            partitionJsonFile = pkg.partitionFile,
            audioProfileJsonFile = pkg.audioProfileFile,
            resourcesDir = pkg.packageDir,
            outputDir = outputDir,
            rotationDbFile = rotationDbFile,
            manifestJsonFile = pkg.manifestFile,
            responses = validatedResponses,
            captures = validatedCaptures,
            sessionState = CaptureSessionState.READY_FOR_RENDER,
            permissions = permissions,
            visitedScreens = effectiveScreens,
            expectedScreenCount = pkg.jobSchemaConstraints?.expectedScreenNumbers?.size?.takeIf { it > 0 },
            rejectedPreviousSessionIds = rejectedPreviousSessionIds,
            rejectedPreviousCaptureSha256s = rejectedPreviousCaptureSha256s,
            preparsedPlan = pkg.renderPlan,
            preparsedAudioProfile = pkg.audioProfile
        )

        val renderRequest = RenderRequest(
            templateId = pkg.templateId,
            templateVersion = pkg.version,
            partitionJsonFile = pkg.partitionFile,
            audioProfileJsonFile = pkg.audioProfileFile,
            resourcesDir = pkg.packageDir,
            outputDir = outputDir,
            rotationDbFile = rotationDbFile,
            responses = validatedResponses,
            captures = validatedCaptures.map {
                CaptureClip(
                    sceneId = it.sceneId,
                    file = File(it.localVideoPath),
                    expectedSha256 = it.sha256,
                    validated = it.validatedByMerchant,
                    sessionId = it.sessionId
                )
            },
            manifestJsonFile = pkg.manifestFile,
            visitedScreens = effectiveScreens,
            expectedScreenCount = pkg.jobSchemaConstraints?.expectedScreenNumbers?.size?.takeIf { it > 0 },
            sessionId = sessionId,
            rejectedPreviousSessionIds = rejectedPreviousSessionIds,
            rejectedPreviousCaptureSha256s = rejectedPreviousCaptureSha256s,
            preparsedPlan = pkg.renderPlan,
            preparsedAudioProfile = pkg.audioProfile
        )

        return AssembledTemplateJob(jobJson, guidedSession, renderRequest)
    }

    fun validateResponses(
        pkg: LoadedTemplatePackage,
        rawResponses: Map<String, String>
    ): Map<String, String> {
        val formKeys = pkg.formSpec.keys()
        val schema = pkg.jobSchemaConstraints
        val allowedKeys = if (schema != null && schema.allowedResponseKeys.isNotEmpty()) {
            schema.allowedResponseKeys
        } else {
            formKeys
        }

        if (schema != null && !schema.additionalResponsesAllowed) {
            for (k in rawResponses.keys) {
                if (k !in allowedKeys) {
                    throw TemplateFormValidationException(
                        "UNKNOWN_RESPONSE_KEY",
                        "Clé de réponse non autorisée par schemas/render-job.json: '$k'"
                    )
                }
            }
        }

        val requiredKeys = buildSet {
            addAll(pkg.formSpec.requiredKeys())
            schema?.requiredResponses?.let { addAll(it) }
        }

        val normalized = linkedMapOf<String, String>()
        val orderedKeys = (pkg.formSpec.questions.map { it.key } + allowedKeys).distinct()
        for (key in orderedKeys) {
            val rawVal = rawResponses[key]
            if (key in requiredKeys && (rawVal == null || rawVal.isBlank())) {
                throw TemplateFormValidationException(
                    "MISSING_REQUIRED_RESPONSE",
                    "Réponse obligatoire absente ou vide pour le champ '$key'"
                )
            }
            // Responses are merchant-authored text. Schema patterns/lengths are legacy
            // annotations and must never rewrite or reject content; schema still controls
            // payload shape, allowed keys, and which answers are required.
            normalized[key] = rawVal.orEmpty()
        }
        return normalized
    }

    fun validateCaptures(
        pkg: LoadedTemplatePackage,
        captures: List<SceneCaptureDescriptor>,
        expectedSessionId: String? = null
    ): List<SceneCaptureDescriptor> {
        val expectedSceneIds = pkg.renderPlan.scenes.map { it.id }
        val schemaScenes = pkg.jobSchemaConstraints?.requiredSceneIds.orEmpty()
        if (schemaScenes.isNotEmpty() && schemaScenes != expectedSceneIds) {
            throw TemplateFormValidationException(
                "SCHEMA_SCENE_MISMATCH",
                "Scènes du schéma ($schemaScenes) != partition ($expectedSceneIds)"
            )
        }

        val byScene = captures.associateBy { it.sceneId }
        if (captures.size != expectedSceneIds.size || byScene.keys.toList() != expectedSceneIds && byScene.keys != expectedSceneIds.toSet()) {
            throw TemplateFormValidationException(
                "CAPTURES_SCENE_COVERAGE_ERROR",
                "Captures fournies (${captures.map { it.sceneId }}) != scènes attendues ($expectedSceneIds)"
            )
        }

        val shaRegex = pkg.jobSchemaConstraints?.sha256Regex ?: Regex("^[0-9a-f]{64}$")
        val seenHashes = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()
        val result = mutableListOf<SceneCaptureDescriptor>()

        for (sc in pkg.renderPlan.scenes) {
            val cap = byScene.getValue(sc.id)
            if (!expectedSessionId.isNullOrBlank() && cap.sessionId != expectedSessionId) {
                throw TemplateFormValidationException(
                    "CROSS_SESSION_CAPTURE_REJECTED",
                    "Capture ${sc.id} appartient à la session ${cap.sessionId} != $expectedSessionId"
                )
            }
            if (cap.templateId != pkg.templateId) {
                throw TemplateFormValidationException(
                    "CAPTURE_TEMPLATE_MISMATCH",
                    "Capture ${sc.id} associée au gabarit ${cap.templateId} != ${pkg.templateId}"
                )
            }
            if (cap.recordingStatus != SceneRecordingStatus.COMPLETED || !cap.validatedByMerchant) {
                throw TemplateFormValidationException(
                    "CAPTURE_NOT_VALIDATED",
                    "Capture ${sc.id} non validée par le commerçant"
                )
            }
            val f = File(cap.localVideoPath)
            if (!f.exists() || !f.isFile || f.length() == 0L || !f.canRead()) {
                throw TemplateFormValidationException(
                    "CAPTURE_UNREADABLE",
                    "Fichier capture introuvable ou illisible pour ${sc.id}: ${cap.localVideoPath}"
                )
            }
            val canonicalPath = f.canonicalPath
            if (!seenPaths.add(canonicalPath)) {
                throw TemplateFormValidationException(
                    "DUPLICATE_CAPTURE_FILE",
                    "Même fichier vidéo réutilisé pour ${sc.id}"
                )
            }
            val computedSha = PreFlightValidator.sha256File(f)
            if (cap.sha256.isNotEmpty() && !cap.sha256.equals(computedSha, ignoreCase = true)) {
                throw TemplateFormValidationException(
                    "CAPTURE_SHA_MISMATCH",
                    "SHA-256 capture ${sc.id} incohérent"
                )
            }
            val finalSha = computedSha.lowercase()
            if (!shaRegex.matches(finalSha)) {
                throw TemplateFormValidationException(
                    "CAPTURE_SHA_FORMAT",
                    "Format SHA-256 invalide pour ${sc.id}: $finalSha"
                )
            }
            if (!seenHashes.add(finalSha)) {
                throw TemplateFormValidationException(
                    "DUPLICATE_CAPTURE_HASH",
                    "Même contenu vidéo détecté sur ${sc.id}"
                )
            }

            val requiredSourceFrames = sc.shots.maxOfOrNull { it.sourceOutFrame }
                ?: (sc.endFrameExcluded - sc.startFrame)
            val requiredUs = (requiredSourceFrames.toLong() * 1_000_000L) / pkg.renderPlan.fps
            val allowFreeze = sc.shots.any { it.freezeTailFrames > 0 }
            if (!allowFreeze && cap.durationUs > 0L && cap.durationUs + 150_000L < requiredUs) {
                throw TemplateFormValidationException(
                    "CAPTURE_TOO_SHORT",
                    "Durée capture ${sc.id} insuffisante (${cap.durationUs / 1000} ms < ${requiredUs / 1000} ms)"
                )
            }
            result += cap.copy(sha256 = finalSha)
        }
        return result
    }
}
