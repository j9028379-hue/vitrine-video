package com.vitrinevideo.engine

import com.vitrinevideo.engine.interpreter.ContratCandidatParser
import com.vitrinevideo.engine.interpreter.MusicCatalogueParser
import com.vitrinevideo.engine.graphics.TextMotionEvaluator
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
        assertEquals(30.0, merchantName.minFontSizePx!!, 0.0001)
        assertEquals("T3_G01_IDENTITY", merchantName.motionGroupId)
        assertEquals("left_to_right_mask_plus_soft_rise", merchantName.sourceMotionName)
        assertEquals(
            listOf(
                "left_to_right_mask_plus_soft_rise",
                "split_reveal_with_price_settle",
                "center_bloom_and_signature",
                "sequential_identity_contact_lockup"
            ),
            plan.textOverlay!!.motionGroups.map { it.motionName }
        )
        assertEquals(listOf(18.0, 14.0, 16.0, 12.0), plan.textOverlay!!.motionGroups.map { it.risePx })
        assertEquals(18.0, plan.textOverlay!!.motionGroups[1].priceRisePx, 0.0001)
        assertEquals(0.66, plan.textOverlay!!.motionGroups[0].scrimTop, 0.0001)
        assertEquals(0.8, plan.textOverlay!!.motionGroups[3].scrimBottom, 0.0001)
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
    fun fourTextMotionsFollowSourceWindowsAndReferenceCurvesAtStartMiddleAndEnd() = withT3Zip { zip ->
        val plan = ContratCandidatParser.parse(
            zip.json("templates/restauration/restauration-t3/montage/contrat-candidat.json")
        )
        val overlay = plan.textOverlay!!
        val identity = overlay.motionGroups[0]
        val identityStart = TextMotionEvaluator.evaluate(identity, 18, plan.width, plan.totalFrames)!!
        val identityMid = TextMotionEvaluator.evaluate(identity, 32, plan.width, plan.totalFrames)!!
        val identityEnd = TextMotionEvaluator.evaluate(identity, 47, plan.width, plan.totalFrames)!!
        assertTrue(identityStart.progress in 0f..identityMid.progress)
        assertEquals(0.5f, identityMid.progress, 0.0001f)
        assertEquals(9f, identityMid.riseOffsetPx, 0.0001f)
        assertEquals(360f, identityMid.revealWidthPx, 0.001f)
        assertEquals(1f, identityEnd.progress, 0.0001f)
        assertEquals(0f, identityEnd.riseOffsetPx, 0.0001f)

        val offer = overlay.motionGroups[1]
        val offerMid = TextMotionEvaluator.evaluate(offer, 221, plan.width, plan.totalFrames)!!
        assertEquals(0.5f, offerMid.progress, 0.0001f)
        assertEquals(9f, offerMid.priceSettleOffsetPx, 0.0001f)
        assertEquals(14.0, offer.risePx, 0.0001)

        val hook = overlay.motionGroups[2]
        val hookMid = TextMotionEvaluator.evaluate(hook, 464, plan.width, plan.totalFrames)!!
        assertEquals(0.5f, hookMid.progress, 0.0001f)
        assertEquals(8f, hookMid.riseOffsetPx, 0.0001f)
        val final = overlay.motionGroups.last()
        val finalMid = TextMotionEvaluator.evaluate(final, 683, plan.width, plan.totalFrames)!!
        assertEquals(0.5f, finalMid.progress, 0.0001f)
        assertEquals(6f, finalMid.riseOffsetPx, 0.0001f)
        val finalEnd = TextMotionEvaluator.evaluate(final, 899, plan.width, plan.totalFrames)!!
        assertEquals(1f, finalEnd.opacity, 0.0001f)
        assertEquals(1.0, final.opacityAtEnd!!, 0.0001)
        assertTrue(TextMotionEvaluator.needsFrameUpdate(identity, 32, plan.totalFrames))
        assertTrue(!TextMotionEvaluator.needsFrameUpdate(identity, 100, plan.totalFrames))
        assertTrue(TextMotionEvaluator.needsFrameUpdate(identity, 140, plan.totalFrames))
        assertTrue(!TextMotionEvaluator.needsFrameUpdate(final, 899, plan.totalFrames))
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
