package com.vitrinevideo.engine

import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression assertions that the generated production contract keeps source-authored data. */
class RestaurationT3ContractFidelityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun generatedT3ContractPreservesTransitionProfilesAndTextTracking() {
        val pkg = TemplatePackageLoader.importZip(
            TestAssetLocator.packageZip("RESTAURATION-T3-1.0.0.zip"),
            tmp.newFolder("restauration-t3-contract")
        )
        try {
            val contractFile = pkg.resolvePackageRelative(
                "templates/restauration/restauration-t3/montage/contrat-candidat.json"
            )
            val source = JSONObject(contractFile.readText())
            val first = pkg.renderPlan.transitions.first()
            assertEquals(17, pkg.renderPlan.transitions.size)
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

            val merchantName = pkg.renderPlan.textOverlay!!.windows.first {
                it.startFrame == 0 && it.fieldKey == "nom"
            }
            assertEquals(2.0, merchantName.trackingPx, 0.0001)
            assertEquals(700, merchantName.declaredWeight)
            val rawName = source.getJSONArray("calques").getJSONObject(0)
            assertEquals(rawName.getDouble("tracking_px"), merchantName.trackingPx, 0.0001)

            val audio = pkg.audioProfile?.mix ?: error("T3 audio mix missing")
            assertEquals(-48.0, audio.voiceVadThresholdDbfs!!, 0.0001)
            assertEquals(180, audio.voiceHoldMs)
            assertEquals(4.0, audio.minimumSpeechToMusicDb!!, 0.0001)
            assertEquals(-2.0, audio.truePeakMaxDbtp!!, 0.0001)
        } finally {
            pkg.packageDir.deleteRecursively()
        }
    }

    @Test
    fun generatedT3TransitionsRetainDistinctSourceFamiliesInsteadOfOnlyMotifAliases() {
        val pkg = TemplatePackageLoader.importZip(
            TestAssetLocator.packageZip("RESTAURATION-T3-1.0.0.zip"),
            tmp.newFolder("restauration-t3-families")
        )
        try {
            val transitions = pkg.renderPlan.transitions
            assertEquals(17, transitions.size)
            assertEquals(16, transitions.map { it.sourceFamily }.toSet().size)
            assertTrue(transitions.all { it.sourceParametersJson.isNotBlank() })
            assertTrue(transitions.any { JSONObject(it.sourceParametersJson).has("mask") })
            assertTrue(transitions.any { JSONObject(it.sourceParametersJson).has("easing") })
        } finally {
            pkg.packageDir.deleteRecursively()
        }
    }
}
