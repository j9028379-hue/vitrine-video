package com.vitrinevideo.engine.timeline

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.vitrinevideo.engine.api.CaptureClip
import com.vitrinevideo.engine.api.EngineConfig
import com.vitrinevideo.engine.api.RenderRequest
import com.vitrinevideo.engine.capture.AndroidMediaExtractorProbe
import com.vitrinevideo.engine.capture.VideoMetadataProbe
import com.vitrinevideo.engine.interpreter.AudioProfileSpec
import com.vitrinevideo.engine.interpreter.RenderPlan
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class ValidatedCapture(
    val sceneId: String,
    val file: File,
    val sha256: String,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val hasAudio: Boolean,
    val rotation: Int = 0
)

data class PreFlightReport(
    val validatedCaptures: Map<String, ValidatedCapture>,
    val freeStorageBytes: Long,
    val availableRamMb: Long,
    val isLowRamDevice: Boolean,
    val runId: String
)

class PreFlightException(val code: String, message: String) : IllegalStateException(message)

object PreFlightValidator {

    @JvmOverloads
    fun validate(
        context: Context?,
        request: RenderRequest,
        plan: RenderPlan,
        audioProfile: AudioProfileSpec?,
        config: EngineConfig,
        probe: VideoMetadataProbe = AndroidMediaExtractorProbe
    ): PreFlightReport {
        // 0. Session isolation guard
        if (request.sessionId.isNotBlank() && request.sessionId in request.rejectedPreviousSessionIds) {
            throw PreFlightException(
                "SESSION_ALREADY_CLOSED",
                "La session ${request.sessionId} est déjà clôturée et ne peut pas être réutilisée."
            )
        }

        // 1. Storage guard
        request.outputDir.mkdirs()
        val statBytes = runCatching { StatFs(request.outputDir.absolutePath).availableBytes }.getOrDefault(0L)
        val freeBytes = if (statBytes > 0L) statBytes else request.outputDir.usableSpace
        if (freeBytes < config.minFreeStorageBytes) {
            throw PreFlightException(
                "INSUFFICIENT_STORAGE",
                "Espace disque insuffisant (${freeBytes / (1024 * 1024)} Mo libres, minimum ${config.minFreeStorageBytes / (1024 * 1024)} Mo)"
            )
        }

        // 2. RAM guard (monitoring only; never degrades JSON quality)
        var availRamMb = 0L
        var lowRam = false
        if (context != null) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                availRamMb = mi.availMem / (1024L * 1024L)
                lowRam = mi.lowMemory || (mi.totalMem / (1024L * 1024L)) <= config.lowRamThresholdMb
            }
        }

        // 3. Visited screens / completeness check if declared
        if (request.expectedScreenCount != null && request.visitedScreens.isNotEmpty()) {
            val expected = (1..request.expectedScreenCount).toList()
            if (request.visitedScreens != expected) {
                throw PreFlightException(
                    "INCOMPLETE_FLOW",
                    "Écrans visités incomplets: ${request.visitedScreens} != $expected"
                )
            }
        }

        // 4. Template & manifest integrity
        if (request.templateId != plan.templateId) {
            throw PreFlightException(
                "TEMPLATE_MISMATCH",
                "template_id job (${request.templateId}) != partition (${plan.templateId})"
            )
        }
        request.manifestJsonFile?.let { mf ->
            if (!mf.exists()) throw PreFlightException("MANIFEST_MISSING", "MANIFEST.json introuvable")
            verifyManifestHashes(mf, request.resourcesDir)
        }

        // 5. Audio profile & approved tracks check
        if (plan.audioProfileId.isNotEmpty()) {
            val profile = audioProfile
                ?: throw PreFlightException("AUDIO_PROFILE_MISSING", "Profil audio requis (${plan.audioProfileId}) absent")
            if (profile.id != plan.audioProfileId) {
                throw PreFlightException("AUDIO_PROFILE_MISMATCH", "Profil audio ${profile.id} != ${plan.audioProfileId}")
            }
            if (!profile.voiceApproved) {
                throw PreFlightException("AUDIO_NOT_APPROVED", "Voix du profil audio non approuvée pour export")
            }
            // A founder record is an explicit commercial approval of the playable
            // selection. It does not rewrite `mix_valide`, which stays a technical
            // state and remains visible in reports.
            if (!profile.mixApproved && !profile.hasFounderApprovedSelection) {
                throw PreFlightException(
                    "AUDIO_NOT_APPROVED",
                    "Mix techniquement non validé et aucune approbation fondateur couvrant toutes les pistes lisibles"
                )
            }
        }

        // 6. Verify font asset if text overlay requires it
        val fontRel = plan.textOverlay?.fontRelativePath.orEmpty()
        if (fontRel.isNotEmpty()) {
            val fontFile = File(request.resourcesDir, fontRel)
            if (!fontFile.exists()) throw PreFlightException("FONT_MISSING", "Police requise introuvable: ${fontFile.path}")
            val expectedFontSha = plan.textOverlay?.fontSha256.orEmpty()
            if (expectedFontSha.isNotEmpty() && !sha256File(fontFile).equals(expectedFontSha, ignoreCase = true)) {
                throw PreFlightException("FONT_SHA_MISMATCH", "Empreinte police invalide pour $fontRel")
            }
        }
        plan.textOverlay?.let { text ->
            val expectedByPath = linkedMapOf<String, String>()
            text.extraFontPaths.forEach { (family, rel) -> expectedByPath[rel] = text.extraFontSha256s[family].orEmpty() }
            text.windows.forEach { w ->
                w.fontRelativePath?.let { expectedByPath[it] = w.fontSha256.orEmpty() }
                w.fallbackFontRelativePath?.let { rel -> expectedByPath.putIfAbsent(rel, text.extraFontSha256s["fallback"].orEmpty()) }
            }
            expectedByPath.forEach { (rel, expected) ->
                val file = File(request.resourcesDir, rel)
                if (!file.exists() || file.length() == 0L) throw PreFlightException("FONT_MISSING", "Police requise introuvable: ${file.path}")
                if (expected.isNotBlank() && !sha256File(file).equals(expected, ignoreCase = true)) throw PreFlightException("FONT_SHA_MISMATCH", "Empreinte police invalide pour $rel")
            }
        }

        // 7. Validate all captures (order, uniqueness, session ownership, SHA-256, video stream + duration)
        val expectedSceneIds = plan.scenes.map { it.id }
        val providedIds = request.captures.map { it.sceneId }
        if (providedIds != expectedSceneIds) {
            throw PreFlightException(
                "SCENES_MISMATCH",
                "Captures fournies $providedIds != scènes attendues $expectedSceneIds"
            )
        }

        val seenHashes = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()
        val rejectedHashesLower = request.rejectedPreviousCaptureSha256s.map { it.lowercase() }.toSet()
        val validatedMap = linkedMapOf<String, ValidatedCapture>()
        val shaPairs = mutableListOf<Pair<String, String>>()

        for (cap in request.captures) {
            val vc = validateSingleCapture(
                cap = cap,
                requestSessionId = request.sessionId,
                rejectedPreviousHashesLower = rejectedHashesLower,
                plan = plan,
                seenHashes = seenHashes,
                seenPaths = seenPaths,
                probe = probe
            )
            validatedMap[cap.sceneId] = vc
            shaPairs += cap.sceneId to vc.sha256
        }

        val runId = TimelineEngine.computeRunId(
            templateId = plan.templateId,
            templateVersion = plan.version,
            responses = request.responses,
            captureSha256s = shaPairs
        )

        return PreFlightReport(
            validatedCaptures = validatedMap,
            freeStorageBytes = freeBytes,
            availableRamMb = availRamMb,
            isLowRamDevice = lowRam,
            runId = runId
        )
    }

    private fun validateSingleCapture(
        cap: CaptureClip,
        requestSessionId: String,
        rejectedPreviousHashesLower: Set<String>,
        plan: RenderPlan,
        seenHashes: MutableSet<String>,
        seenPaths: MutableSet<String>,
        probe: VideoMetadataProbe
    ): ValidatedCapture {
        if (!cap.validated) {
            throw PreFlightException("CAPTURE_NOT_VALIDATED", "Capture ${cap.sceneId} non validée")
        }
        if (requestSessionId.isNotBlank()) {
            if (cap.sessionId.isNotBlank() && cap.sessionId != requestSessionId) {
                throw PreFlightException(
                    "CROSS_SESSION_CAPTURE_REJECTED",
                    "Capture ${cap.sceneId} appartient à une ancienne session (${cap.sessionId} != $requestSessionId)"
                )
            }
            val pathSessionOwner = extractOwnedSessionIdFromPath(cap.file)
            if (pathSessionOwner != null && pathSessionOwner != requestSessionId) {
                throw PreFlightException(
                    "CROSS_SESSION_FILE_OWNERSHIP_VIOLATION",
                    "Fichier ${cap.file.path} appartient au répertoire d'une autre session ($pathSessionOwner != $requestSessionId)"
                )
            }
        }
        if (!cap.file.exists() || !cap.file.isFile || cap.file.length() == 0L || !cap.file.canRead()) {
            throw PreFlightException("CAPTURE_MISSING", "Fichier capture introuvable, vide ou illisible: ${cap.file.path}")
        }
        val canonical = cap.file.canonicalPath
        if (!seenPaths.add(canonical)) {
            throw PreFlightException("DUPLICATE_CAPTURE", "Même fichier vidéo réutilisé sur ${cap.sceneId}")
        }
        val actualSha = sha256File(cap.file).lowercase()
        if (actualSha in rejectedPreviousHashesLower) {
            throw PreFlightException(
                "OLD_SESSION_HASH_REUSED",
                "Vidéo déjà utilisée dans une création précédente détectée sur ${cap.sceneId}"
            )
        }
        if (cap.expectedSha256.isNotEmpty() && !actualSha.equals(cap.expectedSha256, ignoreCase = true)) {
            throw PreFlightException(
                "CAPTURE_SHA_MISMATCH",
                "Empreinte SHA-256 invalide pour ${cap.sceneId}: attendu=${cap.expectedSha256}, réel=$actualSha"
            )
        }
        if (!seenHashes.add(actualSha)) {
            throw PreFlightException("DUPLICATE_CAPTURE", "Capture dupliquée détectée sur ${cap.sceneId}")
        }

        val sceneSpec = plan.scenes.first { it.id == cap.sceneId }
        val requiredSourceFrames = sceneSpec.shots.maxOfOrNull { it.sourceOutFrame }
            ?: (sceneSpec.endFrameExcluded - sceneSpec.startFrame)
        val requiredDurationUs = (requiredSourceFrames.toLong() * 1_000_000L) / plan.fps

        val probed = probe.probe(cap.file)
            ?: throw PreFlightException("INVALID_VIDEO_STREAM", "Fichier vidéo illisible ou conteneur MP4 corrompu pour ${cap.sceneId}")
        if (!probed.hasVideo || probed.width <= 0 || probed.height <= 0) {
            throw PreFlightException("INVALID_VIDEO_STREAM", "Aucune piste vidéo valide dans ${cap.sceneId}")
        }
        val allowFreeze = sceneSpec.shots.any { it.freezeTailFrames > 0 }
        if (!allowFreeze && probed.durationUs > 0L && probed.durationUs + 150_000L < requiredDurationUs) {
            throw PreFlightException(
                "CAPTURE_TOO_SHORT",
                "Capture ${cap.sceneId} trop courte (${probed.durationUs / 1000} ms < ${requiredDurationUs / 1000} ms requis)"
            )
        }
        return ValidatedCapture(
            sceneId = cap.sceneId,
            file = cap.file,
            sha256 = actualSha,
            width = probed.width,
            height = probed.height,
            durationUs = probed.durationUs,
            hasAudio = probed.hasAudio,
            rotation = probed.rotation
        )
    }

    private fun extractOwnedSessionIdFromPath(file: File): String? {
        val parts = file.invariantSeparatorsPath.split('/')
        for (i in 0 until parts.size - 1) {
            val dir = parts[i]
            if ((dir == "sessions_tmp" || dir == "captures") && i + 1 < parts.size) {
                val candidate = parts[i + 1]
                if (candidate.isNotBlank() && !candidate.endsWith(".mp4", ignoreCase = true)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun verifyManifestHashes(manifestFile: File, rootDir: File) {
        val json = JSONObject(manifestFile.readText())
        val rootCanonical = rootDir.canonicalFile
        val filesArr = json.optJSONArray("files") ?: json.optJSONArray("fichiers")
        if (filesArr != null) {
            for (i in 0 until filesArr.length()) {
                val entry = filesArr.getJSONObject(i)
                val rel = entry.optString("path", "").ifBlank { entry.getString("chemin") }
                val expectedSha = entry.getString("sha256")
                val expectedBytes = entry.optLong("bytes", -1L)
                val target = resolveSafeRelative(rootCanonical, rel)
                if (!target.exists()) {
                    throw PreFlightException("MANIFEST_FILE_MISSING", "Fichier du manifeste absent: $rel")
                }
                if (expectedBytes >= 0L && target.length() != expectedBytes) {
                    throw PreFlightException(
                        "MANIFEST_SIZE_MISMATCH",
                        "Taille invalide pour $rel: attendu=$expectedBytes, réel=${target.length()}"
                    )
                }
                val actual = sha256File(target)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    throw PreFlightException("MANIFEST_HASH_MISMATCH", "Altération détectée sur $rel")
                }
            }
            return
        }
        val filesObj = json.optJSONObject("fichiers") ?: return
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val rel = keys.next()
            val expected = filesObj.getString(rel)
            val target = resolveSafeRelative(rootCanonical, rel)
            if (!target.exists()) {
                throw PreFlightException("MANIFEST_FILE_MISSING", "Fichier du manifeste absent: $rel")
            }
            val actual = sha256File(target)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw PreFlightException("MANIFEST_HASH_MISMATCH", "Altération détectée sur $rel")
            }
        }
    }

    private fun resolveSafeRelative(rootCanonical: File, relPath: String): File {
        if (File(relPath).isAbsolute || relPath.contains("..")) {
            throw PreFlightException("UNSAFE_RELATIVE_PATH", "Chemin relatif non autorisé: $relPath")
        }
        val target = File(rootCanonical, relPath).canonicalFile
        val rootPrefix = rootCanonical.path.trimEnd(File.separatorChar) + File.separatorChar
        if (target.path != rootCanonical.path && !target.path.startsWith(rootPrefix)) {
            throw PreFlightException("UNSAFE_RELATIVE_PATH", "Évasion de répertoire interdite: $relPath")
        }
        return target
    }

    fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
