package com.vitrinevideo.engine

import com.vitrinevideo.engine.capabilities.CapabilityId
import com.vitrinevideo.engine.graphics.TextDecorationGeometry
import com.vitrinevideo.engine.interpreter.FounderAudioApprovalParser
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.video.GridCellGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream
import java.nio.file.Files

/**
 * JSON -> RenderPlan fidelity checks for the untouched COMMERCE-T4 partition.
 * The partition is never rewritten to make these assertions pass: the engine
 * must expose the values declared by the founder's ZIP.
 */
class T4JsonFidelityTest {

    @Test
    fun t4PartitionStyleValuesReachTheRenderPlan() {
        val pkg = openPackage("COMMERCE-T4-1.0.0.zip")
        try {
            val plan = pkg.renderPlan
            assertEquals("COMMERCE-T4", plan.templateId)
            assertEquals(30, plan.fps)
            assertEquals(720, plan.width)
            assertEquals(1280, plan.height)
            assertEquals(900, plan.totalFrames)
            assertEquals("coupe_franche", plan.cutMode)
            assertTrue(CapabilityId.TRANSITION_CUT in plan.requiredCapabilities)
            assertTrue("Unexpected unknown capabilities: ${plan.unknownCapabilities}", plan.unknownCapabilities.isEmpty())

            val overlay = plan.textOverlay ?: error("T4 text overlay missing")
            assertEquals("majuscules", overlay.caseMode)
            assertEquals(5.0, overlay.strikethroughWidthPx!!, 0.0001)
            assertTrue(overlay.conditionOnlyIfNonEmpty)
            assertEquals("docs/assets/Montserrat.ttf", overlay.fontRelativePath)
            assertEquals("Montserrat Black", overlay.fontFamily)
            assertEquals(900, overlay.fontWeight)
            assertEquals("0f7b311b2f3279e4eef9b2f968bcdbab6e28f4daeb1f049f4f278a902bcd82f7", overlay.fontSha256)
            assertEquals("#FFFFFF", overlay.colorHex)
            assertEquals("#6C87FF", overlay.accentColorHex)
            assertEquals("#090A11", overlay.outlineColorHex)
            assertEquals(5.0, overlay.outlineWidthPx!!, 0.0001)
            assertEquals(3.0, overlay.shadowOffsetYPx!!, 0.0001)
            assertEquals(0.0, overlay.relief.shadowBlurPx, 0.0001)
            assertEquals(3.0, overlay.relief.shadowDyPx, 0.0001)
            assertEquals(56.0, overlay.defaultStyle.fontSizePx, 0.0001)
            assertEquals(636.0, overlay.defaultStyle.maxWidthPx, 0.0001)
            assertEquals(895.0, overlay.defaultTextY!!, 0.0001)

            assertEquals(listOf(0, 66, 180, 275, 420, 630, 756), overlay.windows.map { it.startFrame })
            assertEquals(listOf(66, 180, 275, 420, 630, 756, 900), overlay.windows.map { it.endFrameExcluded })
            assertEquals(
                listOf("annonce", "produit", "detail", "produit", "prix", "adresse", "contact"),
                overlay.windows.map { it.textKind }
            )
            assertEquals("EN", overlay.windows[0].whiteTextTemplate)
            assertEquals("PROMOTION !", overlay.windows[0].accentTextTemplate)
            assertNull(overlay.windows[1].whiteTextTemplate)
            assertEquals("{produit}", overlay.windows[1].accentTextTemplate)

            val price = overlay.windows[4]
            assertEquals("{habituel}", price.whiteTextTemplate)
            assertEquals("{promo}", price.accentTextTemplate)
            assertEquals(55.0, price.beforePriceFontPx!!, 0.0001)
            assertEquals(89.0, price.afterPriceFontPx!!, 0.0001)
            assertEquals("{fin}", price.conditionTextTemplate)
            assertEquals(37.0, price.conditionFontPx!!, 0.0001)

            assertEquals(26, plan.frameStyleCuts.size)
            val detail = plan.frameStyleCuts.first { it.atFrame == 0 }
            assertEquals("detail", detail.style)
            assertEquals(1.53, detail.zoom!!, 0.0001)
            val full = plan.frameStyleCuts.first { it.atFrame == 21 }
            assertEquals("entier", full.style)
            assertEquals(1.0, full.zoom!!, 0.0001)
            val double = plan.frameStyleCuts.first { it.atFrame == 148 }
            assertEquals("double", double.style)
            assertEquals(2, double.viewports.size)
            assertEquals(1.72, double.viewports[0].zoom, 0.0001)
            assertEquals(0.5, double.viewports[0].cx, 0.0001)
            assertEquals(0.39, double.viewports[0].cy, 0.0001)
            assertEquals(1.05, double.viewports[1].zoom, 0.0001)
            assertEquals(0.5, double.viewports[1].cx, 0.0001)
            assertEquals(0.52, double.viewports[1].cy, 0.0001)

            val doubleShot = plan.scenes.flatMap { it.shots }.first { it.gridLayout?.rows == 2 && it.gridLayout.columns == 1 }
            assertEquals(2, doubleShot.gridLayout!!.viewports.size)
        } finally {
            pkg.packageDir.deleteRecursively()
        }
    }

