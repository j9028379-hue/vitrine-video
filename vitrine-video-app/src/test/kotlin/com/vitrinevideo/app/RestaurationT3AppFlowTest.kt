package com.vitrinevideo.app

import com.vitrinevideo.app.flow.MockupResponseMapper
import com.vitrinevideo.app.flow.TemplateCatalogue
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** RESTAURATION-T3 selective package, existing merchant flow and shared engine integration. */
class RestaurationT3AppFlowTest {
    private fun assetsRoot(): File {
        val candidates = listOf(File("src/main/assets"), File("vitrine-video-app/src/main/assets"))
        return candidates.firstOrNull { it.isDirectory } ?: error("assets root not found")
    }

    private fun packageFile() = File(assetsRoot(), "packages/RESTAURATION-T3-1.0.0.zip")

    private fun load() = TemplatePackageLoader.importZip(
        packageFile(), kotlin.io.path.createTempDirectory("restauration_t3_pkg").toFile(), File(assetsRoot(), "music")
    )

    @Test
    fun catalogueAndRepositoryExposeT3UnderTheApprovedMerchantName() {
        assertEquals("RESTAURATION-T3", TemplateCatalogue.RESTAURATION_T3)
        assertTrue(TemplateCatalogue.isSupported(TemplateCatalogue.RESTAURATION_T3))
        assertEquals("Coulisses de préparation", TemplateCatalogue.displayName(TemplateCatalogue.RESTAURATION_T3, true))
        assertEquals("Behind the preparation", TemplateCatalogue.displayName(TemplateCatalogue.RESTAURATION_T3, false))
        assertEquals(8, TemplateCatalogue.SUPPORTED_IDS.size)
        assertFalse(TemplateCatalogue.SUPPORTED_IDS.contains("RESTAURATION-T4"))
        val repository = File(
            listOf(File("src/main/kotlin"), File("vitrine-video-app/src/main/kotlin")).first { it.isDirectory },
            "com/vitrinevideo/app/flow/TemplatePackageRepository.kt"
        ).readText()
        assertTrue(repository.contains("packages/RESTAURATION-T3-1.0.0.zip"))
    }

    @Test
    fun approvedFormUsesOnlyMerchantValuesAndValidatesAgainstTheUniversalPackage() {
        val answers = linkedMapOf(
            "nom" to "Chez Afi", "produit" to "Poulet braisé et alloco", "prix" to "2500",
            "whatsapp" to "+229 01 97 00 00 00", "quartier" to "Kandi, marché central", "accroche" to ""
        )
        val pkg = load()
        val mapped = MockupResponseMapper.parseFromWebJson(JSONObject(answers).toString(), TemplateCatalogue.RESTAURATION_T3)
        assertEquals(answers, mapped)
        assertEquals(answers, TemplateJobAssembler.validateResponses(pkg, mapped))
        assertEquals(setOf("nom", "produit", "prix", "whatsapp", "quartier"), pkg.formSpec.requiredKeys())
        assertEquals(6, pkg.formSpec.keys().size)
        val schema = JSONObject(pkg.resolvePackageRelative("templates/restauration/restauration-t3/form/schema.json").readText())
        assertEquals(5, schema.getJSONArray("required").length())
        assertEquals("^[0-9 .]+$", schema.getJSONObject("properties").getJSONObject("prix").getString("pattern"))
    }

    @Test
    fun t3IsAUniversalRenderPlanWithExactTimelineAndV04EventWindows() {
        val pkg = load()
        val plan = pkg.renderPlan
        assertEquals("RESTAURATION-T3", plan.templateId)
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
        assertEquals(30, plan.fps)
        assertEquals(900, plan.totalFrames)
        assertEquals(listOf("S1", "S2", "S3", "S4"), plan.scenes.map { it.id })
        assertEquals(listOf(6, 8, 7, 9), plan.scenes.map { (it.endFrameExcluded - it.startFrame) / plan.fps })
        assertEquals(17, plan.transitions.size)
        assertTrue(plan.transitions.all { it.startFrame >= 0 && it.startFrame + it.durationFrames <= 900 })
        assertEquals(listOf(0, 18, 78, 171, 224, 252, 303, 344, 374, 408, 453, 540, 617, 699, 768, 824, 885), plan.transitions.map { it.startFrame })
        val transitionsContract = JSONObject(pkg.resolvePackageRelative("contracts/reverse-engineering/T3_TRANSITIONS_30S.json").readText())
        assertEquals("1.1.0-owner-corrected", transitionsContract.optString("schema_version"))
        assertFalse(transitionsContract.getJSONObject("owner_constraints").getBoolean("visible_transition_guides_allowed"))
        val adapted = JSONObject(pkg.partitionFile.readText())
        assertTrue((0 until adapted.getJSONArray("effets").length()).all { i ->
            val effect = adapted.getJSONArray("effets").getJSONObject(i)
            !effect.getBoolean("separator_lines") && effect.getInt("gap_px") == 0 && effect.getInt("border_px") == 0
        })
    }

