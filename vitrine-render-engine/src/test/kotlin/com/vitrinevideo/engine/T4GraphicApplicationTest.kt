package com.vitrinevideo.engine

import com.vitrinevideo.engine.graphics.TextTypefaceRequest
import com.vitrinevideo.engine.graphics.TrueTypeFontInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Proves that the T4 declaration is not merely parsed but actually carried
 * into the renderer: the packaged font was Thin 100 while the partition asks
 * for Black 900. The engine now produces an explicit weight request.
 */
class T4GraphicApplicationTest {

    @Test
    fun declaredBlackWeightBecomesAnExplicitVariableFontRequest() {
        val overlay = openT4().renderPlan.textOverlay ?: error("T4 overlay missing")
        val fontBytes = openT4Bytes("docs/assets/Montserrat.ttf")
        val font = TrueTypeFontInspector.inspect(fontBytes)

        assertEquals("Montserrat Thin", font.familyName)
        assertEquals("Thin", font.weightName)
        assertEquals(100, font.weightClass)
        assertTrue("Variable font must expose a wght axis", font.hasWeightAxis)
        assertTrue("Font must be capable of 900", font.canRenderDeclaredBlack)
        assertTrue("Axis max was ${font.weightAxisMax}", (font.weightAxisMax ?: 0f) >= 900f)

        val request = TextTypefaceRequest.from(overlay, fontFileAvailable = true)
        assertEquals(900, request.declaredWeight)
        assertTrue(request.applyDeclaredWeight)
        assertEquals("'wght' 900", request.variationSettings)
        assertFalse(request.syntheticBoldFallback)
        assertEquals("docs/assets/Montserrat.ttf", request.fontPath)

        val missingFontRequest = TextTypefaceRequest.from(overlay, fontFileAvailable = false)
        assertTrue(missingFontRequest.syntheticBoldFallback)
        assertFalse(missingFontRequest.applyDeclaredWeight)
    }

    @Test
    fun t3RegularFontRemainsUnchanged() {
        val pkg = FileInputStream(TestAssetLocator.packageZip("COMMERCE-T3-1.0.0.zip")).use {
            com.vitrinevideo.engine.pkg.TemplatePackageLoader.importZipStream(
                it, Files.createTempDirectory("t3-font-regression").toFile()
            )
        }
        try {
            val overlay = pkg.renderPlan.textOverlay ?: error("T3 overlay missing")
            val request = TextTypefaceRequest.from(overlay, fontFileAvailable = true)
            assertEquals(400, request.declaredWeight)
            assertFalse("T3 must keep its file verbatim", request.applyDeclaredWeight)
            assertFalse(request.syntheticBoldFallback)
            assertEquals(null, request.variationSettings)
        } finally {
            pkg.packageDir.deleteRecursively()
        }
    }

    @Test
    fun t4GraphicParametersRemainThoseOfFounderReferenceFrames() {
        val overlay = openT4().renderPlan.textOverlay ?: error("T4 overlay missing")
        assertEquals("#FFFFFF", overlay.colorHex)
        assertEquals("#6C87FF", overlay.accentColorHex)
        assertEquals("#090A11", overlay.outlineColorHex)
        assertEquals(5.0, overlay.outlineWidthPx!!, 0.0001)
        assertEquals(3.0, overlay.shadowOffsetYPx!!, 0.0001)
        assertEquals(636.0, overlay.defaultStyle.maxWidthPx, 0.0001)
        assertEquals(895.0, overlay.defaultTextY!!, 0.0001)
        assertEquals(56.0, overlay.defaultStyle.fontSizePx, 0.0001)
        assertEquals(55.0, overlay.windows.first { it.textKind == "prix" }.beforePriceFontPx!!, 0.0001)
        assertEquals(89.0, overlay.windows.first { it.textKind == "prix" }.afterPriceFontPx!!, 0.0001)
        assertEquals(37.0, overlay.windows.first { it.textKind == "prix" }.conditionFontPx!!, 0.0001)
        assertEquals(5.0, overlay.strikethroughWidthPx!!, 0.0001)

        val annonce = TestAssetLocator.projectFile("test-fixtures/t4-visual-reference/reference_annonce.png")
        val prix = TestAssetLocator.projectFile("test-fixtures/t4-visual-reference/reference_prix.png")
        assertTrue(annonce.isFile)
        assertTrue(prix.isFile)
        assertEquals("64177b97c982a579154eaa18fb048db8500db16efbac7207df68a27f2e558c76", sha256(annonce))
        assertEquals("2e6c2db2483b8676e8b551c1439d2e022bcd8ef14e2ac891cc12af5b08def4b5", sha256(prix))
    }

    private fun openT4() = FileInputStream(TestAssetLocator.packageZip("COMMERCE-T4-1.0.0.zip")).use {
        com.vitrinevideo.engine.pkg.TemplatePackageLoader.importZipStream(
            it, Files.createTempDirectory("t4-graphic-application").toFile()
        )
    }

    private fun openT4Bytes(path: String): ByteArray {
        val pkg = openT4()
        try {
            return pkg.resolvePackageRelative(path).readBytes()
        } finally {
            pkg.packageDir.deleteRecursively()
        }
    }

    private fun sha256(file: java.io.File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