    @Test
    fun t3TwoByTwoGridRemainsUnchanged() {
        val pkg = openPackage("COMMERCE-T3-1.0.0.zip")
        try {
            val plan = pkg.renderPlan
            assertTrue(plan.scenes.flatMap { it.shots }.any { it.gridLayout?.rows == 2 && it.gridLayout.columns == 2 })
            assertEquals("", plan.cutMode)
            val overlay = plan.textOverlay ?: error("T3 text overlay missing")
            assertEquals("", overlay.caseMode)
            assertNull(overlay.strikethroughWidthPx)
            assertEquals(Pair(1.0f, 1.0f), GridCellGeometry.samplingSpan(2, 2))
        } finally {
            pkg.packageDir.deleteRecursively()
        }
    }

    @Test
    fun t4AudioProfileKeepsItsDeclaredApprovalState() {
        val pkg = openPackage("COMMERCE-T4-1.0.0.zip")
        try {
            val audio = pkg.audioProfile ?: error("T4 audio profile missing")
            assertTrue(audio.voiceApproved)
            assertEquals(false, audio.mixApproved)
            assertEquals(listOf(1, 3, 4), audio.tracks.map { it.number })
        } finally {
            pkg.packageDir.deleteRecursively()
        }
    }

    @Test
    fun founderApprovalIsVersionedAndDoesNotRewriteTechnicalMixState() {
        val policyRaw = TestAssetLocator
            .projectFile("vitrine-render-engine/src/main/assets/config/founder-audio-approvals.json")
            .readText()
        val approval = FounderAudioApprovalParser.parseAll(policyRaw).first { it.profileId == "promo_afrobeats" }
        assertEquals("FONDATEUR-T4-MUSIQUES-2026-11-25", approval.approvalId)
        assertEquals(setOf(1, 3, 4), approval.approvedTrackNumbers)
        assertFalse(2 in approval.approvedTrackNumbers)
        assertEquals("mix_valide: false", approval.technicalMixStateInSource)

        val pkg = openPackage("COMMERCE-T4-1.0.0.zip")
        try {
            val profileRaw = pkg.resolvePackageRelative("audio/profil.json").readText()
            val technical = UniversalJsonInterpreter.parseAudioProfileJson(profileRaw)
            assertFalse(technical.mixApproved)
            assertFalse(technical.hasFounderApprovedSelection)

            val merged = UniversalJsonInterpreter.parseAudioProfileJson(profileRaw, policyRaw)
            assertFalse("Technical JSON state must not be falsified", merged.mixApproved)
            assertTrue(merged.hasFounderApprovedSelection)
            assertEquals(approval.approvalId, merged.founderApprovalId)
        } finally {
            pkg.packageDir.deleteRecursively()
        }
    }

    @Test
    fun gridSamplingSpanUsesCellAspectInsteadOfInvertingIt() {
        assertEquals(Pair(1.0f, 0.5f), GridCellGeometry.samplingSpan(2, 1))
        assertEquals(Pair(1.0f, 1.0f), GridCellGeometry.samplingSpan(2, 2))
        assertEquals(Pair(0.5f, 1.0f), GridCellGeometry.samplingSpan(1, 2))
    }

    @Test
    fun declaredStrikeWidthWinsOverLegacyFallback() {
        assertEquals(5.0f, TextDecorationGeometry.strikethroughWidthPx(55f, 5.0), 0.0001f)
        assertEquals(4.4f, TextDecorationGeometry.strikethroughWidthPx(55f, null), 0.0001f)
    }

    private fun openPackage(name: String) =
        FileInputStream(TestAssetLocator.packageZip(name)).use { TemplatePackageLoader.importZipStream(it, Files.createTempDirectory("t4-fidelity-$name").toFile()) }
}
