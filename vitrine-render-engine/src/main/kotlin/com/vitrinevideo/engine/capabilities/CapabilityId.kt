package com.vitrinevideo.engine.capabilities

enum class CapabilityId(val wireName: String, val description: String) {
    VIDEO_SCENE_TRIM("video.scene_trim", "Trimming and sequential composition of capture clips by frame range"),
    VIDEO_CROP_ZOOM("video.crop_zoom", "Normalized center-crop (cx, cy, zoom) and aspect-ratio framing"),
    VIDEO_MOTION_CURVE("video.motion_curve", "Continuous per-shot zoom/pan keyframes (zoom_in, zoom_out, pan_h, pan_v, diagonale, punch)"),
    VIDEO_FREEZE_TAIL("video.freeze_tail", "Extending short source shots by holding tail frame"),
    VIDEO_GRID_2X2_SINGLE_SOURCE("video.grid_2x2_single_source", "2x2 multi-viewport grid from single clip with divider and cell permutations"),
    COLOR_SOURCE_NATIVE("color.source_native", "Pass-through native camera color space without forced tint"),
    TRANSITION_CUT("transition.cut", "Hard cut between scenes or shots"),
    TRANSITION_QUATRE_VOLETS("transition.quatre_volets", "4-quadrant staggered wipe reveal"),
    TRANSITION_CASE_VERS_PLEIN_CADRE("transition.case_vers_plein_cadre", "Bottom-left cell expanding to full frame"),
    TRANSITION_BALAYAGE_DIAGONAL("transition.balayage_diagonal", "Diagonal left-to-right sweep reveal"),
    TRANSITION_WIPE_DIRECTIONNEL("transition.wipe_directionnel", "Horizontal/vertical directional wipe"),
    TRANSITION_CROSSFADE("transition.crossfade", "Alpha crossfade transition"),
    TRANSITION_PUNCH("transition.punch", "Speed-ramp zoom punch transition"),
    TEXT_STYLED_WINDOWS("text.styled_windows", "Timed text overlays with TTF font, wrapping, auto-fit, stroke, shadow and absent-field omission"),
    TEXT_ANIM_COUPE("text.anim.coupe", "Instant text entry"),
    TEXT_ANIM_FONDU("text.anim.fondu", "Alpha fade-in text entry"),
    TEXT_ANIM_VAGUE_CHAR("text.anim.vague_char", "Per-character keyframed wave (dx, dy, rotation, scale)"),
    TEXT_ANIM_SLIDE_POP("text.anim.slide_pop", "Block fade/slide/pop animation"),
    TEXT_STRIKETHROUGH("text.strikethrough", "Strikethrough line rendering (e.g. crossed-out regular price)"),
    AUDIO_CAPTURES_DUCKING("audio.captures_ducking", "Capture audio alignment with RMS VAD music ducking and peak limiting"),
    AUDIO_MUSIC_ONLY("audio.music_only", "Music bed only without capture audio"),
    AUDIO_CYCLIC_ROTATION("audio.cyclic_rotation", "Transactional SQLite cyclic track rotation (committed on success)"),
    EXPORT_MP4_H264_AAC("export.mp4_h264_aac", "H.264/AVC + AAC MP4 export with faststart")
}

class UnsupportedCapabilityException(
    val unsupportedCapabilities: List<String>,
    message: String
) : IllegalArgumentException(message)

object EngineCapabilities {
    val supported: Set<CapabilityId> = CapabilityId.values().toSet()

    fun assertSupported(required: Set<CapabilityId>, unknownWireNames: List<String>) {
        val missing = required.filter { it !in supported }.map { it.wireName } + unknownWireNames
        if (missing.isNotEmpty()) {
            throw UnsupportedCapabilityException(
                unsupportedCapabilities = missing,
                message = "Capacités non prises en charge par le moteur: ${missing.joinToString(", ")}"
            )
        }
    }

    fun catalogSummary(): List<Pair<String, String>> =
        supported.map { it.wireName to it.description }
}
