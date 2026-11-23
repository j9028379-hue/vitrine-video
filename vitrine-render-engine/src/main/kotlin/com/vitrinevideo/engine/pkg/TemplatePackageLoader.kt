package com.vitrinevideo.engine.pkg

import com.vitrinevideo.engine.capture.GuidedCaptureGuideParser
import com.vitrinevideo.engine.form.TemplateFormParser
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Universal ZIP package loader for VITRINE VIDÉO template packages.
 * Imports a template `.zip` archive into local versioned storage (`{storageRoot}/{templateId}/{version}/`),
 * verifies SHA-256 integrity against `MANIFEST.json`, resolves all paths relative to the package root,
 * validates cross-file ID/version coherence, and produces a [LoadedTemplatePackage] with zero template hardcoding.
 */
object TemplatePackageLoader {

    fun importZip(zipFile: File, storageRoot: File): LoadedTemplatePackage {
        if (!zipFile.exists() || !zipFile.isFile || zipFile.length() == 0L) {
            throw TemplatePackageException("ZIP_MISSING", "Archive ZIP introuvable ou vide: ${zipFile.path}")
        }
        return FileInputStream(zipFile).use { fis ->
            importZipStream(fis, storageRoot)
        }
    }

    fun importZipStream(inputStream: InputStream, storageRoot: File): LoadedTemplatePackage {
        storageRoot.mkdirs()
        val stagingDir = File(storageRoot, ".staging_${UUID.randomUUID()}")
        stagingDir.mkdirs()

        try {
            unzipSafely(inputStream, stagingDir)
            val packageRoot = resolvePackageRoot(stagingDir)
            val validated = loadFromDirectory(packageRoot)

            val finalDir = File(File(storageRoot, validated.templateId), validated.version)
            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            finalDir.parentFile?.mkdirs()
            if (!packageRoot.renameTo(finalDir)) {
                packageRoot.copyRecursively(finalDir, overwrite = true)
            }
            stagingDir.deleteRecursively()
            return loadFromDirectory(finalDir)
        } catch (e: TemplatePackageException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw TemplatePackageException("PACKAGE_IMPORT_FAILED", t.message ?: "Échec import paquet ZIP")
        }
    }

