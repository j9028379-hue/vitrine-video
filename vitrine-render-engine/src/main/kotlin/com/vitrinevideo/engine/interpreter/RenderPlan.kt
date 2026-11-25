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
    val cellCuts: List<GridCellCut>,
    val rows: Int = 2,
    val columns: Int = 2
)

data class FrameStyleCut(
    val atFrame: Int,
    val style: String,
    val zoom: Double? = null,
    val viewports: List<ViewportSpec> = emptyList()
)

data class MotionSpec(
    val type: String, // "none", "zoom_in", "zoom_out", "pan_h", "pan_v", "diagonale", "punch"
    val startScale: Double = 1.0,
    val endScale: Double = 1.0,
    val startPanX: Double = 0.0,
    val endPanX: Double = 0.0,
    val startPanY: Double = 0.0,
    val endPanY: Double = 0.0,
    val startAngleDeg: Double = 0.0,
    val endAngleDeg: Double = 0.0,
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
    val raccordMode: String = "",
    val shots: List<CutSegmentSpec>
)

enum class TransitionType(val wireName: String, val capability: CapabilityId) {
    CUT("cut_sec", CapabilityId.TRANSITION_CUT),
    QUATRE_VOLETS("quatre_volets", CapabilityId.TRANSITION_QUATRE_VOLETS),
    CASE_VERS_PLEIN_CADRE("case_vers_plein_cadre", CapabilityId.TRANSITION_CASE_VERS_PLEIN_CADRE),
    BALAYAGE_DIAGONAL("balayage_diagonal", CapabilityId.TRANSITION_BALAYAGE_DIAGONAL),
    WIPE_DIRECTIONNEL("wipe_directionnel", CapabilityId.TRANSITION_WIPE_DIRECTIONNEL),
    CROSSFADE("fondu_court_crossfade", CapabilityId.TRANSITION_CROSSFADE),
    PUNCH("punch_cut", CapabilityId.TRANSITION_PUNCH),
    LAMELLES_OUVERTURE("lamelles_ouverture", CapabilityId.TRANSITION_LAMELLES_OUVERTURE),
    LAMELLES_FERMETURE("lamelles_fermeture", CapabilityId.TRANSITION_LAMELLES_FERMETURE),
    ECLAT_COLORE("eclat_colore", CapabilityId.TRANSITION_ECLAT_COLORE),
    PAPIER_OBLIQUE("papier_oblique", CapabilityId.TRANSITION_PAPIER_OBLIQUE),
    POUSSEE_VOILE("poussee_voile", CapabilityId.TRANSITION_POUSSEE_VOILE),
    FENTE_HORIZONTALE("fente_horizontale", CapabilityId.TRANSITION_FENTE_HORIZONTALE),
    TRANCHE_PIP("tranche_pip", CapabilityId.TRANSITION_TRANCHE_PIP),
    BANDE_OBLIQUE_RECUL("bande_oblique_recul", CapabilityId.TRANSITION_BANDE_OBLIQUE_RECUL)
}

data class TransitionSpec(
    val startFrame: Int,
    val durationFrames: Int,
    val type: TransitionType,
    val motifId: String = ""
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
    SLIDE_POP("slide_pop", CapabilityId.TEXT_ANIM_SLIDE_POP),
    PLUME("plume", CapabilityId.TEXT_ANIM_PLUME),
    LEVER("lever", CapabilityId.TEXT_ANIM_LEVER),
    VAGUE_REVEAL("vague_reveal", CapabilityId.TEXT_ANIM_VAGUE_REVEAL)
}

data class TextWindowSpec(
    val startFrame: Int,
    val endFrameExcluded: Int,
    val fieldKey: String,
    val templateLines: List<String>? = null, // e.g. T5 ["« {avis_client} »", "— {client}"]
    val entryAnim: TextEntryAnim,
    val exitFadeFrames: Int = 0,
    val enterFrames: Int? = null,
    val strikethrough: Boolean = false,
    val customY: Double? = null,
    val centerX: Double? = null,
    val maxLines: Int? = null,
    val maxHeightPx: Double? = null,
    val fontRelativePath: String? = null,
    val fallbackFontRelativePath: String? = null,
    val fontSha256: String? = null,
    val inkColorHex: String? = null,
    val reliefColorHex: String? = null,
    val skipAutoWhatsappPrefix: Boolean = false,
    val overrideStyle: TextStyleSpec? = null,
    /** T4 source-specific paired copy; kept as templates rather than flattened into demo text. */
    val textKind: String? = null,
    val whiteTextTemplate: String? = null,
    val accentTextTemplate: String? = null,
    val whiteColorHex: String? = null,
    val accentColorHex: String? = null,
    val beforePriceFontPx: Double? = null,
    val afterPriceFontPx: Double? = null,
    val conditionTextTemplate: String? = null,
    val conditionFontPx: Double? = null
)

