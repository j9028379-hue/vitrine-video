package com.vitrinevideo.engine

import java.io.File

/** Locates embedded template ZIPs from any Gradle module working directory. */
internal object TestAssetLocator {
    fun projectFile(relativePath: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return dir
        }
        error("Project file $relativePath not found from ${System.getProperty("user.dir")}")
    }

    fun packageZip(name: String): File =
        projectFile("vitrine-video-app/src/main/assets/packages/$name").also { check(it.isFile) }
}
