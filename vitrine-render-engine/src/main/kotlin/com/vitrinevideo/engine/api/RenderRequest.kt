package com.vitrinevideo.engine.api

import java.io.File

data class CaptureClip(
    val sceneId: String,
    val file: File,
    val expectedSha256: String,
    val validated: Boolean = true
)

data class RenderRequest(
    val templateId: String,
    val templateVersion: String,
    val partitionJsonFile: File,
    val audioProfileJsonFile: File?,
    val resourcesDir: File,
    val responses: Map<String, String>,
    val captures: List<CaptureClip>,
    val visitedScreens: List<Int> = emptyList(),
    val expectedScreenCount: Int? = null,
    val outputDir: File,
    val rotationDbFile: File,
    val manifestJsonFile: File? = null,
    val jobJsonRaw: String? = null
)
