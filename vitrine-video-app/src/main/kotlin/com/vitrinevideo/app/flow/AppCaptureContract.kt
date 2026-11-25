package com.vitrinevideo.app.flow

import com.vitrinevideo.engine.capture.CaptureDurationPolicy
import com.vitrinevideo.engine.capture.SceneCaptureDescriptor
import com.vitrinevideo.engine.capture.SceneCaptureValidationSnapshot
import com.vitrinevideo.engine.capture.SceneCaptureValidator
import com.vitrinevideo.engine.capture.SceneRecordingStatus
import com.vitrinevideo.engine.form.TemplateFormValidationException
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.pkg.LoadedTemplatePackage
import org.json.JSONObject
import java.io.File

enum class AppExecutionMode {
    REAL_APPLICATION,
    TECHNICAL_TEST
}

enum class CaptureSource {
    CAMERAX_REAL,
    TECHNICAL_FIXTURE
}

data class CapturedSceneTake(
    val sceneIndex: Int,
    val sceneId: String,
    val filePath: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val audioPresent: Boolean,
    val sha256: String,
    val source: CaptureSource,
    val validatedByMerchant: Boolean,
    val recordedAtEpochMs: Long
) {
    fun snapshot(): SceneCaptureValidationSnapshot {
        val file = File(filePath)
        return SceneCaptureValidationSnapshot(
            sceneId = sceneId,
            filePath = filePath,
            fileExists = file.exists(),
            fileReadable = file.canRead(),
            fileNonEmpty = file.length() > 0L,
            durationUs = durationUs,
            width = width,
            height = height,
            rotation = rotation,
            hasVideoStream = width > 0 && height > 0,
            audioPresent = audioPresent,
            sha256 = sha256,
            fromRealCamera = source == CaptureSource.CAMERAX_REAL
        )
    }
}

class RealSessionViolationException(
    val code: String,
    message: String
) : IllegalStateException(message)

/**
 * Exact mapping from the mockup DOM form inputs to COMMERCE-T3 JSON contract keys:
 * - nom
 * - produit
 * - prix (optional; preserved as "" if blank, never invented)
 * - demonstration (mapped from mockup #q-accroche when T3 is active)
 * - quartier
 * - whatsapp
 */
object MockupResponseMapper {

    fun parseFromWebJson(rawJson: String, templateId: String = "COMMERCE-T3"): Map<String, String> {
        val obj = JSONObject(rawJson)
        val raw = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val value = obj.opt(k)
            if (value !is String) {
                throw TemplateFormValidationException(
                    "RESPONSE_VALUE_NOT_TEXT",
                    "La réponse '$k' doit être une chaîne de texte."
                )
            }
            raw[k] = value
        }
        return normalizeRawResponses(raw, templateId)
    }

    /**
     * Pass every form key through unchanged for schema-driven/future templates. The only
     * compatibility bridges are the existing Commerce T1/T3 label alias for the shared UI field.
     */
    fun normalizeRawResponses(raw: Map<String, String>, templateId: String = "COMMERCE-T3"): Map<String, String> {
        val out = LinkedHashMap(raw)
        when (templateId) {
            "COMMERCE-T4" -> {
                // `fin` is optional in the founder's form, while its v1 render-job schema
                // includes the key in `required`; preserve omission as an empty optional value.
                if (!out.containsKey("fin")) out["fin"] = ""
            }
            "COMMERCE-T1", "COMMERCE-T2" -> {
                // T1/T2 render-job contracts both require `accroche`. The shared mockup
                // historically emitted `demonstration` for #q-accroche, so normalise it
                // here as well as at the bridge source.
                val legacyBridgeValue = out.remove("demonstration")
                if (!out.containsKey("accroche") && legacyBridgeValue != null) out["accroche"] = legacyBridgeValue
            }
            "COMMERCE-T3" -> {
                val legacyUiValue = out.remove("accroche")
                if (!out.containsKey("demonstration") && legacyUiValue != null) out["demonstration"] = legacyUiValue
            }
            TemplateCatalogue.RESTAURATION_T2 -> {
                // The shared UI field is #q-accroche. Only a genuinely legacy alias is
                // remapped; an absent optional accroche stays absent and is never invented.
                val legacyUiValue = out.remove("demonstration")
                if (!out.containsKey("accroche") && legacyUiValue != null) out["accroche"] = legacyUiValue
            }
        }
        return out
    }

    fun validateAgainstPackage(pkg: LoadedTemplatePackage, responses: Map<String, String>): Map<String, String> {
        return TemplateJobAssembler.validateResponses(pkg, responses)
    }
}

