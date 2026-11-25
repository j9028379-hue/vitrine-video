package com.vitrinevideo.engine.api

import com.vitrinevideo.engine.interpreter.AudioProfileSpec
import com.vitrinevideo.engine.interpreter.RenderPlan
import java.io.File

data class CaptureClip(
    val sceneId: String,
    val file: File,
    val expectedSha256: String,
    val validated: Boolean = true,
    val sessionId: String = ""
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
    val jobJsonRaw: String? = null,
    val sessionId: String = "",
    val rejectedPreviousSessionIds: Set<String> = emptySet(),
    val rejectedPreviousCaptureSha256s: Set<String> = emptySet(),
    /** Pre-parsed IR from a verified package; avoids re-parsing derived adapters. */
    val preparsedPlan: RenderPlan? = null,
    val preparsedAudioProfile: AudioProfileSpec? = null
)
