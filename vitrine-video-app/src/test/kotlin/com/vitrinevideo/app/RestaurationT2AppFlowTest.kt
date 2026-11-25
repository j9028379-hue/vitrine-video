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

/** Restauration T2 reachable in the existing app, with neutral cards and six exact fields. */
class RestaurationT2AppFlowTest {

    private fun assetsRoot(): File {
        val candidates = listOf(File("src/main/assets"), File("vitrine-video-app/src/main/assets"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("assets root not found from ${File(".").absolutePath}")
    }

    @Test
    fun onlyRestaurationT2IsEnabledAndNoDomainIdCollides() {
        assertEquals("RESTAURATION-T2", TemplateCatalogue.RESTAURATION_T2)
        assertTrue(TemplateCatalogue.isSupported("COMMERCE-T1"))
        assertTrue(TemplateCatalogue.isSupported("COMMERCE-T2"))
        assertTrue(TemplateCatalogue.isSupported("COMMERCE-T3"))
        assertTrue(TemplateCatalogue.isSupported("COMMERCE-T4"))
        assertTrue(TemplateCatalogue.isSupported("RESTAURATION-T2"))
        assertEquals(5, TemplateCatalogue.SUPPORTED_IDS.size)
        assertFalse(TemplateCatalogue.SUPPORTED_IDS.any { it.startsWith("RESTAURATION-T") && it != "RESTAURATION-T2" })
        assertFalse(TemplateCatalogue.SUPPORTED_IDS.contains("RESTAURATION-T4"))
        val html = File(assetsRoot(), "mockup/vitrine-video-maquette.html").readText()
        assertFalse(html.contains("témoignage client", ignoreCase = true))
        assertFalse(html.contains("Témoignage", ignoreCase = true))
    }

    @Test
    fun collectedSixFieldsMapExactlyAndEmptyAccrocheStaysEmpty() {
        val answers = linkedMapOf(
            "nom" to "Chez Maman",
            "produit" to "Poulet braisé",
            "prix" to "2500",
            "whatsapp" to "+229 01 97 00 00 00",
            "quartier" to "Kandi, marché",
            "accroche" to ""
        )
        val mapped = MockupResponseMapper.parseFromWebJson(JSONObject(answers).toString(), TemplateCatalogue.RESTAURATION_T2)
        assertEquals(answers, mapped)
        assertEquals("", mapped["accroche"])
        val legacy = MockupResponseMapper.normalizeRawResponses(
            mapOf("nom" to "Chez Maman", "produit" to "Poulet", "prix" to "2500",
                "whatsapp" to "+229", "quartier" to "Kandi", "demonstration" to "Libre"),
            TemplateCatalogue.RESTAURATION_T2
        )
        assertFalse(legacy.containsKey("demonstration"))
        assertEquals("Libre", legacy["accroche"])
    }

    @Test
    fun mappedAnswersPassTheBundledRestaurantT2RenderJobSchema() {
        val root = assetsRoot()
        val pkg = TemplatePackageLoader.importZip(
            File(root, "packages/RESTAURATION-T2-1.0.0.zip"),
            kotlin.io.path.createTempDirectory("resto_t2_schema").toFile(),
            File(root, "music")
        )
        val answers = mapOf(
            "nom" to "Chez Maman", "produit" to "Poulet braisé", "prix" to "2500",
            "whatsapp" to "+229 01 97 00 00 00", "quartier" to "Kandi", "accroche" to ""
        )
        val validated = TemplateJobAssembler.validateResponses(pkg, answers)
        assertEquals(6, validated.size)
        assertEquals("", validated["accroche"])
        val voices = JSONObject(pkg.resolvePackageRelative("audio/voix.json").readText())
        assertEquals("captured_scene_audio", voices.getJSONArray("clips").getJSONObject(0).getString("source"))
        assertTrue(voices.getString("clips_policy").contains("No registered synthetic narration"))
        assertEquals(false, voices.getBoolean("music_template_default"))
        assertEquals(1.0, voices.getDouble("source_speed"), 0.0001)
    }

    @Test
    fun cardsAreNeutralAtRestAndActiveOnlyAfterAUserClick() {
        val html = File(assetsRoot(), "mockup/vitrine-video-maquette.html").readText()
        val bridge = File(assetsRoot(), "mockup/android-bridge.js").readText()
        assertFalse("No card may be preselected in markup", html.contains("format-card--featured"))
        assertTrue(html.contains("selectedFormat: null }"))
        assertTrue(html.contains("function applyFormatCardSelection()"))
        assertTrue(html.contains("card.classList.toggle(\"is-selected\", card.dataset.format===state.selectedFormat)"))
        assertTrue(html.contains("state.selectedFormat=selectedFormat"))
        assertTrue(html.contains(".format-card.is-selected"))
        assertTrue(html.contains("state.selectedFormat=null"))
        assertTrue(bridge.contains("RESTAURATION-T2"))
        assertTrue(bridge.contains(".format-card[data-format=\"r2\"]"))
    }

    @Test
    fun bridgeBlocksOtherRestaurationTemplatesButEnablesAvantApres() {
        val bridge = File(assetsRoot(), "mockup/android-bridge.js").readText()
        assertTrue(bridge.contains("sector !== \"commerce\" && sector !== \"resto\""))
        assertTrue(bridge.contains("[\"t1\", \"t2\", \"t3\", \"t4\", \"r2\"].includes(fmt)"))
        assertFalse(bridge.contains("\"r1\"].includes(fmt)"))
        assertTrue(bridge.contains("fmt === \"r2\" ? \"RESTAURATION-T2\""))
        assertTrue(bridge.contains("activeTemplateFormat === \"t2\" || activeTemplateFormat === \"r2\""))
        assertFalse(bridge.contains("window.VitrineAndroid.onTemplateSelected(\"RESTAURATION-T4\")"))
    }
}
