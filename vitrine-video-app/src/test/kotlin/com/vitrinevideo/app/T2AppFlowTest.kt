package com.vitrinevideo.app

import com.vitrinevideo.app.flow.MockupResponseMapper
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T2 is reachable through the existing sector → template → form journey, without new UI. */
class T2AppFlowTest {

    @Test
    fun goldenSixAnswersMapToT2ContractKeysWithoutSubstitution() {
        val answers = mapOf(
            "nom" to "Sillon Studio",
            "produit" to "Platine vinyle rétro",
            "prix" to "48 000 F CFA",
            "accroche" to "Le rituel du vinyle",
            "quartier" to "Cadjèhoun, Cotonou",
            "whatsapp" to "+1 202 555 0168"
        )
        val mapped = MockupResponseMapper.parseFromWebJson(JSONObject(answers).toString(), "COMMERCE-T2")
        assertEquals(answers, mapped)
        assertEquals(6, mapped.size)
    }

    @Test
    fun legacyDemonstrationKeyIsAliasedToT2Accroche() {
        val raw = mapOf(
            "nom" to "Sillon Studio",
            "produit" to "Platine vinyle rétro",
            "prix" to "48 000 F CFA",
            "demonstration" to "Le rituel du vinyle",
            "quartier" to "Cadjèhoun, Cotonou",
            "whatsapp" to "+1 202 555 0168"
        )
        val mapped = MockupResponseMapper.parseFromWebJson(JSONObject(raw).toString(), "COMMERCE-T2")
        assertFalse("demonstration" in mapped)
        assertEquals("Le rituel du vinyle", mapped["accroche"])
        assertEquals(6, mapped.size)
        // T3 legacy direction must stay intact.
        val t3 = MockupResponseMapper.normalizeRawResponses(mapOf("accroche" to "Geste"), "COMMERCE-T3")
        assertEquals("Geste", t3["demonstration"])
        assertFalse("accroche" in t3)
    }

    @Test
    fun mappedT2AnswersPassRenderJobSchemaValidation() {
        val root = listOf(File("src/main/assets"), File("vitrine-video-app/src/main/assets"))
            .firstOrNull { it.isDirectory } ?: error("assets root not found")
        val pkg = TemplatePackageLoader.importZip(
            File(root, "packages/COMMERCE-T2-1.0.0.zip"),
            kotlin.io.path.createTempDirectory("t2_schema").toFile(),
            File(root, "music")
        )
        val legacyUiPayload = JSONObject().apply {
            put("nom", "Sillon Studio")
            put("produit", "Platine vinyle rétro")
            put("prix", "48 000 F CFA")
            put("demonstration", "Le rituel du vinyle")
            put("quartier", "Cadjèhoun, Cotonou")
            put("whatsapp", "+1 202 555 0168")
        }.toString()
        val mapped = MockupResponseMapper.parseFromWebJson(legacyUiPayload, "COMMERCE-T2")
        val validated = TemplateJobAssembler.validateResponses(pkg, mapped)
        assertEquals(6, validated.size)
        assertEquals("Le rituel du vinyle", validated["accroche"])
        assertFalse("demonstration" in validated)
    }

    @Test
    fun webBridgeEnablesT2TemplateSelection() {
        val root = listOf(File("src/main/assets"), File("vitrine-video-app/src/main/assets"))
            .firstOrNull { it.isDirectory } ?: error("assets root not found")
        val bridge = File(root, "mockup/android-bridge.js").readText()
        assertTrue(bridge.contains("[\"t1\", \"t2\", \"t3\", \"t4\", \"r2\"].includes(fmt)"))
        assertTrue(bridge.contains("fmt === \"t2\" ? \"COMMERCE-T2\""))
        assertTrue(bridge.contains("activeTemplateFormat === \"t2\" || activeTemplateFormat === \"r2\""))
        assertTrue(bridge.contains("accroche: val(\"q-accroche\")"))
        assertTrue(bridge.contains(".format-card[data-format=\"t2\"]"))
        assertFalse(bridge.contains("[\"beaute\", \"resto\"].forEach"))
    }
}
