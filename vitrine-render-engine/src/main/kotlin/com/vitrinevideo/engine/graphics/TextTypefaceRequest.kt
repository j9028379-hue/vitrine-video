package com.vitrinevideo.engine.graphics

import com.vitrinevideo.engine.interpreter.TextOverlaySpec

/**
 * Pure description of the typeface a renderer must use.
 *
 * The T4 partition declares Montserrat Black / weight 900 but ships the same
 * variable Montserrat TTF as T3, whose default instance is Thin (usWeightClass=100).
 * Loading it with `Typeface.createFromFile` therefore silently renders Thin.
 * This request makes the declared weight explicit so the Android layer can
 * instantiate the variable font at 900 (or synthesize bold on old APIs).
 */
data class TextTypefaceRequest(
    val fontPath: String,
    val family: String,
    val declaredWeight: Int,
    val applyDeclaredWeight: Boolean,
    val variationSettings: String?,
    val syntheticBoldFallback: Boolean
) {
    companion object {
        fun from(
            spec: TextOverlaySpec,
            fontFileAvailable: Boolean,
            declaredWeight: Int = spec.fontWeight
        ): TextTypefaceRequest {
            val weight = if (declaredWeight in 100..900) declaredWeight else spec.fontWeight
            val mustApplyWeight = fontFileAvailable && weight >= 600
            return TextTypefaceRequest(
                fontPath = spec.fontRelativePath,
                family = spec.fontFamily,
                declaredWeight = weight,
                applyDeclaredWeight = mustApplyWeight,
                variationSettings = if (mustApplyWeight) "'wght' $weight" else null,
                syntheticBoldFallback = !fontFileAvailable && weight >= 600
            )
        }
    }
}
