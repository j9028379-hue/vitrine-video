package com.vitrinevideo.engine

import com.vitrinevideo.engine.interpreter.TransitionSpec
import com.vitrinevideo.engine.interpreter.TransitionType
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.timeline.TimelineEngine
import com.vitrinevideo.engine.video.TransitionGeometry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TimelineAndGeometryTest {

    @Test
    fun t3GridPermutationsAndTimestampsMatchSpec() {
        val t3Part = File("/tmp/inspect/t3/montage/partition.json")
        if (!t3Part.exists()) return
        val plan = UniversalJsonInterpreter.parsePartition(t3Part)
        val tl = TimelineEngine(plan)

        assertEquals(0L, tl.frameToUs(0))
        assertEquals(6_000_000L, tl.frameToUs(180))
        assertEquals(30_000_000L, tl.frameToUs(900))
        assertEquals(180, tl.usToFrame(6_000_000L))

        val st180 = tl.stateAtFrame(180)
        assertEquals("S2", st180.scene.id)
        assertEquals(listOf(0, 1, 2, 3), st180.gridOrder)

        val st263 = tl.stateAtFrame(263)
        assertEquals(listOf(1, 3, 0, 2), st263.gridOrder)

        val st351 = tl.stateAtFrame(351)
        assertEquals(listOf(3, 2, 1, 0), st351.gridOrder)
    }

    @Test
    fun transitionGeometryCoversStartMidAndEndCorrectly() {
        val qv = TransitionSpec(180, 18, TransitionType.QUATRE_VOLETS)
        val start = TransitionGeometry.evaluate(qv, 180)!!
        assertEquals(0f, TransitionGeometry.sampleIncomingAlpha(start, 0.25f, 0.25f), 0.001f)

        val end = TransitionGeometry.evaluate(qv, 197)!!
        assertEquals(1f, TransitionGeometry.sampleIncomingAlpha(end, 0.25f, 0.25f), 0.001f)
        assertEquals(1f, TransitionGeometry.sampleIncomingAlpha(end, 0.75f, 0.75f), 0.001f)

        val caseToFull = TransitionSpec(420, 17, TransitionType.CASE_VERS_PLEIN_CADRE)
        val midCase = TransitionGeometry.evaluate(caseToFull, 428)!!
        assertEquals(1f, TransitionGeometry.sampleIncomingAlpha(midCase, 0.2f, 0.8f), 0.001f)
        assertEquals(0f, TransitionGeometry.sampleIncomingAlpha(midCase, 0.95f, 0.05f), 0.001f)

        val diag = TransitionSpec(630, 15, TransitionType.BALAYAGE_DIAGONAL)
        val diagEnd = TransitionGeometry.evaluate(diag, 644)!!
        assertEquals(1f, TransitionGeometry.sampleIncomingAlpha(diagEnd, 0.99f, 0.99f), 0.001f)
    }
}
