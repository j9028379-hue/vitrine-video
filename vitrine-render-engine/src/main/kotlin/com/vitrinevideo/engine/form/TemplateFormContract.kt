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
    val expectedScreenNumbers: List<Int>
)

object TemplateFormParser {

    fun parseQuestions(rawJson: String): TemplateFormSpec {
        val root = JSONObject(rawJson)
        val title = root.optString("titre", "").ifBlank { "Formulaire gabarit" }
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

        val repObj = props.optJSONObject("reponses") ?: JSONObject()
        val reqRepArr = repObj.optJSONArray("required") ?: JSONArray()
        val requiredResponses = (0 until reqRepArr.length()).map { reqRepArr.getString(it) }.toSet()

        val repProps = repObj.optJSONObject("properties") ?: JSONObject()
        val allowedKeys = mutableSetOf<String>()
        val minLengths = mutableMapOf<String, Int>()
        val repIter = repProps.keys()
        while (repIter.hasNext()) {
            val k = repIter.next()
            allowedKeys += k
            val fieldSchema = repProps.optJSONObject(k)
            if (fieldSchema != null && fieldSchema.has("minLength")) {
                minLengths[k] = fieldSchema.getInt("minLength")
            }
        }
        val additionalAllowed = repObj.optBoolean("additionalProperties", true)

        val capItems = props.optJSONObject("captures")?.optJSONObject("items") ?: JSONObject()
        val capProps = capItems.optJSONObject("properties") ?: JSONObject()
        val sceneEnumArr = capProps.optJSONObject("scene")?.optJSONArray("enum") ?: JSONArray()
        val requiredScenes = (0 until sceneEnumArr.length()).map { sceneEnumArr.getString(it) }
        val shaPattern = capProps.optJSONObject("sha256")?.optString("pattern", "^[0-9a-f]{64}$") ?: "^[0-9a-f]{64}$"

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
            expectedScreenNumbers = expectedScreens
        )
    }
}
