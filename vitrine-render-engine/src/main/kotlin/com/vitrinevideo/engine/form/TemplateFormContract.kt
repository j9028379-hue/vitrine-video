package com.vitrinevideo.engine.form

import org.json.JSONArray
import org.json.JSONObject

class TemplateFormValidationException(val code: String, message: String) : IllegalArgumentException(message)

data class TemplateFormQuestion(
    val key: String,
    val prompt: String,
    val example: String,
    val required: Boolean,
    val valueType: String
)

data class TemplateFormSpec(
    val title: String,
    val questions: List<TemplateFormQuestion>
) {
    fun keys(): Set<String> = questions.map { it.key }.toSet()
    fun requiredKeys(): Set<String> = questions.filter { it.required }.map { it.key }.toSet()
}

data class RenderJobSchemaConstraints(
    val expectedTemplateId: String?,
    val expectedTemplateVersion: String?,
    val requiredResponses: Set<String>,
    val responseMinLengths: Map<String, Int>,
    val allowedResponseKeys: Set<String>,
    val additionalResponsesAllowed: Boolean,
    val requiredSceneIds: List<String>,
    val sha256Regex: Regex,
    val expectedScreenNumbers: List<Int>,
    val responseMaxLengths: Map<String, Int> = emptyMap(),
    val responsePatterns: Map<String, Regex> = emptyMap(),
    val responsePayloadKey: String = "reponses",
    val capturesAsObject: Boolean = false,
    val captureUris: Map<String, String> = emptyMap()
)

object TemplateFormParser {

    fun parseQuestions(rawJson: String): TemplateFormSpec {
        val root = JSONObject(rawJson)
        val title = root.optString("titre", "").ifBlank { "Formulaire gabarit" }
        if (!root.has("champs") && !root.has("questions")) {
            val questions = mutableListOf<TemplateFormQuestion>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = root.opt(key)
                if (value is String) questions += TemplateFormQuestion(key, value, "", true, "texte")
            }
            if (questions.isEmpty()) throw TemplateFormValidationException("FORM_MISSING_QUESTIONS", "Aucune question valide dans form/questions.json")
            return TemplateFormSpec(title, questions)
        }
        val arr = root.optJSONArray("champs") ?: root.optJSONArray("questions")
            ?: throw TemplateFormValidationException("FORM_MISSING_QUESTIONS", "Tableau 'champs'/'questions' absent de form/questions.json")
        if (arr.length() == 0) {
            throw TemplateFormValidationException("FORM_EMPTY_QUESTIONS", "Aucune question définie dans form/questions.json")
        }
        val questions = (0 until arr.length()).map { idx ->
            val q = arr.getJSONObject(idx)
            val key = q.optString("cle", "").trim()
            if (key.isEmpty()) {
                throw TemplateFormValidationException("FORM_INVALID_KEY", "Clé vide à l'index $idx de form/questions.json")
            }
            TemplateFormQuestion(
                key = key,
                prompt = q.optString("question", key),
                example = q.optString("exemple", ""),
                required = q.optBoolean("obligatoire", true),
                valueType = q.optString("type", "texte")
            )
        }
        return TemplateFormSpec(title = title, questions = questions)
    }

    fun parseRenderJobSchema(rawJson: String): RenderJobSchemaConstraints {
        val root = JSONObject(rawJson)
        val props = root.optJSONObject("properties") ?: JSONObject()

        val expectedTemplateId = props.optJSONObject("template_id")?.optString("const", "")?.ifBlank { null }
        val expectedTemplateVersion = props.optJSONObject("template_version")?.optString("const", "")?.ifBlank { null }

        val payloadKey = if (props.has("merchant_payload")) "merchant_payload" else "reponses"
        val repObj = props.optJSONObject(payloadKey) ?: JSONObject()
        val reqRepArr = repObj.optJSONArray("required") ?: JSONArray()
        val requiredResponses = (0 until reqRepArr.length()).map { reqRepArr.getString(it) }.toSet()

        val repProps = repObj.optJSONObject("properties") ?: JSONObject()
        val allowedKeys = mutableSetOf<String>()
        val minLengths = mutableMapOf<String, Int>()
        val maxLengths = mutableMapOf<String, Int>()
        val patterns = mutableMapOf<String, Regex>()
        val repIter = repProps.keys()
        while (repIter.hasNext()) {
            val k = repIter.next()
            allowedKeys += k
            val fieldSchema = repProps.optJSONObject(k)
            if (fieldSchema != null) {
                if (fieldSchema.has("minLength")) minLengths[k] = fieldSchema.getInt("minLength")
                if (fieldSchema.has("maxLength")) maxLengths[k] = fieldSchema.getInt("maxLength")
                fieldSchema.optString("pattern", "").takeIf { it.isNotBlank() }?.let { patterns[k] = Regex(it) }
            }
        }
        val additionalAllowed = repObj.optBoolean("additionalProperties", true)

        val capturesObj = props.optJSONObject("captures") ?: JSONObject()
        val capturesAsObject = capturesObj.has("properties")
        val capProps = if (capturesAsObject) capturesObj.optJSONObject("properties") ?: JSONObject()
            else capturesObj.optJSONObject("items")?.optJSONObject("properties") ?: JSONObject()
        val sceneEnumArr = capProps.optJSONObject("scene")?.optJSONArray("enum") ?: JSONArray()
        val requiredCaptureArr = capturesObj.optJSONArray("required") ?: JSONArray()
        val requiredScenes = if (sceneEnumArr.length() > 0) (0 until sceneEnumArr.length()).map { sceneEnumArr.getString(it) }
            else if (requiredCaptureArr.length() > 0) (0 until requiredCaptureArr.length()).map { requiredCaptureArr.getString(it) }
            else emptyList()
        val captureUris = mutableMapOf<String, String>()
        var shaPattern = capProps.optJSONObject("sha256")?.optString("pattern", "") ?: ""
        val capKeyIter = capProps.keys()
        while (capKeyIter.hasNext()) {
            val scene = capKeyIter.next()
            val sceneProps = capProps.optJSONObject(scene)?.optJSONObject("properties") ?: continue
            sceneProps.optJSONObject("uri")?.optString("const", "")?.takeIf { it.isNotBlank() }?.let { captureUris[scene] = it }
            if (shaPattern.isBlank()) shaPattern = sceneProps.optJSONObject("sha256")?.optString("pattern", "") ?: ""
        }
        if (shaPattern.isBlank()) shaPattern = "^[0-9a-f]{64}$"

        val ecransProp = props.optJSONObject("ecrans")
        val ecransEnum = ecransProp?.optJSONObject("items")?.optJSONArray("enum") ?: JSONArray()
        val expectedScreens = if (ecransEnum.length() > 0) {
            (0 until ecransEnum.length()).map { ecransEnum.getInt(it) }
        } else {
            val minScreens = ecransProp?.optInt("minItems", 0) ?: 0
            if (minScreens > 0) (1..minScreens).toList() else emptyList()
        }

        return RenderJobSchemaConstraints(
            expectedTemplateId = expectedTemplateId,
            expectedTemplateVersion = expectedTemplateVersion,
            requiredResponses = requiredResponses,
            responseMinLengths = minLengths,
            allowedResponseKeys = allowedKeys,
            additionalResponsesAllowed = additionalAllowed,
            requiredSceneIds = requiredScenes,
            sha256Regex = Regex(shaPattern),
            expectedScreenNumbers = expectedScreens,
            responseMaxLengths = maxLengths,
            responsePatterns = patterns,
            responsePayloadKey = payloadKey,
            capturesAsObject = capturesAsObject,
            captureUris = captureUris
        )
    }
}
