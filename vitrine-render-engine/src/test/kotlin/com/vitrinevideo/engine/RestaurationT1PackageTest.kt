package com.vitrinevideo.engine

import com.vitrinevideo.engine.graphics.TextFieldResolver
import com.vitrinevideo.engine.interpreter.ContratCandidatParser
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Technical-only conformance checks for the source-pinned Restaurants/Maquis T1 package. */
class RestaurationT1PackageTest {
    private fun assets() = TestAssetLocator.projectFile("vitrine-video-app/src/main/assets")
    private fun zip() = ZipFile(TestAssetLocator.packageZip("RESTAURATION-T1-1.0.0.zip"))
    private fun load() = TemplatePackageLoader.importZip(
        TestAssetLocator.packageZip("RESTAURATION-T1-1.0.0.zip"),
        kotlin.io.path.createTempDirectory("restauration_t1_pkg").toFile(),
        File(assets(), "music")
    )

    @Test
    fun runtimePackageHasOneIdentityExactReferenceTimelineAndFourCaptures() = zip().use { z ->
        val manifest = JSONObject(z.getInputStream(z.getEntry("MANIFEST.json")).bufferedReader().use { it.readText() })
        assertEquals("RESTAURATION-T1", manifest.getString("template_id"))
        assertEquals("1.0.0", manifest.getString("version"))
        assertEquals("9d761f4f1f1f3ce1b0a3e9b66c76a5adab92a68b", manifest.getString("source_commit"))
        val pkg = load()
        assertEquals("RESTAURATION-T1", pkg.templateId)
        assertEquals(900, pkg.renderPlan.totalFrames)
        assertEquals(listOf(6, 8, 7, 9), pkg.renderPlan.scenes.map { (it.endFrameExcluded-it.startFrame)/30 })
        assertEquals(listOf(0,180,420,630,900), pkg.renderPlan.scenes.flatMap { listOf(it.startFrame,it.endFrameExcluded) }.distinct())
        assertEquals(14, pkg.renderPlan.scenes.sumOf { it.shots.size })
        assertEquals("P01", pkg.renderPlan.scenes.first().shots.first().shotId)
        assertEquals(900, pkg.renderPlan.scenes.last().shots.last().outEndFrameExcluded)
        assertEquals(12, JSONObject(pkg.resolvePackageRelative("templates/restauration/restauration-t1/capture-guide/ecrans.json").readText())
            .getJSONArray("ecrans").length())
        assertEquals(listOf("S1","S2","S3","S4"),pkg.captureGuides.map { it.sceneId })
        assertEquals(listOf(6,8,7,9),pkg.captureGuides.map { it.durationSec })
        assertEquals(5, pkg.formSpec.requiredKeys().size)
        assertEquals(6, pkg.formSpec.questions.size)
        assertNotNull(pkg.audioProfile)
        assertTrue(pkg.audioProfile!!.voiceApproved)
        assertTrue(pkg.audioProfile!!.mixApproved)
        assertEquals(listOf(1,2,3,4),pkg.audioProfile!!.approvedNumbers)
        val price= pkg.renderPlan.textOverlay!!.windows.first { it.fieldKey=="prix" && it.appendCurrencyWhenAbsent!=null }
        assertTrue(price.priceCurrencyFontRelativePath!!.endsWith("PlusJakartaSans-Variable.ttf"))
        assertEquals(28.0,price.priceCurrencySizePx!!,0.001)
        assertEquals(18.0,price.priceCurrencyGapPx,0.001)
        assertEquals(32,pkg.renderPlan.visualEffects.size)
    }

    @Test
    fun musicIsT1IsolatedAndRotationContractIsFourTrackSuccessOnly() = zip().use { z ->
        val audio = JSONObject(z.getInputStream(z.getEntry("audio/catalogue.json")).bufferedReader().use { it.readText() })
        assertEquals(listOf("t1_music_239426","t1_music_464383","t1_music_406552","t1_music_178406"),
            (0 until audio.getJSONArray("pistes").length()).map { audio.getJSONArray("pistes").getJSONObject(it).getString("candidate_id") })
        assertEquals("2", audio.getJSONObject("transition").getString("1"))
        assertEquals("3", audio.getJSONObject("transition").getString("2"))
        assertEquals("4", audio.getJSONObject("transition").getString("3"))
        assertEquals("1", audio.getJSONObject("transition").getString("4"))
        val pkg=load()
        assertEquals(listOf(1,2,3,4),pkg.audioProfile!!.approvedNumbers)
        assertTrue(pkg.audioProfile!!.rotationMode.contains("cycle_4"))
        val files=z.entries().asSequence().map { it.name }.toList()
        assertEquals(3,files.count { it.endsWith(".ttf") })
        assertFalse(files.any { it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".mp4") || it.endsWith(".png") || it.endsWith(".webp") })
        assertTrue(files.any { it.endsWith("T1_special_menu_reference_v3_1.json") })
        assertTrue(files.any { it.endsWith("T1_ENGINEERING_SPEC_V1_2.json") })
    }

    @Test
    fun priceFormattingIsDisplayOnlyAndDoesNotMutateMerchantAnswer() = zip().use { z ->
        val pkg=load()
        val contract=JSONObject(z.getInputStream(z.getEntry("templates/restauration/restauration-t1/montage/contrat-candidat.json")).bufferedReader().use { it.readText() })
        val parsed=ContratCandidatParser.parse(contract)
        val win=parsed.textOverlay!!.windows.first { it.fieldKey=="prix" && it.appendCurrencyWhenAbsent!=null }
        val values=linkedMapOf("prix" to "2500")
        assertEquals("2\u00a0500 FCFA",TextFieldResolver.resolveFieldText(win,parsed.textOverlay!!,values))
        assertEquals("2500",values["prix"])
        values["prix"]="2 500 FCFA"
        assertEquals("2 500 FCFA",TextFieldResolver.resolveFieldText(win,parsed.textOverlay!!,values))
        values["prix"]="Prix sur demande"
        assertEquals("Prix sur demande FCFA",TextFieldResolver.resolveFieldText(win,parsed.textOverlay!!,values))
    }
}
