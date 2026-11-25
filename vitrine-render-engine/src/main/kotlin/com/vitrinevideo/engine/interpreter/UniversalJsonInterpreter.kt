package com.vitrinevideo.engine.interpreter

import com.vitrinevideo.engine.capabilities.EngineCapabilities
import org.json.JSONObject
import java.io.File

object UniversalJsonInterpreter {

    fun parsePartition(partitionFile: File, supportingFiles: Map<String, String> = emptyMap()): RenderPlan {
        require(partitionFile.exists()) { "Fichier partition introuvable: ${partitionFile.path}" }
        return parsePartitionJson(partitionFile.readText(), supportingFiles)
    }

    fun parsePartitionJson(rawJson: String, supportingFiles: Map<String, String> = emptyMap()): RenderPlan {
        val json = JSONObject(rawJson)
        val plan = when {
            ContratCandidatParser.canParse(json) -> ContratCandidatParser.parse(json)
            PartitionV1Parser.canParse(json) -> PartitionV1Parser.parse(json)
            EngineeringSpecParser.canParse(json) -> EngineeringSpecParser.parse(json)
            EvidenceMontageContractParser.canParse(json) -> EvidenceMontageContractParser.parse(
                json,
                supportingFiles["render/film.py"]
            )
            else -> throw IllegalArgumentException("Format JSON de partition non reconnu")
        }
        EngineCapabilities.assertSupported(plan.requiredCapabilities, plan.unknownCapabilities)
        validateStructuralIntegrity(plan)
        return plan
    }

    fun parseAudioProfile(profileFile: File, founderApprovalJson: String? = null): AudioProfileSpec {
        require(profileFile.exists()) { "Fichier profil audio introuvable: ${profileFile.path}" }
        return parseAudioProfileJson(profileFile.readText(), founderApprovalJson)
    }

    fun parseAudioProfileJson(rawJson: String, founderApprovalJson: String? = null): AudioProfileSpec {
        val json = JSONObject(rawJson)
        val status = json.optString("statut", "validé")
        val approvedArr = json.getJSONArray("pistes_approuvees")
        val approvedIds = (0 until approvedArr.length()).map { approvedArr.getInt(it) }
        val pistesArr = json.getJSONArray("pistes")
        val tracks = (0 until pistesArr.length()).mapNotNull { i ->
            val t = pistesArr.getJSONObject(i)
            // Profiles may list approved reference tracks that are intentionally not
            // redistributed. Keep approval metadata in the package, but only expose
            // playable MP3s to the universal renderer/rotation pool.
            val path = t.optString("fichier", "").takeIf { it.isNotBlank() && it != "null" }
            if ((t.has("redistribution_mp3") && !t.optBoolean("redistribution_mp3")) || path == null) return@mapNotNull null
            ApprovedTrack(
                number = t.getInt("numero"),
                title = t.getString("titre"),
                artist = t.optString("artiste", t.optString("auteur", "")),
                licence = t.optString("licence", ""),
                credit = t.optString("credit", ""),
                relativePath = path,
                sha256 = t.optString("extrait_sha256", t.optString("sha256", "")),
                preMeasuredLufs = if (t.has("pre_measured_lufs")) t.getDouble("pre_measured_lufs") else null
            )
        }
        val mixJson = json.optJSONObject("mix") ?: JSONObject()
        val mix = AudioMixConfig(
            profileId = json.getString("id"),
            includeCaptureVoice = true,
            targetLufs = mixJson.optDouble("lufs", -16.0),
            bgGainDb = mixJson.optDouble("fond_db", -3.0),
            underSpeechGainDb = mixJson.optDouble("sous_parole_db", -10.0),
            attackMs = mixJson.optInt("attaque_ms", 120),
            releaseMs = mixJson.optInt("relachement_ms", 450),
            minimumVoiceMusicGapDb = if (mixJson.has("ecart_min_db")) mixJson.optDouble("ecart_min_db") else null
        )
        // T1/T3 use `voix_approuvee`/`mix_approuve`; T4 publishes the same
        // technical state as `voix_complete_validee`/`mix_valide`. Both spellings
        // remain authoritative and are never silently upgraded.
        val profile = AudioProfileSpec(
            id = json.getString("id"),
            version = json.optString("version", "1.0.0"),
            status = status,
            rotationMode = json.optString("rotation", "cycle_1_2_3_4_apres_succes"),
            approvedNumbers = approvedIds,
            voiceApproved = json.optBoolean("voix_approuvee", json.optBoolean("voix_complete_validee", true)),
            mixApproved = json.optBoolean("mix_approuve", json.optBoolean("mix_valide", true)),
            tracks = tracks,
            mix = mix
        )
        if (founderApprovalJson.isNullOrBlank()) return profile
        val approval = runCatching { FounderAudioApprovalParser.parseAll(founderApprovalJson) }
            .getOrNull()
            ?.firstOrNull { it.profileId == profile.id }
            ?: return profile
        return profile.copy(
            founderApprovalId = approval.approvalId,
            founderApprovedTrackNumbers = approval.approvedTrackNumbers,
            founderApprovedFiles = approval.approvedFiles
        )
    }

    private fun validateStructuralIntegrity(plan: RenderPlan) {
        require(plan.fps > 0) { "fps invalide: ${plan.fps}" }
        require(plan.width > 0 && plan.height > 0) { "dimensions invalides: ${plan.width}x${plan.height}" }
        require(plan.totalFrames > 0) { "nombre d'images invalide: ${plan.totalFrames}" }
        require(plan.scenes.isNotEmpty()) { "aucune scène définie" }

        var expectedStart = 0
        for (scene in plan.scenes) {
            require(scene.startFrame == expectedStart) {
                "Rupture de timeline à la scène ${scene.id}: attendu=$expectedStart, trouvé=${scene.startFrame}"
            }
            require(scene.endFrameExcluded > scene.startFrame) {
                "Durée nulle ou négative pour la scène ${scene.id}"
            }
            expectedStart = scene.endFrameExcluded
        }
        require(expectedStart == plan.totalFrames) {
            "Somme des scènes ($expectedStart) différente du total d'images (${plan.totalFrames})"
        }
        for (tr in plan.transitions) {
            require(tr.startFrame in 0 until plan.totalFrames) {
                "Transition hors timeline à l'image ${tr.startFrame}"
            }
            require(tr.startFrame + tr.durationFrames <= plan.totalFrames) {
                "Transition déborde la timeline à l'image ${tr.startFrame}"
            }
        }
    }
}
