package com.vitrinevideo.engine.interpreter

import com.vitrinevideo.engine.capabilities.EngineCapabilities
import org.json.JSONObject
import java.io.File

object UniversalJsonInterpreter {

    fun parsePartition(partitionFile: File): RenderPlan {
        require(partitionFile.exists()) { "Fichier partition introuvable: ${partitionFile.path}" }
        return parsePartitionJson(partitionFile.readText())
    }

    fun parsePartitionJson(rawJson: String): RenderPlan {
        val json = JSONObject(rawJson)
        val plan = when {
            PartitionV1Parser.canParse(json) -> PartitionV1Parser.parse(json)
            EngineeringSpecParser.canParse(json) -> EngineeringSpecParser.parse(json)
            else -> throw IllegalArgumentException("Format JSON de partition non reconnu")
        }
        EngineCapabilities.assertSupported(plan.requiredCapabilities, plan.unknownCapabilities)
        validateStructuralIntegrity(plan)
        return plan
    }

    fun parseAudioProfile(profileFile: File): AudioProfileSpec {
        require(profileFile.exists()) { "Fichier profil audio introuvable: ${profileFile.path}" }
        return parseAudioProfileJson(profileFile.readText())
    }

    fun parseAudioProfileJson(rawJson: String): AudioProfileSpec {
        val json = JSONObject(rawJson)
        val status = json.optString("statut", "validé")
        val approvedArr = json.getJSONArray("pistes_approuvees")
        val approvedIds = (0 until approvedArr.length()).map { approvedArr.getInt(it) }
        val pistesArr = json.getJSONArray("pistes")
        val tracks = (0 until pistesArr.length()).map { i ->
            val t = pistesArr.getJSONObject(i)
            ApprovedTrack(
                number = t.getInt("numero"),
                title = t.getString("titre"),
                artist = t.optString("artiste", t.optString("auteur", "")),
                licence = t.getString("licence"),
                credit = t.getString("credit"),
                relativePath = t.getString("fichier"),
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
            releaseMs = mixJson.optInt("relachement_ms", 450)
        )
        return AudioProfileSpec(
            id = json.getString("id"),
            version = json.optString("version", "1.0.0"),
            status = status,
            rotationMode = json.optString("rotation", "cycle_1_2_3_4_apres_succes"),
            approvedNumbers = approvedIds,
            voiceApproved = json.optBoolean("voix_approuvee", true),
            mixApproved = json.optBoolean("mix_approuve", true),
            tracks = tracks,
            mix = mix
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
