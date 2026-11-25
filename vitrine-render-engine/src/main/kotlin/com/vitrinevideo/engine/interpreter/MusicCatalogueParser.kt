package com.vitrinevideo.engine.interpreter

import org.json.JSONObject

/**
 * Generic adapter for packages that declare their music as a catalogue of
 * externally distributed resources instead of an `audio/profil.json` file.
 * The declared IDs, metadata, hashes, start points and approval text are kept
 * verbatim; only the resource location is resolved by the caller.
 */
object MusicCatalogueParser {

    data class DeclaredResource(
        val relativePath: String,
        val sha256: String,
        val bytes: Long,
        val startSec: Double
    )

    fun canParse(catalogue: JSONObject, voices: JSONObject?): Boolean =
        catalogue.has("pistes") && catalogue.has("audio_profile") && catalogue.has("pack_version")

    fun parse(
        catalogue: JSONObject,
        voices: JSONObject?,
        resources: Map<Int, DeclaredResource>
    ): AudioProfileSpec {
        val id = catalogue.getString("audio_profile")
        val version = catalogue.getString("pack_version")
        val pistes = catalogue.getJSONArray("pistes")
        require(pistes.length() > 0) { "Catalogue musical sans piste" }
        val approvedNumbers = mutableListOf<Int>()
        val tracks = mutableListOf<ApprovedTrack>()
        for (i in 0 until pistes.length()) {
            val p = pistes.getJSONObject(i)
            val number = p.getInt("numero")
            approvedNumbers += number
            val declaredSha = p.getString("sha256").lowercase()
            val declaredBytes = p.getLong("octets")
            val resource = resources[number]
                ?: throw IllegalArgumentException("Ressource musicale #$number absente du catalogue interne")
            require(resource.sha256.equals(declaredSha, ignoreCase = true)) {
                "SHA-256 déclaré #$number ($declaredSha) != ressource interne (${resource.sha256})"
            }
            require(resource.bytes == declaredBytes) {
                "Taille déclarée #$number ($declaredBytes) != ressource interne (${resource.bytes})"
            }
            tracks += ApprovedTrack(
                number = number,
                title = p.getString("titre"),
                artist = p.optString("artiste", ""),
                licence = p.optString("licence_affichee", ""),
                credit = listOf(
                    p.optString("titre", ""),
                    p.optString("artiste", ""),
                    "Pixabay #${p.optString("pixabay_id", p.optString("id", ""))}",
                    p.optString("page", "")
                ).filter { it.isNotBlank() }.joinToString(" — "),
                relativePath = resource.relativePath,
                sha256 = declaredSha,
                preMeasuredLufs = if (p.has("lufs")) p.getDouble("lufs") else null
            )
        }

        val mixJson = voices?.optJSONObject("mix") ?: JSONObject()
        val gates = voices?.optJSONObject("gates") ?: JSONObject()
        val mix = AudioMixConfig(
            profileId = id,
            includeCaptureVoice = true,
            targetLufs = mixJson.optDouble("master_target_lufs", -16.0),
            bgGainDb = mixJson.optDouble("music_open_gain_db", -3.0),
            underSpeechGainDb = mixJson.optDouble("music_under_voice_gain_db", -10.0),
            attackMs = mixJson.optInt("duck_attack_ms", 120),
            releaseMs = mixJson.optInt("duck_release_ms", 450),
            minimumVoiceMusicGapDb = null,
            duckDepthDb = if (mixJson.has("duck_depth_db")) mixJson.optDouble("duck_depth_db") else null,
            voiceGainDb = mixJson.optDouble("voice_gain_db", 0.0),
            attackCurve = mixJson.optString("duck_attack_curve", ""),
            releaseCurve = mixJson.optString("duck_release_curve", ""),
            peakLimitDb = mixJson.optDouble("master_true_peak_dbtp_max", -2.0),
            truePeakMaxDbtp = if (mixJson.has("master_true_peak_dbtp_max")) mixJson.optDouble("master_true_peak_dbtp_max") else null,
            deliveryFormat = mixJson.optString("delivery_audio", ""),
            minimumSpeechToMusicDb = if (gates.has("minimum_active_speech_to_music_db")) gates.optDouble("minimum_active_speech_to_music_db") else null
        )

        // The catalogue itself is the versioned approval record of the playable
        // selection. Demo TTS clips in `voix.json` are documentation only: they
        // are never mapped into the render request or into the mixer.
        val approvalText = catalogue.optString("approbation", "")
        return AudioProfileSpec(
            id = id,
            version = version,
            status = approvalText,
            rotationMode = "cycle_${tracks.size}_apres_succes",
            approvedNumbers = approvedNumbers,
            voiceApproved = voices != null && voices.has("clips"),
            mixApproved = approvalText.isNotBlank() && tracks.isNotEmpty(),
            tracks = tracks,
            mix = mix
        )
    }
}
