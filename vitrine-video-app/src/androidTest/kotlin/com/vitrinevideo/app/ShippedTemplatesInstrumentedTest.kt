package com.vitrinevideo.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vitrinevideo.app.flow.TemplateCatalogue
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-side proof that the single Release APK really carries every shipped
 * template and the approved merchant copy. It runs on a handset/emulator only;
 * the sandbox has no Android runtime, so CI executes the JVM suites and the
 * APK inspection instead.
 */
@RunWith(AndroidJUnit4::class)
class ShippedTemplatesInstrumentedTest {

    private val context: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private fun load(asset: String) = TemplatePackageLoader.importZip(
        File(context.cacheDir, asset).also { target ->
            context.assets.open("packages/$asset").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        },
        File(context.cacheDir, "pkg_${asset.removeSuffix(".zip")}"),
        // Each template gets its own music root: the sealed vault must never be
        // shared between two packaged libraries.
        File(context.cacheDir, "music_${asset.removeSuffix(".zip")}")
    )

    @Test
    fun everyShippedTemplateLoadsInTheUniversalEngine() {
        val expectations = mapOf(
            "COMMERCE-T1-2.0.0.zip" to "COMMERCE-T1",
            "COMMERCE-T2-1.0.0.zip" to "COMMERCE-T2",
            "COMMERCE-T3-1.0.0.zip" to "COMMERCE-T3",
            "COMMERCE-T4-1.0.0.zip" to "COMMERCE-T4",
            "RESTAURATION-T2-1.0.0.zip" to "RESTAURATION-T2",
            "RESTAURATION-T5-1.0.0.zip" to "RESTAURATION-T5"
        )
        for ((asset, templateId) in expectations) {
            val pkg = load(asset)
            assertEquals(templateId, pkg.templateId)
            assertEquals(4, pkg.captureGuides.size)
            assertTrue("$templateId must declare a render plan", pkg.renderPlan.totalFrames > 0)
            assertTrue(TemplateCatalogue.isSupported(templateId))
        }
    }

    @Test
    fun t5KeepsItsGuideImageAndAudioPlayerAndHidesTechnicalWording() {
        val html = context.assets.open("mockup/vitrine-video-maquette.html")
            .bufferedReader().use { it.readText() }
        for (required in listOf(
            "COMMENCER À FILMER", "À FILMER", "À ÉVITER", "CE QUE TU DOIS FAIRE",
            "guide-audio-toggle", "guide-audio-back", "guide-audio-forward",
            "guide-audio-seek", "guide-scene-image"
        )) {
            assertTrue("approved T5 guide function missing: $required", html.contains(required))
        }
        for (forbidden in listOf("TEST T5", "DEBUG", "MOCK", "screen_id", "non connectée")) {
            assertFalse("merchant UI must not show '$forbidden'", html.contains(forbidden))
        }
    }
}
