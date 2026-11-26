package com.vitrinevideo.engine.interpreter

import org.json.JSONObject

/**
 * Versioned, explicit founder decisions about music selection.
 *
 * This is deliberately separate from `audio/profil.json`: the founder's
 * commercial approval and the package's technical mix state are different
 * facts. Nothing here changes a template JSON, invents a missing track, or
 * weakens the technical validation performed by the engine.
 */
data class FounderAudioApproval(
    val approvalId: String,
    val declarationDate: String,
    val profileId: String,
    val templateId: String,
    val approvedTrackNumbers: Set<Int>,
    val approvedFiles: Set<String>,
    val technicalMixStateInSource: String,
    val declaration: String
) {
    fun covers(profile: AudioProfileSpec): Boolean =
        profile.id == profileId &&
            approvedTrackNumbers.isNotEmpty() &&
            profile.tracks.isNotEmpty() &&
            profile.tracks.all { it.number in approvedTrackNumbers && it.relativePath in approvedFiles }
}

object FounderAudioApprovalParser {
    fun parse(rawJson: String): FounderAudioApproval =
        parseAll(rawJson).firstOrNull() ?: error("Aucune approbation fondateur exploitable")

    fun parseAll(rawJson: String): List<FounderAudioApproval> {
        val root = JSONObject(rawJson)
        val results = mutableListOf<FounderAudioApproval>()
        val records = root.optJSONArray("records") ?: return emptyList()
        for (i in 0 until records.length()) {
            val r = records.getJSONObject(i)
            val nums = r.optJSONArray("approved_track_numbers") ?: continue
            val files = r.optJSONArray("approved_files") ?: continue
            results += FounderAudioApproval(
                approvalId = root.optString("approval_id", ""),
                declarationDate = root.optString("decision_date", ""),
                profileId = r.optString("profile_id", ""),
                templateId = r.optString("template_id", ""),
                approvedTrackNumbers = (0 until nums.length()).map { nums.getInt(it) }.toSet(),
                approvedFiles = (0 until files.length()).map { files.getString(it) }.toSet(),
                technicalMixStateInSource = r.optString("technical_mix_state_in_source", ""),
                declaration = root.optString("declaration", "")
            )
        }
        return results
    }
}
