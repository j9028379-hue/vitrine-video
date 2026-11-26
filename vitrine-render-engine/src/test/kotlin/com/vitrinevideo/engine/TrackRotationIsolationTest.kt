package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.RotationPersistence
import com.vitrinevideo.engine.audio.TrackRotationCoordinator
import com.vitrinevideo.engine.interpreter.ApprovedTrack
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import com.vitrinevideo.engine.interpreter.AudioProfileSpec
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackRotationIsolationTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemoryRotationStore : RotationPersistence {
        private val committed = mutableListOf<Triple<String, String, Int>>()
        override fun findTrackForJob(profileId: String, runId: String): Int? =
            committed.lastOrNull { it.first == profileId && it.second == runId }?.third
        override fun getLastTrack(profileId: String): Int? =
            committed.lastOrNull { it.first == profileId }?.third
        override fun getHistory(profileId: String): List<Int> =
            committed.filter { it.first == profileId }.map { it.third }
        override fun recordSuccess(profileId: String, runId: String, trackNumber: Int) {
            if (findTrackForJob(profileId, runId) == null) committed += Triple(profileId, runId, trackNumber)
        }
    }

    private fun profile(id: String, directory: String, numbers: List<Int> = listOf(1, 2, 3, 4)): AudioProfileSpec {
        val tracks = numbers.map { n ->
            ApprovedTrack(n, "$id track $n", "artist", "licence", "credit", "$directory/$n.mp3", "")
        }
        return AudioProfileSpec(id, "1", "validé", "cycle_1_2_3_4_apres_succes", numbers, true, true, tracks, AudioMixConfig(id, includeCaptureVoice = true))
    }

    @Test
    fun eightSelectionsPerProfileRotatePersistAndStayIndependentWhenInterleaved() {
        val resources = tmp.newFolder("resources")
        val t1 = profile("t1-profile", "t1")
        val t3 = profile("t3-profile", "t3")
        listOf(t1, t3).forEach { p ->
            p.tracks.forEach { track ->
                File(resources, track.relativePath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(track.number.toByte())) }
            }
        }
        val coordinator = TrackRotationCoordinator(MemoryRotationStore(), resources)
        val observed = mutableMapOf(t1.id to mutableListOf<Int>(), t3.id to mutableListOf<Int>())

        repeat(8) { index ->
            for (p in listOf(t1, t3)) {
                val runId = "${p.id}-run-$index"
                val (track, file) = coordinator.selectTrackForRender(p, runId)
                assertTrue(file.isFile)
                observed.getValue(p.id) += track.number
                coordinator.commitAfterVerifiedRender(p.id, runId, track.number)
            }
        }
        assertEquals(listOf(1, 2, 3, 4, 1, 2, 3, 4), observed.getValue(t1.id))
        assertEquals(listOf(1, 2, 3, 4, 1, 2, 3, 4), observed.getValue(t3.id))
    }

    private fun bundledPackage(name: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(10) {
            val candidate = File(dir, "vitrine-video-app/src/main/assets/packages/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: dir
        }
        error("Could not locate bundled package $name from ${System.getProperty("user.dir")}")
    }

    @Test
    fun bundledTemplatesSelectTheirOwnHashedAudioFiles() {
        val t1 = TemplatePackageLoader.importZip(bundledPackage("COMMERCE-T1-2.0.0.zip"), tmp.newFolder("bundled-t1"))
        val t3 = TemplatePackageLoader.importZip(bundledPackage("COMMERCE-T3-1.0.0.zip"), tmp.newFolder("bundled-t3"))
        val t4 = TemplatePackageLoader.importZip(bundledPackage("COMMERCE-T4-1.0.0.zip"), tmp.newFolder("bundled-t4"))
        assertEquals("afrobeat_amapiano_commerce", t1.audioProfile!!.id)
        assertEquals("afrobeat_demo_commerce", t3.audioProfile!!.id)
        assertEquals("promo_afrobeats", t4.audioProfile!!.id)
        val persistence = MemoryRotationStore()
        val packages = listOf(t1, t3, t4)
        val coordinators = packages.associate { it.templateId to TrackRotationCoordinator(persistence, it.packageDir) }
        val sequence = packages.associate { it.audioProfile!!.id to mutableListOf<Int>() }
        repeat(8) { index ->
            for (pkg in packages) {
                val profile = pkg.audioProfile!!
                val coordinator = coordinators.getValue(pkg.templateId)
                val runId = "${profile.id}-real-package-$index"
                val (selected, file) = coordinator.selectTrackForRender(profile, runId)
                assertTrue(file.isFile)
                assertEquals(pkg.resolvePackageRelative(selected.relativePath).canonicalFile, file.canonicalFile)
                assertEquals(selected.sha256, PreFlightValidator.sha256File(file))
                assertTrue("Selected audio must stay inside ${pkg.templateId}'s extracted package",
                    file.canonicalPath.startsWith(pkg.packageDir.canonicalPath + File.separator))
                sequence.getValue(profile.id) += selected.number
                coordinator.commitAfterVerifiedRender(profile.id, runId, selected.number)
            }
        }
        assertEquals(listOf(1, 2, 3, 4, 1, 2, 3, 4), sequence.getValue(t1.audioProfile!!.id))
        assertEquals(listOf(1, 2, 3, 4, 1, 2, 3, 4), sequence.getValue(t3.audioProfile!!.id))
        val t4Selection = sequence.getValue(t4.audioProfile!!.id)
        assertTrue(t4Selection.all { it in listOf(1, 3, 4) })
        assertTrue(t4Selection.zipWithNext().all { (a, b) -> a != b })
        val t4Counts = t4Selection.groupingBy { it }.eachCount().values
        assertTrue(t4Counts.maxOrNull()!! - t4Counts.minOrNull()!! <= 1)
        assertEquals(3, listOf(t1, t3, t4).map { it.packageDir.canonicalPath }.toSet().size)
    }

    @Test
    fun retryUsesSameJobSelectionAndSelectionDoesNotAdvanceUntilSuccess() {
        val resources = tmp.newFolder("resources")
        val p = profile("retry-profile", "retry")
        p.tracks.forEach { File(resources, it.relativePath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(it.number.toByte())) } }
        val coordinator = TrackRotationCoordinator(MemoryRotationStore(), resources)
        assertEquals(1, coordinator.selectTrackForRender(p, "uncommitted").first.number)
        assertEquals(1, coordinator.selectTrackForRender(p, "uncommitted").first.number)
        assertEquals(1, coordinator.selectTrackForRender(p, "first-success").first.number)
        coordinator.commitAfterVerifiedRender(p.id, "first-success", 1)
        assertEquals(2, coordinator.selectTrackForRender(p, "second-success").first.number)
    }

    @Test
    fun shorterLibrariesCycleWithinOnlyTheirApprovedTracksAndMissingTrackFailsClearly() {
        val resources = tmp.newFolder("resources")
        val short = profile("short-profile", "short", listOf(2, 4))
        short.tracks.forEach { File(resources, it.relativePath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(it.number.toByte())) } }
        val coordinator = TrackRotationCoordinator(MemoryRotationStore(), resources)
        val first = coordinator.selectTrackForRender(short, "a").first.number
        coordinator.commitAfterVerifiedRender(short.id, "a", first)
        val second = coordinator.selectTrackForRender(short, "b").first.number
        coordinator.commitAfterVerifiedRender(short.id, "b", second)
        assertEquals(listOf(2, 4, 2, 4), (0..3).map { i ->
            val run = "c$i"
            coordinator.selectTrackForRender(short, run).also { coordinator.commitAfterVerifiedRender(short.id, run, it.first.number) }.first.number
        })

        val noBorrow = profile("isolated-profile", "other")
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.selectTrackForRender(noBorrow, "missing")
        }
    }
}
