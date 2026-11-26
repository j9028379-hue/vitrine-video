package com.vitrinevideo.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vitrinevideo.app.flow.T3PackageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented verification test for the real Vitrine Vidéo Android application.
 * Runs only on a real Android device or emulator (no device attached in sandbox).
 */
@RunWith(AndroidJUnit4::class)
class IntegratedAppInstrumentedTest {

    @Test
    fun appContext_loadsBundledT3Package_andMockupAssets() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("com.vitrinevideo.app", context.packageName)

        val pkg = T3PackageRepository.ensureLoaded(context)
        assertEquals("COMMERCE-T3", pkg.templateId)
        assertEquals("1.0.0", pkg.version)
        assertEquals(4, pkg.captureGuides.size)

        val htmlBytes = context.assets.open("mockup/vitrine-video-maquette.html").use { it.readBytes() }
        assertTrue(htmlBytes.isNotEmpty())
    }
}
