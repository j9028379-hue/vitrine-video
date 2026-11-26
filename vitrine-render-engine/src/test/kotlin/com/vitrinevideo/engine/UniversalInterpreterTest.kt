package com.vitrinevideo.engine

import com.vitrinevideo.engine.interpreter.TransitionType
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UniversalInterpreterTest {

    @Test
    fun parsesRealCommerceT3PartitionAndAudioProfile() {
        val t3Part = File("/tmp/inspect/t3/montage/partition.json")
        if (!t3Part.exists()) return // Skip gracefully if /tmp/inspect/t3 is absent on CI
        val plan = UniversalJsonInterpreter.parsePartition(t3Part)

        assertEquals("COMMERCE-T3", plan.templateId)
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
        assertEquals(30, plan.fps)
        assertEquals(900, plan.totalFrames)
        assertEquals(4, plan.scenes.size)

        val s2 = plan.scenes[1]
        assertEquals("S2", s2.id)
        val grid = s2.shots.single().gridLayout
        assertNotNull(grid)
        assertEquals(6, grid!!.dividerPx)
        assertEquals(4, grid.viewports.size)
        assertEquals(3, grid.cellCuts.size)

        assertEquals(3, plan.transitions.size)
        assertEquals(TransitionType.QUATRE_VOLETS, plan.transitions[0].type)
        assertEquals(TransitionType.CASE_VERS_PLEIN_CADRE, plan.transitions[1].type)
        assertEquals(TransitionType.BALAYAGE_DIAGONAL, plan.transitions[2].type)

        assertNotNull(plan.textOverlay)
        assertEquals(6, plan.textOverlay!!.windows.size)
        assertEquals(16, plan.textOverlay!!.wave!!.keyframes.size)

        val audioFile = File("/tmp/inspect/t3/audio/profil.json")
        val audio = UniversalJsonInterpreter.parseAudioProfile(audioFile)
        assertEquals("afrobeat_demo_commerce", audio.id)
        assertEquals(listOf(1, 2, 3, 4), audio.approvedNumbers)
        assertEquals(-16.0, audio.mix.targetLufs, 0.01)
        assertEquals(-10.0, audio.mix.underSpeechGainDb, 0.01)
    }

    @Test
    fun parsesRealCommerceT5PartitionWithoutCodeChanges() {
        val t5Part = File("/tmp/inspect/t5/montage/partition.json")
        if (!t5Part.exists()) return
        val plan = UniversalJsonInterpreter.parsePartition(t5Part)

        assertEquals("COMMERCE-T5", plan.templateId)
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
        assertEquals(900, plan.totalFrames)
        assertEquals(4, plan.scenes.size)
        // T5 has zoom coupes inside scenes
        assertTrue(plan.scenes.sumOf { it.shots.size } > 4)
        assertNotNull(plan.textOverlay)
        assertTrue(plan.textOverlay!!.windows.any { it.templateLines != null })
    }

    @Test
    fun parsesEngineeringSpecV12IntoSameUniversalRenderPlan() {
        val v12 = TestAssetLocator.projectFile("test-fixtures/T1_ENGINEERING_SPEC_V1_2.json")
        if (!v12.exists()) return
        val plan = UniversalJsonInterpreter.parsePartition(v12)

        assertEquals("restaurants_maquis_T1", plan.templateId)
        assertEquals(30, plan.fps)
        assertEquals(900, plan.totalFrames)
        assertEquals(4, plan.scenes.size)
        assertEquals(12, plan.scenes.sumOf { it.shots.size })
        assertTrue(plan.transitions.isNotEmpty())
        assertNotNull(plan.textOverlay)
    }
}
