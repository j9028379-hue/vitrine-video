package com.vitrinevideo.engine.pkg

import java.io.File
import java.io.InputStream

/**
 * Local versioned registry of verified template packages under [storageRoot].
 * Supports installing new ZIP packages across any sector with zero hardcoded template logic.
 */
class TemplateRegistry(private val storageRoot: File) {

    fun installZip(zipFile: File): LoadedTemplatePackage =
        TemplatePackageLoader.importZip(zipFile, storageRoot)

    fun installZipStream(inputStream: InputStream): LoadedTemplatePackage =
        TemplatePackageLoader.importZipStream(inputStream, storageRoot)

    fun listInstalled(): List<LoadedTemplatePackage> {
        if (!storageRoot.exists() || !storageRoot.isDirectory) return emptyList()
        val result = mutableListOf<LoadedTemplatePackage>()
        val templateDirs = storageRoot.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        for (tplDir in templateDirs.sortedBy { it.name }) {
            val versionDirs = tplDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
            for (verDir in versionDirs.sortedBy { it.name }) {
                if (File(verDir, "MANIFEST.json").exists()) {
                    result += TemplatePackageLoader.loadFromDirectory(verDir)
                }
            }
        }
        return result
    }

    fun get(templateId: String, version: String? = null): LoadedTemplatePackage? {
        val candidates = listInstalled().filter { it.templateId == templateId }
        return if (version != null) {
            candidates.firstOrNull { it.version == version }
        } else {
            candidates.lastOrNull()
        }
    }

    fun findBySector(sector: String): List<LoadedTemplatePackage> =
        listInstalled().filter { it.sector.equals(sector, ignoreCase = true) }
}
