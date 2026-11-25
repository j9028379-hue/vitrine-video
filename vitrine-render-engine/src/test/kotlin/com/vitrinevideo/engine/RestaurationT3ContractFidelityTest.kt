package com.vitrinevideo.engine

import com.vitrinevideo.engine.interpreter.ContratCandidatParser
import com.vitrinevideo.engine.interpreter.MusicCatalogueParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

/** Regression assertions that generated production data reaches the engine models. */
class RestaurationT3ContractFidelityTest {
    private fun <T> withT3Zip(block: (ZipFile) -> T): T =
        ZipFile(TestAssetLocator.packageZip("RESTAURATION-T3-1.0.0.zip")).use(block)

    private fun ZipFile.json(path: String): JSONObject =
        getInputStream(getEntry(path) ?: error("Missing package entry: $path"))
            .bufferedReader().use { JSONObject(it.readText()) }

    @Test
    fun generatedT3ContractPreservesTransitionProfilesTextTrackingAndAudioSettings() = withT3Zip { zip ->
        val contract = zip.json("templates/restauration/restauration-t3/montage/contrat-candidat.json")
        val plan = ContratCandidatParser.parse(contract)
        val first = plan.transitions.first()
        assertEquals(17, plan.transitions.size)
        assertEquals("R4_TILED_GRID_INTRO", first.sourceFamily)
        assertEquals("repeated_tile_grid+grid_scale_in", first.sourceVariant)
        assertEquals("R4_TILED_GRID_INTRO", first.sourceProfile)
        assertEquals(listOf(0, 17), first.sourceFramesInclusive)
        assertEquals(18, first.sourceDurationFrames)
        val retainedGrid = JSONObject(first.sourceParametersJson).getJSONObject("grid")
        assertEquals(3, retainedGrid.getInt("columns"))
        assertEquals(4, retainedGrid.getInt("rows"))
        assertEquals(4, retainedGrid.getInt("gap_px"))
        assertEquals(12, retainedGrid.getJSONArray("tile_time_offsets_frames").length())

        val merchantName = plan.textOverlay!!.windows.first {
            it.fieldKey == "nom" && it.trackingPx == 2.0
        }
        assertEquals(2.0, merchantName.trackingPx, 0.0001)
        assertEquals(700, merchantName.declaredWeight)
        val rawName = contract.getJSONArray("calques").getJSONObject(0)
        assertEquals(rawName.getDouble("tracking_px"), merchantName.trackingPx, 0.0001)

        val catalogue = zip.json("audio/catalogue.json")
        val resources = buildMap {
            val tracks = catalogue.getJSONArray("pistes")
            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                put(track.getInt("numero"), MusicCatalogueParser.DeclaredResource(
                    relativePath = "audio/pistes/${track.getInt("numero").toString().padStart(2, '0')}.mp3",
                    sha256 = track.getString("sha256"),
                    bytes = track.getLong("octets"),
                    startSec = track.optDouble("source_debut_s", 0.0)
                ))
            }
        }
        val mix = MusicCatalogueParser.parse(catalogue, zip.json("audio/voix.json"), resources).mix
        assertEquals(-48.0, mix.voiceVadThresholdDbfs!!, 0.0001)
        assertEquals(180, mix.voiceHoldMs)
        assertEquals(4.0, mix.minimumSpeechToMusicDb!!, 0.0001)
        assertEquals(-2.0, mix.truePeakMaxDbtp!!, 0.0001)
    }

    @Test
    fun generatedT3TransitionsRetainDistinctSourceFamiliesInsteadOfOnlyMotifAliases() = withT3Zip { zip ->
        val plan = ContratCandidatParser.parse(
            zip.json("templates/restauration/restauration-t3/montage/contrat-candidat.json")
        )
        val transitions = plan.transitions
        assertEquals(17, transitions.size)
        assertEquals(16, transitions.map { it.sourceFamily }.toSet().size)
        assertTrue(transitions.all { it.sourceParametersJson.isNotBlank() })
        assertTrue(transitions.any { JSONObject(it.sourceParametersJson).has("mask") })
        assertTrue(transitions.any { JSONObject(it.sourceParametersJson).has("easing") })
    }
}