    fun loadFromDirectory(packageDir: File): LoadedTemplatePackage {
        if (!packageDir.exists() || !packageDir.isDirectory) {
            throw TemplatePackageException("PACKAGE_DIR_MISSING", "Répertoire paquet introuvable: ${packageDir.path}")
        }
        val rootCanonical = packageDir.canonicalFile

        val manifestFile = resolveSafe(rootCanonical, "MANIFEST.json")
        if (!manifestFile.exists()) {
            throw TemplatePackageException("MANIFEST_MISSING", "MANIFEST.json absent du paquet")
        }
        val manifest = parseAndVerifyManifest(manifestFile, rootCanonical)

        val catalogueFile = resolveSafe(rootCanonical, "catalogue/catalogue.json")
        if (!catalogueFile.exists()) {
            throw TemplatePackageException("CATALOGUE_MISSING", "catalogue/catalogue.json absent du paquet")
        }
        val catalogueEntry = parseCatalogue(catalogueFile)

        val partitionFile = resolveSafe(rootCanonical, catalogueEntry.contratRelPath)
        if (!partitionFile.exists()) {
            throw TemplatePackageException("PARTITION_MISSING", "Contrat introuvable: ${catalogueEntry.contratRelPath}")
        }
        val renderPlan = try {
            UniversalJsonInterpreter.parsePartition(partitionFile)
        } catch (t: Throwable) {
            throw TemplatePackageException("PARTITION_INVALID", "Partition invalide: ${t.message}")
        }

        val audioProfileFile = resolveSafe(rootCanonical, catalogueEntry.audioRelPath).takeIf { it.exists() }
        if (renderPlan.audioProfileId.isNotEmpty() && audioProfileFile == null) {
            throw TemplatePackageException("AUDIO_PROFILE_MISSING", "Profil audio requis absent: ${catalogueEntry.audioRelPath}")
        }
        val audioProfile = audioProfileFile?.let { f ->
            try {
                UniversalJsonInterpreter.parseAudioProfile(f)
            } catch (t: Throwable) {
                throw TemplatePackageException("AUDIO_PROFILE_INVALID", "Profil audio invalide: ${t.message}")
            }
        }

        val formFile = resolveSafe(rootCanonical, catalogueEntry.formRelPath)
        if (!formFile.exists()) {
            throw TemplatePackageException("FORM_MISSING", "Formulaire absent: ${catalogueEntry.formRelPath}")
        }
        val formSpec = try {
            TemplateFormParser.parseQuestions(formFile.readText())
        } catch (t: Throwable) {
            throw TemplatePackageException("FORM_INVALID", "Formulaire invalide: ${t.message}")
        }

        val captureGuideFile = resolveSafe(rootCanonical, catalogueEntry.captureGuideRelPath)
        if (!captureGuideFile.exists()) {
            throw TemplatePackageException("CAPTURE_GUIDE_MISSING", "Guide de capture absent: ${catalogueEntry.captureGuideRelPath}")
        }
        val captureGuides = GuidedCaptureGuideParser.parseFromEcransJson(captureGuideFile.readText())
        if (captureGuides.size != renderPlan.scenes.size) {
            throw TemplatePackageException(
                "CAPTURE_GUIDE_SCENE_MISMATCH",
                "Guide capture (${captureGuides.size} scènes) != partition (${renderPlan.scenes.size} scènes)"
            )
        }
        for (i in renderPlan.scenes.indices) {
            val sc = renderPlan.scenes[i]
            val guide = captureGuides[i]
            if (guide.sceneId != sc.id) {
                throw TemplatePackageException(
                    "CAPTURE_GUIDE_ORDER_MISMATCH",
                    "Scène guide ${guide.sceneId} != partition ${sc.id}"
                )
            }
            val expectedSec = (sc.endFrameExcluded - sc.startFrame).toDouble() / renderPlan.fps.toDouble()
            if (kotlin.math.abs(guide.durationSec - expectedSec) > 0.05) {
                throw TemplatePackageException(
                    "CAPTURE_GUIDE_DURATION_MISMATCH",
                    "Durée guide ${guide.sceneId} (${guide.durationSec}s) != partition (${expectedSec}s)"
                )
            }
        }

        val jobSchemaFile = resolveSafe(rootCanonical, catalogueEntry.jobSchemaRelPath).takeIf { it.exists() }
        val jobSchemaConstraints = jobSchemaFile?.let {
            TemplateFormParser.parseRenderJobSchema(it.readText())
        }

        val templateSchemaFile = resolveSafe(rootCanonical, catalogueEntry.templateSchemaRelPath).takeIf { it.exists() }
        val templateSchemaJson = templateSchemaFile?.let { JSONObject(it.readText()) }
        if (templateSchemaJson != null) {
            validatePartitionAgainstTemplateSchema(JSONObject(partitionFile.readText()), templateSchemaJson)
        }

        val validationFile = resolveSafe(rootCanonical, catalogueEntry.validationRelPath).takeIf { it.exists() }
        val approvalSummary = validationFile?.let { parseApprovalSummary(it) }

        // Cross-file version & ID coherence checks
        verifyCrossFileCoherence(
            manifest = manifest,
            catalogue = catalogueEntry,
            renderPlanTemplateId = renderPlan.templateId,
            renderPlanVersion = renderPlan.version,
            renderPlanAudioId = renderPlan.audioProfileId,
            audioProfile = audioProfile,
            jobSchemaConstraints = jobSchemaConstraints,
            approvalSummary = approvalSummary
        )

        // Verify required font asset and music track files relative to package root
        val fontRel = renderPlan.textOverlay?.fontRelativePath.orEmpty()
        if (fontRel.isNotEmpty()) {
            val fontFile = resolveSafe(rootCanonical, fontRel)
            if (!fontFile.exists() || fontFile.length() == 0L) {
                throw TemplatePackageException("FONT_MISSING", "Police requise absente: $fontRel")
            }
            val expectedFontSha = renderPlan.textOverlay?.fontSha256.orEmpty()
            if (expectedFontSha.isNotEmpty()) {
                val actualFontSha = PreFlightValidator.sha256File(fontFile)
                if (!actualFontSha.equals(expectedFontSha, ignoreCase = true)) {
                    throw TemplatePackageException(
                        "FONT_SHA_MISMATCH",
                        "Empreinte SHA-256 police invalide ($fontRel): attendu=$expectedFontSha, réel=$actualFontSha"
                    )
                }
            }
        }
        if (audioProfile != null) {
            if (audioProfile.tracks.isEmpty()) {
                throw TemplatePackageException("AUDIO_TRACKS_EMPTY", "Aucune piste musicale définie dans audio/profil.json")
            }
            for (track in audioProfile.tracks) {
                val trackFile = resolveSafe(rootCanonical, track.relativePath)
                if (!trackFile.exists() || trackFile.length() == 0L) {
                    throw TemplatePackageException("AUDIO_TRACK_MISSING", "Piste musicale absente: ${track.relativePath}")
                }
                if (track.sha256.isNotEmpty()) {
                    val actual = PreFlightValidator.sha256File(trackFile)
                    if (!actual.equals(track.sha256, ignoreCase = true)) {
                        throw TemplatePackageException(
                            "AUDIO_TRACK_SHA_MISMATCH",
                            "SHA-256 piste #${track.number} invalide (${track.relativePath})"
                        )
                    }
                }
            }
        }

        return LoadedTemplatePackage(
            packageDir = rootCanonical,
            manifestFile = manifestFile,
            partitionFile = partitionFile,
            audioProfileFile = audioProfileFile,
            formFile = formFile,
            captureGuideFile = captureGuideFile,
            jobSchemaFile = jobSchemaFile,
            manifest = manifest,
            catalogueEntry = catalogueEntry,
            renderPlan = renderPlan,
            audioProfile = audioProfile,
            formSpec = formSpec,
            captureGuides = captureGuides,
            jobSchemaConstraints = jobSchemaConstraints,
            templateSchemaJson = templateSchemaJson,
            approvalSummary = approvalSummary
        )
    }

