package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.BalancedAvailableTrackRotationAlgorithm
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

class T4PackageIntegrationTest {
    @Test
    fun founderPackageLoadsAndPreservesCommercialContract() {
        val zip = findPackageAsset()
        val temp = Files.createTempDirectory("t4-package-test").toFile()
        try {
            val pkg = FileInputStream(zip).use { TemplatePackageLoader.importZipStream(it, temp) }
            assertEquals("COMMERCE-T4", pkg.templateId)
            assertEquals("1.0.0", pkg.version)
            assertEquals(30, pkg.renderPlan.fps)
            assertEquals(720, pkg.renderPlan.width)
            assertEquals(1280, pkg.renderPlan.height)
            assertEquals(900, pkg.renderPlan.totalFrames)
            assertEquals(listOf("S1", "S2", "S3", "S4"), pkg.renderPlan.scenes.map { it.id })
            assertEquals(listOf(0, 180, 420, 630), pkg.renderPlan.scenes.map { it.startFrame })
            assertEquals(listOf(180, 420, 630, 900), pkg.renderPlan.scenes.map { it.endFrameExcluded })
            assertEquals(4, pkg.captureGuides.size)
            assertEquals(listOf(6, 8, 7, 9), pkg.captureGuides.map { it.durationSec })
            assertEquals(listOf(1, 4, 7, 10), pkg.captureGuides.map { it.guideScreenNumber })
            assertEquals(listOf(2, 5, 8, 11), pkg.captureGuides.map { it.recordScreenNumber })
            assertEquals(listOf(3, 6, 9, 12), pkg.captureGuides.map { it.reviewScreenNumber })
            assertEquals("C’EST EN PROMOTION !", pkg.captureGuides.first().title)
            assertEquals(2, pkg.captureGuides.first().recordScreenNumber)
            assertEquals("Ce produit est en promotion !", pkg.captureGuides.first().exampleSpeech)
            assertEquals(3, pkg.captureGuides.first().thingsToFilm.size)
            assertTrue(pkg.captureGuides.first().filmingInstruction.isNotBlank())
            assertEquals(setOf("commerce", "produit", "habituel", "promo", "fin", "localisation", "whatsapp"), pkg.formSpec.keys())
            assertEquals(setOf("commerce", "produit", "habituel", "promo", "localisation", "whatsapp"), pkg.formSpec.requiredKeys())
            val audio = pkg.audioProfile ?: error("T4 audio profile missing")
            assertEquals(listOf(1, 2, 3, 4), audio.approvedNumbers)
            assertEquals(listOf(1, 3, 4), audio.tracks.map { it.number })
            assertEquals("promo_afrobeats", audio.id)
            assertEquals(-16.0, audio.mix.targetLufs, 0.001)
            assertEquals(-3.0, audio.mix.bgGainDb, 0.001)
            assertEquals(-10.0, audio.mix.underSpeechGainDb, 0.001)
            assertEquals(8.0, audio.mix.minimumVoiceMusicGapDb!!, 0.001)
            assertEquals(listOf(0, 21, 49, 74, 96, 122, 148, 167, 180, 190, 212, 238, 274, 305, 335, 364, 390, 420, 449, 476, 532, 558, 630, 662, 718, 746), pkg.renderPlan.frameStyleCuts.map { it.atFrame })
            val overlay = pkg.renderPlan.textOverlay!!
            assertEquals(listOf(0, 66, 180, 275, 420, 630, 756), overlay.windows.map { it.startFrame })
            assertEquals(listOf(66, 180, 275, 420, 630, 756, 900), overlay.windows.map { it.endFrameExcluded })
            assertEquals("Montserrat Black", overlay.fontFamily)
            assertEquals(900, overlay.fontWeight)
            assertEquals("#FFFFFF", overlay.colorHex)
            assertEquals("#6C87FF", overlay.accentColorHex)
            assertEquals(7, overlay.windows.size)
            assertEquals("prix", overlay.windows[4].textKind)
            assertEquals("{habituel}", overlay.windows[4].whiteTextTemplate)
            assertEquals("{promo}", overlay.windows[4].accentTextTemplate)
            assertTrue(pkg.renderPlan.scenes.flatMap { it.shots }.any { it.gridLayout?.rows == 2 && it.gridLayout?.columns == 1 })
            assertTrue(pkg.packageDir.resolve("render/demo/voix/Voix-seule-T4.wav").exists())
            assertFalse(audio.tracks.any { it.relativePath.contains("voix", ignoreCase = true) })
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun balancedRotationUsesOnlyPlayableApprovedTracksAndAvoidsImmediateRepeat() {
        val available = listOf(1, 3, 4)
        var history = emptyList<Int>()
        repeat(60) { seed ->
            val chosen = BalancedAvailableTrackRotationAlgorithm.choose(available, history) { seed % it }
            assertTrue(chosen in available)
            if (history.isNotEmpty()) assertTrue(chosen != history.last())
            history = history + chosen
        }
        val counts = history.groupingBy { it }.eachCount().values
        assertTrue(counts.maxOrNull()!! - counts.minOrNull()!! <= 1)
    }

    private fun findPackageAsset(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            val candidate = File(dir, "vitrine-video-app/src/main/assets/packages/COMMERCE-T4-1.0.0.zip")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: dir
        }
        error("COMMERCE-T4 asset ZIP not found from ${System.getProperty("user.dir")}")
    }
}
