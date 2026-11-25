package com.vitrinevideo.app

import com.vitrinevideo.app.flow.MockupResponseMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    fun webBridgeEnablesT2TemplateSelection() {
        val root = listOf(File("src/main/assets"), File("vitrine-video-app/src/main/assets"))
            .firstOrNull { it.isDirectory } ?: error("assets root not found")
        val bridge = File(root, "mockup/android-bridge.js").readText()
        assertTrue(bridge.contains("fmt !== \"t2\""))
        assertTrue(bridge.contains("\"t2\" ? \"COMMERCE-T2\""))
    }
}
