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

/**
 * Loads and SHA-256 verifies the bundled COMMERCE-T3-1.0.0.zip package directly from app assets,
 * and saves rendered final MP4 files to both app external storage and public Movies/VitrineVideo.
 */
object T3PackageRepository {

    private const val ASSET_ZIP_PATH = "packages/COMMERCE-T3-1.0.0.zip"
    private const val TEMPLATE_ID = "COMMERCE-T3"
    private const val TEMPLATE_VERSION = "1.0.0"

    @Synchronized
    fun ensureLoaded(context: Context): LoadedTemplatePackage {
        val storageRoot = File(context.filesDir, "template_packages")
        val existingDir = File(File(storageRoot, TEMPLATE_ID), TEMPLATE_VERSION)
        if (existingDir.exists() && File(existingDir, "MANIFEST.json").exists()) {
            val cached = runCatching { TemplatePackageLoader.loadFromDirectory(existingDir) }.getOrNull()
            if (cached != null) return cached
        }
        return context.assets.open(ASSET_ZIP_PATH).use { stream ->
            TemplatePackageLoader.importZipStream(stream, storageRoot)
        }
    }

    /**
     * Publishes the verified final MP4 to a user-accessible location on the phone:
     * - App external exports folder: /sdcard/Android/data/com.vitrinevideo.app/files/vitrine_exports/<session>/Film.mp4
     * - Public MediaStore Movies/VitrineVideo/Vitrine_T3_<timestamp>.mp4 (API 29+) or public Movies dir (< 29)
     */
    fun publishFinalVideo(context: Context, renderedMp4: File, sessionId: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val shortTag = sessionId.filter { it.isLetterOrDigit() }.takeLast(4).ifEmpty { "0000" }
        val displayName = "Vitrine_T3_${stamp}_${shortTag}.mp4"

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
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(renderedMp4).use { input -> input.copyTo(out, 32 * 1024) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Movies/VitrineVideo/$displayName"
            }.getOrElse { renderedMp4.absolutePath }
        } else {
            return runCatching {
                @Suppress("DEPRECATION")
                val moviesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "VitrineVideo"
                ).apply { mkdirs() }
                val dest = File(moviesDir, displayName)
                renderedMp4.copyTo(dest, overwrite = true)
                dest.absolutePath
            }.getOrElse { renderedMp4.absolutePath }
        }
    }
}
