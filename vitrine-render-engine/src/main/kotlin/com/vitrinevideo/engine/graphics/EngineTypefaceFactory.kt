package com.vitrinevideo.engine.graphics

import android.graphics.Typeface
import android.os.Build
import java.io.File

/**
 * Android-side typeface loader. Keeps T1/T3 behavior untouched (weights below
 * 600 use the packaged file verbatim) while honoring declared Black/900 for T4.
 */
object EngineTypefaceFactory {
    fun create(request: TextTypefaceRequest, fontFile: File?): Typeface {
        val available = fontFile != null && fontFile.exists() && fontFile.length() > 0L
        if (!available) {
            return Typeface.create(
                request.family,
                if (request.syntheticBoldFallback) Typeface.BOLD else Typeface.NORMAL
            )
        }
        val file = fontFile!!
        if (!request.applyDeclaredWeight) return Typeface.createFromFile(file)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Typeface.Builder(file)
                .setWeight(request.declaredWeight.coerceIn(100, 900))
            if (request.variationSettings != null) {
                builder.setFontVariationSettings(request.variationSettings)
            }
            builder.build()
        } else {
            // API 23..25 cannot instantiate a variable-font instance. Synthetic
            // bold is the closest available approximation; never load Thin as-is.
            Typeface.create(Typeface.createFromFile(file), Typeface.BOLD)
        }
    }
}