    private fun unzipSafely(inputStream: InputStream, destDir: File) {
        val destCanonical = destDir.canonicalFile
        val destPrefix = destCanonical.path.trimEnd(File.separatorChar) + File.separatorChar
        var entriesCount = 0
        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entriesCount++
                val name = entry.name
                if (name.contains("..")) {
                    throw TemplatePackageException("ZIP_SLIP", "Entrée ZIP suspecte: $name")
                }
                val outFile = File(destCanonical, name).canonicalFile
                if (outFile.path != destCanonical.path && !outFile.path.startsWith(destPrefix)) {
                    throw TemplatePackageException("ZIP_SLIP", "Évasion ZIP interdite: $name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = zis.read(buf)
                            if (r <= 0) break
                            fos.write(buf, 0, r)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
        if (entriesCount == 0) {
            throw TemplatePackageException("ZIP_EMPTY", "Archive ZIP vide ou corrompue")
        }
    }

    private fun resolvePackageRoot(stagingDir: File): File {
        if (File(stagingDir, "MANIFEST.json").exists()) return stagingDir
        val subDirs = stagingDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        if (subDirs.size == 1 && File(subDirs[0], "MANIFEST.json").exists()) {
            return subDirs[0]
        }
        return stagingDir
    }

    private fun parseAndVerifyManifest(manifestFile: File, rootCanonical: File): TemplatePackageManifest {
        val json = try {
            JSONObject(manifestFile.readText())
        } catch (t: Throwable) {
            throw TemplatePackageException("MANIFEST_INVALID_JSON", "MANIFEST.json illisible: ${t.message}")
        }
        val schemaVersion = json.optString("schema_version", "1.0.0")
        val templateId = json.optString("template_id", "").trim()
        val version = json.optString("version", "").trim()
        val packageId = json.optString("package_id", if (templateId.isNotEmpty() && version.isNotEmpty()) "$templateId-$version" else "").trim()
        val generatedAt = json.optString("genere_le", "")
        if (version.isEmpty()) {
            throw TemplatePackageException("MANIFEST_MISSING_VERSION", "Champ 'version' absent de MANIFEST.json")
        }

        val entries = mutableListOf<TemplateManifestFileEntry>()
        val filesArr = json.optJSONArray("files") ?: json.optJSONArray("fichiers")
            ?: throw TemplatePackageException("MANIFEST_MISSING_FILES", "Tableau 'files'/'fichiers' absent de MANIFEST.json")

        for (i in 0 until filesArr.length()) {
            val obj = filesArr.getJSONObject(i)
            val rel = obj.optString("path", "").ifBlank { obj.getString("chemin") }
            val sha = obj.getString("sha256")
            val bytes = obj.optLong("bytes", -1L)
            val target = resolveSafe(rootCanonical, rel)
            if (!target.exists() || !target.isFile) {
                throw TemplatePackageException("MANIFEST_FILE_MISSING", "Fichier manquant dans le paquet: $rel")
            }
            if (bytes >= 0L && target.length() != bytes) {
                throw TemplatePackageException(
                    "MANIFEST_SIZE_MISMATCH",
                    "Taille invalide pour $rel (attendu=$bytes, réel=${target.length()})"
                )
            }
            val actualSha = PreFlightValidator.sha256File(target)
            if (!actualSha.equals(sha, ignoreCase = true)) {
                throw TemplatePackageException(
                    "MANIFEST_SHA_MISMATCH",
                    "Empreinte SHA-256 invalide pour $rel"
                )
            }
            entries += TemplateManifestFileEntry(rel, sha, bytes)
        }
        return TemplatePackageManifest(schemaVersion, packageId, version, generatedAt, entries)
    }

    private fun parseCatalogue(catalogueFile: File): TemplateCatalogueEntry {
        val root = try {
            JSONObject(catalogueFile.readText())
        } catch (t: Throwable) {
            throw TemplatePackageException("CATALOGUE_INVALID_JSON", "catalogue.json invalide: ${t.message}")
        }
        val tplObj = when {
            root.has("entries") -> {
                val arr = root.getJSONArray("entries")
                if (arr.length() == 0) {
                    throw TemplatePackageException("CATALOGUE_EMPTY", "Catalogue sans aucune entrée")
                }
                arr.getJSONObject(0)
            }
            root.has("templates") -> {
                val arr = root.getJSONArray("templates")
                if (arr.length() == 0) {
                    throw TemplatePackageException("CATALOGUE_EMPTY", "Catalogue sans aucun gabarit")
                }
                arr.getJSONObject(0)
            }
            else -> root
        }

        val templateId = tplObj.optString("template_id", "").trim()
        val version = tplObj.optString("version", "").trim()
        if (templateId.isEmpty() || version.isEmpty()) {
            throw TemplatePackageException("CATALOGUE_MISSING_ID_OR_VERSION", "template_id ou version absent du catalogue")
        }
        return TemplateCatalogueEntry(
            templateId = templateId,
            version = version,
            title = tplObj.optString("titre", templateId),
            sector = tplObj.optString("secteur", ""),
            status = tplObj.optString("statut", ""),
            durationSec = tplObj.optDouble("duree_s", 0.0),
            format = tplObj.optString("format", ""),
            contratRelPath = tplObj.optString("contrat", "montage/partition.json"),
            audioRelPath = tplObj.optString("audio", "audio/profil.json"),
            formRelPath = tplObj.optString("formulaire", "form/questions.json"),
            captureGuideRelPath = tplObj.optString("cartes", "capture-guide/ecrans.json"),
            jobSchemaRelPath = tplObj.optString("schema_job", "schemas/render-job.json"),
            validationRelPath = tplObj.optString("validation", "approvals/validation.json")
        )
    }

    private fun parseApprovalSummary(validationFile: File): TemplateApprovalSummary? {
        val json = runCatching { JSONObject(validationFile.readText()) }.getOrNull() ?: return null
        val idsArr = json.optJSONArray("pistes_musicales") ?: JSONArray()
        val musicIds = (0 until idsArr.length()).map { idsArr.getInt(it) }
        return TemplateApprovalSummary(
            templateId = json.optString("template_id", ""),
            version = json.optString("version", ""),
            status = json.optString("decision", json.optString("statut", "")),
            approvedResultSha256 = json.optString("master_sha256", json.optString("resultat_sha256", "")).ifBlank { null },
            approvedMusicIds = musicIds
        )
    }

    private fun validatePartitionAgainstTemplateSchema(partitionJson: JSONObject, templateSchema: JSONObject) {
        val required = templateSchema.optJSONArray("required") ?: return
        for (i in 0 until required.length()) {
            val reqKey = required.getString(i)
            if (!partitionJson.has(reqKey)) {
                throw TemplatePackageException(
                    "TEMPLATE_SCHEMA_VIOLATION",
                    "Champ requis par schemas/template.json absent de partition.json: $reqKey"
                )
            }
        }
    }

    private fun verifyCrossFileCoherence(
        manifest: TemplatePackageManifest,
        catalogue: TemplateCatalogueEntry,
        renderPlanTemplateId: String,
        renderPlanVersion: String,
        renderPlanAudioId: String,
        audioProfile: com.vitrinevideo.engine.interpreter.AudioProfileSpec?,
        jobSchemaConstraints: com.vitrinevideo.engine.form.RenderJobSchemaConstraints?,
        approvalSummary: TemplateApprovalSummary?
    ) {
        if (manifest.version != catalogue.version) {
            throw TemplatePackageException(
                "VERSION_MISMATCH",
                "Version MANIFEST (${manifest.version}) != catalogue (${catalogue.version})"
            )
        }
        if (catalogue.templateId != renderPlanTemplateId) {
            throw TemplatePackageException(
                "TEMPLATE_ID_MISMATCH",
                "template_id catalogue (${catalogue.templateId}) != partition ($renderPlanTemplateId)"
            )
        }
        if (catalogue.version != renderPlanVersion) {
            throw TemplatePackageException(
                "VERSION_MISMATCH",
                "Version catalogue (${catalogue.version}) != partition ($renderPlanVersion)"
            )
        }
        if (manifest.packageId.isNotEmpty()) {
            val expectedPkgId = "${catalogue.templateId}-${catalogue.version}"
            if (manifest.packageId != expectedPkgId && !manifest.packageId.startsWith(catalogue.templateId)) {
                throw TemplatePackageException(
                    "PACKAGE_ID_MISMATCH",
                    "package_id MANIFEST (${manifest.packageId}) incohérent avec $expectedPkgId"
                )
            }
        }
        if (audioProfile != null) {
            if (audioProfile.version != catalogue.version) {
                throw TemplatePackageException(
                    "AUDIO_VERSION_MISMATCH",
                    "Version audio (${audioProfile.version}) != catalogue (${catalogue.version})"
                )
            }
            if (renderPlanAudioId.isNotEmpty() && audioProfile.id != renderPlanAudioId) {
                throw TemplatePackageException(
                    "AUDIO_ID_MISMATCH",
                    "ID profil audio (${audioProfile.id}) != partition ($renderPlanAudioId)"
                )
            }
        }
        if (jobSchemaConstraints != null) {
            val expectedId = jobSchemaConstraints.expectedTemplateId
            if (expectedId != null && expectedId != catalogue.templateId) {
                throw TemplatePackageException(
                    "JOB_SCHEMA_TEMPLATE_MISMATCH",
                    "template_id schema_job ($expectedId) != catalogue (${catalogue.templateId})"
                )
            }
            val expectedVer = jobSchemaConstraints.expectedTemplateVersion
            if (expectedVer != null && expectedVer != catalogue.version) {
                throw TemplatePackageException(
                    "JOB_SCHEMA_VERSION_MISMATCH",
                    "version schema_job ($expectedVer) != catalogue (${catalogue.version})"
                )
            }
        }
        if (approvalSummary != null) {
            if (approvalSummary.templateId.isNotEmpty() && approvalSummary.templateId != catalogue.templateId) {
                throw TemplatePackageException(
                    "APPROVAL_TEMPLATE_MISMATCH",
                    "template_id validation (${approvalSummary.templateId}) != catalogue (${catalogue.templateId})"
                )
            }
            if (approvalSummary.version.isNotEmpty() && approvalSummary.version != catalogue.version) {
                throw TemplatePackageException(
                    "APPROVAL_VERSION_MISMATCH",
                    "version validation (${approvalSummary.version}) != catalogue (${catalogue.version})"
                )
            }
        }
    }

    private fun resolveSafe(rootCanonical: File, relPath: String): File {
        if (relPath.isBlank() || File(relPath).isAbsolute || relPath.contains("..")) {
            throw TemplatePackageException("UNSAFE_PATH", "Chemin relatif invalide: $relPath")
        }
        val target = File(rootCanonical, relPath).canonicalFile
        val prefix = rootCanonical.path.trimEnd(File.separatorChar) + File.separatorChar
        if (target.path != rootCanonical.path && !target.path.startsWith(prefix)) {
            throw TemplatePackageException("UNSAFE_PATH", "Évasion de répertoire détectée: $relPath")
        }
        return target
    }
}