/**
 * Sequential 4-scene guided capture state machine:
 * - Enforces S1 -> S2 -> S3 -> S4 acceptance order
 * - Preserves validated scenes & form answers across retakes and Back navigation
 * - Replaces ONLY the re-shot scene when the merchant clicks REFAIRE
 */
class CaptureProgressionStateMachine(
    val sceneIds: List<String> = listOf("S1", "S2", "S3", "S4"),
    val executionMode: AppExecutionMode = AppExecutionMode.REAL_APPLICATION
) {
    private val takes = linkedMapOf<String, CapturedSceneTake>()
    private val pendingTakes = linkedMapOf<String, CapturedSceneTake>()
    private val visitedScreenSet = sortedSetOf<Int>()
    private var sceneRequirements: Map<String, Pair<Long, Boolean>> = emptyMap()

    /** Installs the exact engine requirements so readiness and render use one contract. */
    fun configureRequirements(pkg: LoadedTemplatePackage) {
        val mapped = pkg.renderPlan.scenes.associate { sc ->
            sc.id to (CaptureDurationPolicy.requiredDurationUs(sc, pkg.renderPlan.fps) to
                CaptureDurationPolicy.allowsFrozenTail(sc))
        }
        if (mapped.keys == sceneIds.toSet()) sceneRequirements = mapped
    }

    fun clearRequirements() {
        sceneRequirements = emptyMap()
    }

    fun invalidReadySceneIndexes(): List<Int> = sceneIds.indices.filter { index ->
        val id = sceneIds[index]
        val take = takes[id]?.takeIf { it.validatedByMerchant } ?: return@filter false
        val (requiredUs, allowFreeze) = sceneRequirements[id] ?: return@filter false
        SceneCaptureValidator.validate(take.snapshot(), requiredUs, allowFreeze) != null
    }

    fun canOpenSceneCamera(sceneIndex: Int): Boolean {
        if (sceneIndex !in sceneIds.indices) return false
        if (sceneIndex == 0) return true
        val prevId = sceneIds[sceneIndex - 1]
        return takes[prevId]?.validatedByMerchant == true
    }

    fun markGuideVisited(sceneIndex: Int) {
        if (sceneIndex in sceneIds.indices) {
            visitedScreenSet += (sceneIndex * 3) + 1
        }
    }

    fun onRecordingCompleted(take: CapturedSceneTake) {
        if (take.sceneIndex !in sceneIds.indices || take.sceneId != sceneIds[take.sceneIndex]) {
            throw IllegalArgumentException("Scène invalide: ${take.sceneIndex} / ${take.sceneId}")
        }
        if (executionMode == AppExecutionMode.REAL_APPLICATION && take.source != CaptureSource.CAMERAX_REAL) {
            throw RealSessionViolationException(
                "SYNTHETIC_INPUT_FORBIDDEN",
                "Mode application réelle : les vidéos synthétiques sont interdites."
            )
        }
        if (!canOpenSceneCamera(take.sceneIndex)) {
            throw RealSessionViolationException(
                "OUT_OF_ORDER_CAPTURE",
                "La scène précédente doit être validée avant ${take.sceneId}."
            )
        }
        visitedScreenSet += (take.sceneIndex * 3) + 2
        pendingTakes[take.sceneId] = take.copy(validatedByMerchant = false)
    }

    fun acceptScene(sceneIndex: Int): CapturedSceneTake {
        if (!canOpenSceneCamera(sceneIndex)) {
            throw RealSessionViolationException(
                "PREVIOUS_SCENE_NOT_VALIDATED",
                "Impossible de valider ${sceneIds.getOrNull(sceneIndex)} avant la scène précédente."
            )
        }
        val sceneId = sceneIds[sceneIndex]
        val take = pendingTakes[sceneId] ?: takes[sceneId]
            ?: throw RealSessionViolationException(
                "MISSING_PENDING_TAKE",
                "Aucune prise enregistrée à valider pour $sceneId."
            )
        val failure = sceneRequirements[sceneId]?.let { (requiredUs, allowFreeze) ->
            SceneCaptureValidator.validate(take.snapshot(), requiredUs, allowFreeze)
        }
        if (failure != null) {
            throw RealSessionViolationException(failure.code, failure.message)
        }
        val accepted = take.copy(validatedByMerchant = true)
        takes[sceneId] = accepted
        pendingTakes.remove(sceneId)
        visitedScreenSet += (sceneIndex * 3) + 1
        visitedScreenSet += (sceneIndex * 3) + 2
        visitedScreenSet += (sceneIndex * 3) + 3
        return accepted
    }

    /**
     * Retake replaces ONLY the target scene while keeping all other validated scenes intact.
     */
    fun retakeScene(sceneIndex: Int) {
        if (sceneIndex !in sceneIds.indices) return
        val sceneId = sceneIds[sceneIndex]
        pendingTakes.remove(sceneId)
        takes.remove(sceneId)
    }

    fun restoreValidatedTake(take: CapturedSceneTake) {
        if (executionMode == AppExecutionMode.REAL_APPLICATION && take.source != CaptureSource.CAMERAX_REAL) {
            return
        }
        if (take.validatedByMerchant && take.sceneIndex in sceneIds.indices) {
            val (requiredUs, allowFreeze) = sceneRequirements[take.sceneId]
            if (requiredUs != null &&
                SceneCaptureValidator.validate(take.snapshot(), requiredUs, allowFreeze) != null
            ) {
                return
            }
            takes[take.sceneId] = take
            visitedScreenSet += (take.sceneIndex * 3) + 1
            visitedScreenSet += (take.sceneIndex * 3) + 2
            visitedScreenSet += (take.sceneIndex * 3) + 3
        }
    }

    fun hasValidatedScene(sceneIndex: Int): Boolean {
        val id = sceneIds.getOrNull(sceneIndex) ?: return false
        return takes[id]?.validatedByMerchant == true
    }

    fun getReviewableTake(sceneIndex: Int): CapturedSceneTake? {
        val id = sceneIds.getOrNull(sceneIndex) ?: return null
        return pendingTakes[id] ?: takes[id]
    }

    fun allValidatedTakes(): List<CapturedSceneTake> = sceneIds.mapNotNull { takes[it] }

    fun isAllScenesValidated(): Boolean =
        sceneIds.all { takes[it]?.validatedByMerchant == true } && invalidReadySceneIndexes().isEmpty()

    fun visitedScreens(): List<Int> = visitedScreenSet.toList()

    fun clearAll() {
        takes.clear()
        pendingTakes.clear()
        visitedScreenSet.clear()
    }
}

