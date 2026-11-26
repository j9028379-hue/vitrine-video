package com.vitrinevideo.app

import com.vitrinevideo.app.flow.MockupResponseMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class T4ResponseContractTest {
    @Test
    fun t4KeysPassThroughAndOptionalEndConditionRemainsOptional() {
        val answers = linkedMapOf(
            "commerce" to "Boutique Grâce",
            "produit" to "Sac à main",
            "habituel" to "10 000 F",
            "promo" to "7 000 F",
            "localisation" to "Kandi, près du marché",
            "whatsapp" to "01 XX XX XX XX"
        )
        assertEquals(answers + ("fin" to ""), MockupResponseMapper.normalizeRawResponses(answers, "COMMERCE-T4"))
    }

    @Test
    fun t4ExplicitEndConditionIsNeverRewritten() {
        val value = "Jusqu’à épuisement du stock"
        assertEquals(value, MockupResponseMapper.normalizeRawResponses(mapOf("fin" to value), "COMMERCE-T4")["fin"])
    }
}
