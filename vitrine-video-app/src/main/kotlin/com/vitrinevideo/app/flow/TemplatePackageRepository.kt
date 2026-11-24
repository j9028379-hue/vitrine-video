package com.vitrinevideo.app.flow

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vitrinevideo.engine.pkg.LoadedTemplatePackage
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Loads and verifies bundled Commerce templates, preserving independent package caches. */
object TemplatePackageRepository {
    private data class Asset(val path: String, val version: String)
    private val supported = mapOf(
        "COMMERCE-T1" to Asset("packages/COMMERCE-T1-2.0.0.zip", "2.0.0"),
        "COMMERCE-T3" to Asset("packages/COMMERCE-T3-1.0.0.zip", "1.0.0")
    )

    @Synchronized
    fun ensureLoaded(context: Context, templateId: String): LoadedTemplatePackage {
        val asset = supported[templateId] ?: throw IllegalArgumentException("Gabarit Commerce non pris en charge: $templateId")
        val storageRoot = File(context.filesDir, "template_packages")
        val existingDir = File(File(storageRoot, templateId), asset.version)
        if (existingDir.exists() && File(existingDir, "MANIFEST.json").exists()) {
            val cached = runCatching { TemplatePackageLoader.loadFromDirectory(existingDir) }.getOrNull()
            if (cached != null) return cached
        }
        return context.assets.open(asset.path).use { TemplatePackageLoader.importZipStream(it, storageRoot) }
    }

    fun publishFinalVideo(context: Context, renderedMp4: File, sessionId: String, templateId: String): String {
        val tag = templateId.removePrefix("COMMERCE-").replace(Regex("[^A-Za-z0-9_-]"), "")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val shortTag = sessionId.filter { it.isLetterOrDigit() }.takeLast(4).ifEmpty { "0000" }
        val displayName = "Vitrine_${tag}_${stamp}_${shortTag}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VitrineVideo")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching renderedMp4.absolutePath
                resolver.openOutputStream(uri)?.use { out -> FileInputStream(renderedMp4).use { it.copyTo(out, 32 * 1024) } }
                values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Movies/VitrineVideo/$displayName"
            }.getOrElse { renderedMp4.absolutePath }
        }
        return runCatching {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VitrineVideo").apply { mkdirs() }
            val dest = File(dir, displayName)
            renderedMp4.copyTo(dest, overwrite = true)
            dest.absolutePath
        }.getOrElse { renderedMp4.absolutePath }
    }
}