/**
 * Enforces strict separation between REAL_APPLICATION mode (real CameraX + merchant answers)
 * and TECHNICAL_TEST mode. No silent fallback to synthetic fixtures is ever allowed.
 */
object RealSessionGuard {

    fun verifyAndBuildDescriptors(
        mode: AppExecutionMode,
        pkg: LoadedTemplatePackage,
        sessionId: String,
        responses: Map<String, String>,
        takes: List<CapturedSceneTake>
    ): Pair<Map<String, String>, List<SceneCaptureDescriptor>> {
        if (mode != AppExecutionMode.REAL_APPLICATION) {
            throw RealSessionViolationException(
                "INVALID_MODE_FOR_PRODUCTION_FLOW",
                "Le parcours commercial exige AppExecutionMode.REAL_APPLICATION."
            )
        }

        val validatedResponses = try {
            MockupResponseMapper.validateAgainstPackage(pkg, responses)
        } catch (e: TemplateFormValidationException) {
            throw RealSessionViolationException(e.code, e.message ?: "Réponses commerciales incomplètes")
        }

        val expectedIds = pkg.renderPlan.scenes.map { it.id }
        val byId = takes.associateBy { it.sceneId }
        if (byId.keys != expectedIds.toSet() || takes.size != expectedIds.size) {
            throw RealSessionViolationException(
                "INCOMPLETE_SCENES",
                "Toutes les scènes ($expectedIds) doivent être filmées et validées avant le montage."
            )
        }

        val seenPaths = mutableSetOf<String>()
        val seenHashes = mutableSetOf<String>()
        val descriptors = mutableListOf<SceneCaptureDescriptor>()

        for (sc in pkg.renderPlan.scenes) {
            val take = byId.getValue(sc.id)
            if (take.source != CaptureSource.CAMERAX_REAL) {
                throw RealSessionViolationException(
                    "SYNTHETIC_SCENE_REJECTED",
                    "La scène ${sc.id} ne provient pas de la caméra réelle CameraX."
                )
            }
            if (!take.validatedByMerchant) {
                throw RealSessionViolationException(
                    "SCENE_NOT_VALIDATED",
                    "La scène ${sc.id} n'a pas été acceptée par le commerçant."
                )
            }
            val file = File(take.filePath)
            if (!file.exists() || !file.isFile || file.length() == 0L || !file.canRead()) {
                throw RealSessionViolationException(
                    "CAPTURE_FILE_UNAVAILABLE",
                    "Vidéo introuvable ou illisible pour ${sc.id} (${take.filePath})."
                )
            }
            val canonical = file.canonicalPath
            if (!seenPaths.add(canonical)) {
                throw RealSessionViolationException(
                    "DUPLICATE_SCENE_FILE",
                    "Chaque scène doit avoir sa propre prise vidéo (${sc.id})."
                )
            }
            if (take.sha256.isBlank() || !seenHashes.add(take.sha256.lowercase())) {
                throw RealSessionViolationException(
                    "INVALID_OR_DUPLICATE_HASH",
                    "Empreinte SHA-256 manquante ou dupliquée pour ${sc.id}."
                )
            }
            val failure = SceneCaptureValidator.validate(
                SceneCaptureValidationSnapshot(
                    sceneId = sc.id,
                    filePath = take.filePath,
                    fileExists = file.exists(),
                    fileReadable = file.canRead(),
                    fileNonEmpty = file.length() > 0L,
                    durationUs = take.durationUs,
                    width = take.width,
                    height = take.height,
                    rotation = take.rotation,
                    hasVideoStream = take.width > 0 && take.height > 0,
                    audioPresent = take.audioPresent,
                    sha256 = take.sha256,
                    fromRealCamera = take.source == CaptureSource.CAMERAX_REAL
                ),
                requiredDurationUs = CaptureDurationPolicy.requiredDurationUs(sc, pkg.renderPlan.fps),
                allowFrozenTail = CaptureDurationPolicy.allowsFrozenTail(sc)
            )
            if (failure != null) {
                throw RealSessionViolationException(failure.code, failure.message)
            }

            descriptors += SceneCaptureDescriptor(
                sessionId = sessionId,
                templateId = pkg.templateId,
                templateVersion = pkg.version,
                sceneId = sc.id,
                localVideoPath = take.filePath,
                durationUs = take.durationUs,
                width = take.width,
                height = take.height,
                rotation = take.rotation,
                audioPresent = take.audioPresent,
                sha256 = take.sha256.lowercase(),
                recordingStatus = SceneRecordingStatus.COMPLETED,
                validatedByMerchant = true,
                recordedAtEpochMs = take.recordedAtEpochMs
            )
        }

        return validatedResponses to descriptors
    }
}
