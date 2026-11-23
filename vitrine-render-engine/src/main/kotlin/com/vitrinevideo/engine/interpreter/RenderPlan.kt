package com.vitrinevideo.engine.interpreter

import com.vitrinevideo.engine.capabilities.CapabilityId

data class ViewportSpec(
    val cx: Double,
    val cy: Double,
    val zoom: Double
)

data class GridCellCut(
    val atFrame: Int,
    val order: List<Int>
)

data class GridLayoutSpec(
    val dividerPx: Int,
    val backgroundColorHex: String = "#111111",
    val viewports: List<ViewportSpec>,
    val cellCuts: List<GridCellCut>
)

data class MotionSpec(
    val type: String, // "none", "zoom_in", "zoom_out", "pan_h", "pan_v", "diagonale", "punch"
    val startScale: Double = 1.0,
    val endScale: Double = 1.0,
    val startPanX: Double = 0.0,
    val endPanX: Double = 0.0,
    val startPanY: Double = 0.0,
    val endPanY: Double = 0.0,
    val easing: String = "easeInOutQuad"
)

data class CutSegmentSpec(
    val shotId: String,
    val sceneId: String,
    val sourceInFrame: Int,
    val sourceOutFrame: Int,
    val outStartFrame: Int,
    val outEndFrameExcluded: Int,
    val freezeTailFrames: Int = 0,
    val viewport: ViewportSpec = ViewportSpec(0.5, 0.5, 1.0),
    val motion: MotionSpec = MotionSpec("none"),
    val gridLayout: GridLayoutSpec? = null
)

data class SceneSpec(
    val id: String,
    val startFrame: Int,
    val endFrameExcluded: Int,
    val role: String = "",
    val shots: List<CutSegmentSpec>
)

enum class TransitionType(val wireName: String, val capability: CapabilityId) {
    CUT("cut_sec", CapabilityId.TRANSITION_CUT),
    QUATRE_VOLETS("quatre_volets", CapabilityId.TRANSITION_QUATRE_VOLETS),
    CASE_VERS_PLEIN_CADRE("case_vers_plein_cadre", CapabilityId.TRANSITION_CASE_VERS_PLEIN_CADRE),
    BALAYAGE_DIAGONAL("balayage_diagonal", CapabilityId.TRANSITION_BALAYAGE_DIAGONAL),
    WIPE_DIRECTIONNEL("wipe_directionnel", CapabilityId.TRANSITION_WIPE_DIRECTIONNEL),
    CROSSFADE("fondu_court_crossfade", CapabilityId.TRANSITION_CROSSFADE),
    PUNCH("punch_cut", CapabilityId.TRANSITION_PUNCH)
}

data class TransitionSpec(
    val startFrame: Int,
    val durationFrames: Int,
    val type: TransitionType
)

data class TextStyleSpec(
    val fontSizePx: Double,
    val maxWidthPx: Double,
    val lineSpacingPx: Double,
    val twoLineBaseY: Double,
    val singleLineBaseY: Double,
    val emRefPx: Double,
    val alignTopBand: Boolean = false
)

data class TextReliefSpec(
    val shadowColorRgba: String,
    val shadowDxPx: Double,
    val shadowDyPx: Double,
    val shadowBlurPx: Double,
    val strokeColorRgba: String,
    val strokeWidthPx: Double
)

data class WaveKeyframe(
    val ageRefFrames: Double,
    val dxPx: Double,
    val dyPx: Double,
    val rotationDeg: Double,
    val scale: Double
)

data class WaveSpec(
    val keyframes: List<WaveKeyframe>,
    val charIntervalRefFrames: Double,
    val linePauseRefFrames: Double,
    val timeAdaptation: Double
)

enum class TextEntryAnim(val wireName: String, val capability: CapabilityId) {
    COUPE("coupe", CapabilityId.TEXT_ANIM_COUPE),
    FONDU("fondu", CapabilityId.TEXT_ANIM_FONDU),
    VAGUE("vague", CapabilityId.TEXT_ANIM_VAGUE_CHAR),
    SLIDE_POP("slide_pop", CapabilityId.TEXT_ANIM_SLIDE_POP)
}

data class TextWindowSpec(
    val startFrame: Int,
    val endFrameExcluded: Int,
    val fieldKey: String,
    val templateLines: List<String>? = null, // e.g. T5 ["« {avis_client} »", "— {client}"]
    val entryAnim: TextEntryAnim,
    val exitFadeFrames: Int = 0,
    val strikethrough: Boolean = false,
    val customY: Double? = null,
    val maxLines: Int? = null,
    val overrideStyle: TextStyleSpec? = null
)

data class BandExportSpec(
    val topPx: Int,
    val heightPx: Int
)

data class TextOverlaySpec(
    val fontFamily: String,
    val fontWeight: Int,
    val fontRelativePath: String,
    val colorHex: String,
    val defaultStyle: TextStyleSpec,
    val endSectionStyle: TextStyleSpec?,
    val endSectionStartFrame: Int?,
    val exportBand: BandExportSpec,
    val relief: TextReliefSpec,
    val wave: WaveSpec?,
    val fieldBindings: Map<String, List<String>>,
    val maxLinesByField: Map<String, Int>,
    val windows: List<TextWindowSpec>,
    val fadeFrames: Int = 16
)

data class AudioMixConfig(
    val profileId: String,
    val includeCaptureVoice: Boolean,
    val targetLufs: Double = -16.0,
    val bgGainDb: Double = -3.0,
    val underSpeechGainDb: Double = -10.0,
    val attackMs: Int = 120,
    val releaseMs: Int = 450,
    val speechMarginDb: Double = -6.0,
    val peakLimitDb: Double = -2.6
)

data class ApprovedTrack(
    val number: Int,
    val title: String,
    val artist: String,
    val licence: String,
    val credit: String,
    val relativePath: String,
    val sha256: String,
    val preMeasuredLufs: Double? = null
)

data class AudioProfileSpec(
    val id: String,
    val version: String,
    val status: String,
    val rotationMode: String,
    val approvedNumbers: List<Int>,
    val voiceApproved: Boolean,
    val mixApproved: Boolean,
    val tracks: List<ApprovedTrack>,
    val mix: AudioMixConfig
)

data class RenderPlan(
    val templateId: String,
    val version: String,
    val fps: Int,
    val width: Int,
    val height: Int,
    val totalFrames: Int,
    val scenes: List<SceneSpec>,
    val transitions: List<TransitionSpec>,
    val textOverlay: TextOverlaySpec?,
    val colorMode: String,
    val audioProfileId: String,
    val requiredCapabilities: Set<CapabilityId>,
    val unknownCapabilities: List<String>
) {
    val durationSec: Double get() = totalFrames.toDouble() / fps.toDouble()
}
