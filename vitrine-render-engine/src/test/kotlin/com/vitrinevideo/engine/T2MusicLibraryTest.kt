package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.AudioDuckingEngine
import com.vitrinevideo.engine.interpreter.MusicCatalogueParser
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.security.InternalMusicLibrary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Verifies T2's independent music library, SHA chain and exact mix parameters. */
class T2MusicLibraryTest {

    private fun musicRoot(): File = TestAssetLocator.projectFile("vitrine-video-app/src/main/assets/music")

    @Test
    fun catalogueHashesStartPointsAndOrderArePreservedThroughInternalVault() {
        val zipBytes = FileInputStream(TestAssetLocator.packageZip("COMMERCE-T2-1.0.0.zip")).use { it.readBytes() }
        var catalogueRaw: String? = null
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == "audio/catalogue.json") {
                    catalogueRaw = zis.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
        }
        val catalogue = JSONObject(catalogueRaw ?: error("audio/catalogue.json missing"))
        val manifest = InternalMusicLibrary.read(musicRoot(), "COMMERCE-T2", "1.0.0")!!
        val outDir = kotlin.io.path.createTempDirectory("t2_music").toFile()
        val resources = InternalMusicLibrary.materialize(manifest, musicRoot(), outDir)
        val profile = MusicCatalogueParser.parse(catalogue, JSONObject(readVoices()), resources)

        assertEquals("afrobeat_arrivage_commerce", profile.id)
        assertEquals("1.0.0", profile.version)
        assertEquals(listOf(1, 2, 3, 4), profile.tracks.map { it.number })
        assertEquals(listOf("01.mp3", "02.mp3", "03.mp3", "04.mp3"),
            profile.tracks.map { File(it.relativePath).name })
        assertEquals(listOf(162190, 303023, 260353, 300338),
            catalogue.getJSONArray("pistes").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getInt("pixabay_id") }
            })
        assertEquals(listOf(8.0, 12.0, 9.0, 16.0), manifest.entries.map { it.startSec })
        for (track in profile.tracks) {
            val declared = manifest.entries.first { it.number == track.number }
            assertEquals(declared.sha256, track.sha256)
            assertEquals(declared.sha256, sha256(File(outDir, resources.getValue(track.number).relativePath)))
        }
    }

    @Test
    fun declaredT2MixUsesLinearDbDuckingAndPreservesAllTechnicalGates() {
        val mixJson = JSONObject(readVoices()).getJSONObject("mix")
        assertEquals(-3.0, mixJson.getDouble("music_open_gain_db"), 0.0001)
        assertEquals(-10.0, mixJson.getDouble("music_under_voice_gain_db"), 0.0001)
        assertEquals(-7.0, mixJson.getDouble("duck_depth_db"), 0.0001)
        assertEquals(120, mixJson.getInt("duck_attack_ms"))
        assertEquals(450, mixJson.getInt("duck_release_ms"))
        assertEquals("linear_db", mixJson.getString("duck_attack_curve"))
        assertEquals("linear_db", mixJson.getString("duck_release_curve"))
        assertEquals(0.0, mixJson.getDouble("voice_gain_db"), 0.0001)
        assertEquals(-16.0, mixJson.getDouble("master_target_lufs"), 0.0001)
        assertEquals(-2.0, mixJson.getDouble("master_true_peak_dbtp_max"), 0.0001)
        assertEquals("stereo 48 kHz", mixJson.getString("delivery_audio"))
        val mix = com.vitrinevideo.engine.interpreter.AudioMixConfig(
            profileId = "afrobeat_arrivage_commerce",
            includeCaptureVoice = true,
            targetLufs = mixJson.getDouble("master_target_lufs"),
            bgGainDb = mixJson.getDouble("music_open_gain_db"),
            underSpeechGainDb = mixJson.getDouble("music_under_voice_gain_db"),
            attackMs = mixJson.getInt("duck_attack_ms"),
            releaseMs = mixJson.getInt("duck_release_ms"),
            attackCurve = mixJson.getString("duck_attack_curve"),
            releaseCurve = mixJson.getString("duck_release_curve"),
            voiceGainDb = mixJson.getDouble("voice_gain_db"),
            peakLimitDb = mixJson.getDouble("master_true_peak_dbtp_max"),
            duckDepthDb = mixJson.getDouble("duck_depth_db")
        )
        val engine = AudioDuckingEngine(48_000, 2, mix)
        assertEquals(-10.0, engine.effectiveDuckGainDb, 0.0001)
    }

    @Test
    fun noPlainMp3IsPublishedInAppAssets() {
        val assetRoot = TestAssetLocator.projectFile("vitrine-video-app/src/main/assets/music")
        val plain = assetRoot.walkTopDown().filter { it.isFile && it.extension.equals("mp3", true) }.toList()
        assertTrue("Raw MP3 must never be committed: $plain", plain.isEmpty())
        val libraryDir = File(assetRoot, "COMMERCE-T2/1.0.0")
        assertTrue(File(libraryDir, "library.json").isFile)
        assertEquals(4, libraryDir.listFiles()!!.count { it.name.endsWith(".vault") })
    }

    private fun readVoices(): String = readEntry("audio/voix.json")

    private fun readEntry(name: String): String {
        val bytes = FileInputStream(TestAssetLocator.packageZip("COMMERCE-T2-1.0.0.zip")).use { it.readBytes() }
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == name) return zis.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("$name missing")
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