    @Test
    fun captureFlowRetainsTwelveStatesAndSharedCountdownOutsideCaptureDurations() {
        val pkg = load()
        assertEquals(4, pkg.captureGuides.size)
        assertEquals(listOf(6, 8, 7, 9), pkg.captureGuides.map { it.durationSec })
        val guide = JSONObject(pkg.resolvePackageRelative("templates/restauration/restauration-t3/capture-guide/ecrans.json").readText())
        assertEquals(12, guide.getJSONArray("ecrans").length())
        assertEquals(listOf(3, 2, 1), (0..2).map { guide.getJSONArray("transient_states").getJSONObject(0).getJSONArray("sequence").getInt(it) })
        assertFalse(guide.getJSONArray("transient_states").getJSONObject(0).getBoolean("counted_in_12_stable_screens"))
        assertTrue(guide.getJSONObject("runtime_rules").getBoolean("microphone_requested"))
        assertFalse(guide.getJSONObject("runtime_rules").getBoolean("automatic_subtitles"))
    }

    @Test
    fun approvedMusicRotatesInOrderAndAllThreeFontAssetsAreHashedAndUsedOnce() {
        val pkg = load()
        assertEquals(listOf(1, 2, 3, 4), pkg.audioProfile?.approvedNumbers)
        assertTrue(pkg.audioProfile?.rotationMode.orEmpty().contains("cycle_4"))
        val audio = JSONObject(pkg.resolvePackageRelative("audio/catalogue.json").readText())
        assertEquals(listOf("2", "3", "4", "1"), (1..4).map { audio.getJSONObject("transition").getString("$it") })
        val motion = JSONObject(pkg.resolvePackageRelative("contracts/config/T3_TEXT_MOTION_V0_4_0.json").readText())
        assertEquals(3, motion.getJSONObject("fonts").length())
        ZipFile(packageFile()).use { zip ->
            val ttf = zip.entries().asSequence().filter { it.name.endsWith(".ttf") }.map { it.name }.toList()
            assertEquals(3, ttf.size)
            assertTrue(ttf.any { it.endsWith("DMSerifDisplay-Regular.ttf") })
            assertTrue(ttf.any { it.endsWith("Montserrat-Variable.ttf") })
            assertTrue(ttf.any { it.endsWith("PlusJakartaSans-Variable.ttf") })
        }
    }

    @Test
    fun productionPackageHasNoCasaTacosVoiceOrDemoMediaAndAppRoutesR3WithoutSoonBadge() {
        ZipFile(packageFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertFalse(names.any { it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".mp4") || it.endsWith(".png") || it.endsWith(".webp") })
            assertFalse(names.any { it.contains("t3-voice-v1") || it.contains("t3-voice-candidates") || it.startsWith("fixtures/") })
            val allText = names.filterNot { it.endsWith("/") }.joinToString("\n") { name ->
                zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
            }
            assertFalse(allText.contains("Casa Tacos", ignoreCase = true))
            assertFalse(allText.contains("voice-02", ignoreCase = true))
        }
        val root = assetsRoot()
        val html = File(root, "mockup/vitrine-video-maquette.html").readText()
        val bridge = File(root, "mockup/android-bridge.js").readText()
        assertTrue(html.contains("data-i18n=\"formats.r3.title\">Coulisses de préparation"))
        assertTrue(bridge.contains("fmt === \"r3\" ? \"RESTAURATION-T3\""))
        assertTrue(bridge.contains("\"r2\", \"r3\", \"r4\"].includes(fmt)"))
        assertTrue(bridge.contains("if (activeTemplateFormat === \"r3\") return {"))
        assertFalse(bridge.contains("startWithCountdown"))
        val guideStart = html.indexOf("if(flow.stage===\"guide\"){")
        val guideEnd = html.indexOf("const audio=$(\"#guide-audio\")", guideStart)
        val markup = html.substring(guideStart, guideEnd)
        assertTrue(markup.indexOf("guide-readable-header") < markup.indexOf("guide-scene-image"))
        assertTrue(markup.contains("flow.template===\"r3\"?\" · \"+s.dur+\" s\":"))
        for (n in 1..4) {
            assertTrue(html.contains("assets/images/r3-scene$n-portrait.webp"))
            assertTrue(html.contains("assets/audio/r3-scene$n-guide.mp3"))
        }
    }
}
