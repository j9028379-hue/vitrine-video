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
        permissions: CapturePermissionsSnapshot = CapturePermissionsSnapshot()
    ): AssembledTemplateJob {
        val validatedResponses = validateResponses(pkg, rawResponses)
        val validatedCaptures = validateCaptures(pkg, captures)
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
            put("reponses", JSONObject().apply {
                for ((k, v) in validatedResponses) {
                    put(k, v)
                }
            })
            put("captures", JSONArray().apply {
                for (c in validatedCaptures) {
                    put(JSONObject().apply {
                        put("scene", c.sceneId)
                        put("valide", c.validatedByMerchant)
                        put("sha256", c.sha256)
                    })
                }
            })
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
            expectedScreenCount = pkg.jobSchemaConstraints?.expectedScreenNumbers?.size?.takeIf { it > 0 }
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
                    validated = it.validatedByMerchant
                )
            },
            manifestJsonFile = pkg.manifestFile,
            visitedScreens = effectiveScreens,
            expectedScreenCount = pkg.jobSchemaConstraints?.expectedScreenNumbers?.size?.takeIf { it > 0 }
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
            if (key in requiredKeys) {
                if (rawVal == null || rawVal.trim().isEmpty()) {
                    throw TemplateFormValidationException(
                        "MISSING_REQUIRED_RESPONSE",
                        "Réponse obligatoire absente ou vide pour le champ '$key'"
                    )
                }
                val minLen = schema?.responseMinLengths?.get(key) ?: 1
                if (rawVal.trim().length < minLen) {
                    throw TemplateFormValidationException(
                        "RESPONSE_TOO_SHORT",
                        "Réponse '$key' trop courte (${rawVal.trim().length} < $minLen)"
                    )
                }
                // Preserve merchant wording intact (only normalize outer whitespace if needed, never invent text)
                normalized[key] = rawVal
            } else {
                // Optional field (e.g. 'prix'): preserve empty string "" cleanly when omitted
                normalized[key] = rawVal ?: ""
            }
        }
        return normalized
    }

    fun validateCaptures(
        pkg: LoadedTemplatePackage,
        captures: List<SceneCaptureDescriptor>
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

            val requiredUs = ((sc.endFrameExcluded - sc.startFrame).toLong() * 1_000_000L) / pkg.renderPlan.fps
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
