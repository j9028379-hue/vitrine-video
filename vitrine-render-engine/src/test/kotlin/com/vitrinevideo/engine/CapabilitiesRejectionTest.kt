package com.vitrinevideo.engine

import com.vitrinevideo.engine.capabilities.UnsupportedCapabilityException
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesRejectionTest {

    private fun basePartition(
        transitionType: String = "cut_sec",
        colorMode: String = "source_native",
        textEntry: String = "coupe",
        scene4End: Int = 900
    ): String = """
        {
          "template_id": "TEST-CAP",
          "version": "1.0.0",
          "fps": 30,
          "dimensions": [720, 1280],
          "images": 900,
          "etalonnage": { "mode": "$colorMode" },
          "scenes": [
            { "id": "S1", "debut": 0, "fin_exclue": 200 },
            { "id": "S2", "debut": 200, "fin_exclue": 450 },
            { "id": "S3", "debut": 450, "fin_exclue": 650 },
            { "id": "S4", "debut": 650, "fin_exclue": $scene4End }
          ],
          "liaisons": [
            { "debut": 200, "duree": 15, "type": "$transitionType" }
          ],
          "texte": {
            "famille": "sans-serif",
            "graisse": 400,
            "couleur": "#FFFFFF",
            "fenetres": [
              { "debut": 10, "fin_exclue": 100, "champ": "nom", "entree": "$textEntry" }
            ]
          }
        }
    """.trimIndent()

    @Test(expected = UnsupportedCapabilityException::class)
    fun rejectsUnknownTransitionNeverSilentlyIgnored() {
        UniversalJsonInterpreter.parsePartitionJson(basePartition(transitionType = "3d_cube_spin"))
    }

    @Test(expected = UnsupportedCapabilityException::class)
    fun rejectsUnknownColorGradingMode() {
        UniversalJsonInterpreter.parsePartitionJson(basePartition(colorMode = "lut_3d_teal_orange"))
    }

    @Test(expected = UnsupportedCapabilityException::class)
    fun rejectsUnknownTextAnimation() {
        UniversalJsonInterpreter.parsePartitionJson(basePartition(textEntry = "explosion_3d_particules"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBrokenTimelineTotalFrames() {
        UniversalJsonInterpreter.parsePartitionJson(basePartition(scene4End = 850))
    }

    @Test
    fun reportsExplicitUnsupportedWireNameInExceptionMessage() {
        try {
            UniversalJsonInterpreter.parsePartitionJson(basePartition(transitionType = "liquid_morph_ai"))
        } catch (e: UnsupportedCapabilityException) {
            assertTrue(e.unsupportedCapabilities.contains("transition.liquid_morph_ai"))
            assertTrue(e.message!!.contains("liquid_morph_ai"))
        }
    }
}