data class ColorGradingSpec(
    val mode: String = "source_native",
    val addedTint: Boolean = false,
    val decorativeBorder: Boolean = false,
    val tintHex: String? = null,
    val tintOpacity: Double = 0.0,
    val borderColorHex: String? = null,
    val borderWidthPx: Int = 0
) {
    val isNativeColorPreserved: Boolean
        get() = mode == "source_native" && !addedTint

    val shouldRenderAddedTint: Boolean
        get() = addedTint && tintOpacity > 0.0 && !tintHex.isNullOrBlank()

    val shouldRenderDecorativeBorder: Boolean
        get() = decorativeBorder && borderWidthPx > 0
}

data class BandExportSpec(
    val topPx: Int,
    val heightPx: Int
)

data class TextOverlaySpec(
    val fontFamily: String,
    val fontWeight: Int,
    val fontRelativePath: String,
    val fontSha256: String = "",
    val extraFontPaths: Map<String, String> = emptyMap(),
    val extraFontSha256s: Map<String, String> = emptyMap(),
    val colorHex: String,
    val alignment: String = "centre",
    val absentPriceRule: String = "",
    val defaultStyle: TextStyleSpec,
    val endSectionStyle: TextStyleSpec?,
    val endSectionStartFrame: Int?,
    val exportBand: BandExportSpec,
    val relief: TextReliefSpec,
    val wave: WaveSpec?,
    val fieldBindings: Map<String, List<String>>,
    val maxLinesByField: Map<String, Int>,
    val windows: List<TextWindowSpec>,
    val fadeFrames: Int = 16,
    val outlineColorHex: String? = null,
    val outlineWidthPx: Double? = null,
    val shadowOffsetYPx: Double? = null,
    val defaultTextY: Double? = null,
    val accentColorHex: String? = null,
    /** Verbatim `texte.casse` value from the partition; drives capitalization at draw time. */
    val caseMode: String = "",
    /** Verbatim `texte.prix_barre_px` value; null means the engine legacy fallback applies. */
    val strikethroughWidthPx: Double? = null,
    /** Verbatim `texte.condition_si_non_vide` value. */
    val conditionOnlyIfNonEmpty: Boolean = false
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
    val peakLimitDb: Double = -2.6,
    val minimumVoiceMusicGapDb: Double? = null
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
    val mix: AudioMixConfig,
    /** Explicit founder approval record, never inferred from a technical mix flag. */
    val founderApprovalId: String = "",
    val founderApprovedTrackNumbers: Set<Int> = emptySet(),
    val founderApprovedFiles: Set<String> = emptySet()
) {
    /** True only when an explicit versioned record covers every playable track. */
    val hasFounderApprovedSelection: Boolean
        get() = founderApprovalId.isNotBlank() &&
            founderApprovedTrackNumbers.isNotEmpty() &&
            tracks.isNotEmpty() &&
            tracks.all { it.number in founderApprovedTrackNumbers && it.relativePath in founderApprovedFiles }
}

data class RenderPlan(
    val templateId: String,
    val version: String,
    val statusLabel: String = "",
    val fps: Int,
    val width: Int,
    val height: Int,
    val totalFrames: Int,
    val scenes: List<SceneSpec>,
    val transitions: List<TransitionSpec>,
    val textOverlay: TextOverlaySpec?,
    val colorMode: String,
    val colorGrading: ColorGradingSpec = ColorGradingSpec(mode = colorMode),
    val audioProfileId: String,
    val requiredCapabilities: Set<CapabilityId>,
    val unknownCapabilities: List<String>,
    val frameStyleCuts: List<FrameStyleCut> = emptyList(),
    /** Verbatim `effets.type` value (e.g. `coupe_franche`); cuts stay hard unless a `liaison` says otherwise. */
    val cutMode: String = ""
) {
    val durationSec: Double get() = totalFrames.toDouble() / fps.toDouble()
}
