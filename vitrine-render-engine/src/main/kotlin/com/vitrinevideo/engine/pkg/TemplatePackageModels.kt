package com.vitrinevideo.engine.pkg

import com.vitrinevideo.engine.capture.SceneCaptureGuideSpec
import com.vitrinevideo.engine.form.RenderJobSchemaConstraints
import com.vitrinevideo.engine.form.TemplateFormSpec
import com.vitrinevideo.engine.interpreter.AudioProfileSpec
import com.vitrinevideo.engine.interpreter.RenderPlan
import org.json.JSONObject
import java.io.File

class TemplatePackageException(val code: String, message: String) : IllegalStateException(message)

data class TemplateManifestFileEntry(
    val relativePath: String,
    val sha256: String,
    val bytes: Long
)

data class TemplatePackageManifest(
    val schemaVersion: String,
    val packageId: String,
    val version: String,
    val generatedAt: String,
    val files: List<TemplateManifestFileEntry>
)

data class TemplateCatalogueEntry(
    val templateId: String,
    val version: String,
    val title: String,
    val sector: String,
    val status: String,
    val durationSec: Double,
    val format: String,
    val contratRelPath: String,
    val audioRelPath: String,
    val formRelPath: String,
    val captureGuideRelPath: String,
    val jobSchemaRelPath: String,
    val validationRelPath: String,
    val templateSchemaRelPath: String = "schemas/template.json",
    val renderResultSchemaRelPath: String = "schemas/render-result.json"
)

data class TemplateApprovalSummary(
    val templateId: String,
    val version: String,
    val status: String,
    val approvedResultSha256: String?,
    val approvedMusicIds: List<Int>
)

/**
 * Self-contained, verified template package unpacked from a universal ZIP archive.
 * All paths remain strictly relative to [packageDir]; zero template-specific constants.
 */
data class LoadedTemplatePackage(
    val packageDir: File,
    val manifestFile: File,
    val partitionFile: File,
    val audioProfileFile: File?,
    val formFile: File,
    val captureGuideFile: File,
    val jobSchemaFile: File?,
    val manifest: TemplatePackageManifest,
    val catalogueEntry: TemplateCatalogueEntry,
    val renderPlan: RenderPlan,
    val audioProfile: AudioProfileSpec?,
    val formSpec: TemplateFormSpec,
    val captureGuides: List<SceneCaptureGuideSpec>,
    val jobSchemaConstraints: RenderJobSchemaConstraints?,
    val templateSchemaJson: JSONObject?,
    val approvalSummary: TemplateApprovalSummary?
) {
    val templateId: String get() = catalogueEntry.templateId
    val version: String get() = catalogueEntry.version
    val sector: String get() = catalogueEntry.sector
    val title: String get() = catalogueEntry.title

    fun resolvePackageRelative(relPath: String): File {
        if (File(relPath).isAbsolute || relPath.contains("..")) {
            throw TemplatePackageException("UNSAFE_PATH", "Chemin hors paquet interdit: $relPath")
        }
        val rootCanonical = packageDir.canonicalFile
        val resolved = File(rootCanonical, relPath).canonicalFile
        val prefix = rootCanonical.path.trimEnd(File.separatorChar) + File.separatorChar
        if (resolved.path != rootCanonical.path && !resolved.path.startsWith(prefix)) {
            throw TemplatePackageException("UNSAFE_PATH", "Évasion de répertoire interdite: $relPath")
        }
        return resolved
    }
}
