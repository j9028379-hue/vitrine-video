package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.RotationPersistence
import com.vitrinevideo.engine.audio.StrictMusicRotation
import com.vitrinevideo.engine.audio.TrackRotationCoordinator
import com.vitrinevideo.engine.interpreter.ApprovedTrack
import com.vitrinevideo.engine.interpreter.AudioMixConfig
import com.vitrinevideo.engine.interpreter.AudioProfileSpec
import com.vitrinevideo.engine.interpreter.MusicCatalogueParser
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.security.InternalMusicLibrary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Restauration T2 « Avant / Après »: official-archive adapter, engine and music proof. */
class RestaurationT2PackageTest {

    private fun musicRoot(): File = TestAssetLocator.projectFile("vitrine-video-app/src/main/assets/music")
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entries(zipName: String): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(TestAssetLocator.packageZip(zipName))).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        return out
    }

    private fun load() = TemplatePackageLoader.importZip(
        TestAssetLocator.packageZip("RESTAURATION-T2-1.0.0.zip"),
        kotlin.io.path.createTempDirectory("restauration_t2_pkg").toFile(),
        musicRoot()
    )

    @Test
    fun sourceArchiveInventoryIsSelectiveAndAnchored() {
        val inventory = TestAssetLocator.projectFile("docs/restauration_t2/SOURCE_INVENTORY.txt").readText()
        assertTrue(inventory.contains("SHA256 360c01d0bd91c76614abcc1df31ddf4373eae8e360f7b3b41a891628552e820e"))
        assertTrue(inventory.contains("ENTRIES 195"))
        val kept = inventory.lineSequence().filter { it.startsWith("KEEP ") }.toList()
        assertEquals(28, kept.size)
        assertTrue(kept.none { line ->
            line.contains(".m4a") || line.contains(".wav") || line.contains("ui-screens/") ||
                line.contains("/demo/") || line.contains("/tests/") || line.contains(".github/") ||
                line.contains("cloudflare/") || line.contains("/reports/") || line.contains("/rushes/")
        })
        assertTrue(kept.any { it.contains("PinyonScript-Regular.ttf") })
        assertTrue(kept.any { it.contains("PlayfairDisplay[wght].ttf") })
        assertTrue(kept.any { it.contains("DejaVuSans-Bold.ttf") })
    }

    @Test
    fun packageLoadsThroughUniversalEngineWithExactTimelineAndTwelveScreens() {
        val pkg = load()
        assertEquals("RESTAURATION-T2", pkg.templateId)
        assertEquals("1.0.0", pkg.version)
        assertEquals("Restauration", pkg.sector)
        assertFalse(pkg.templateId.startsWith("COMMERCE"))
        val plan = pkg.renderPlan
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
        assertEquals(30, plan.fps)
        assertEquals(900, plan.totalFrames)
        assertEquals(listOf(0, 180, 420, 630, 900),
            plan.scenes.flatMap { listOf(it.startFrame, it.endFrameExcluded) }.distinct().sorted())
        assertEquals(listOf("L'AVANT", "LA TRANSFORMATION", "LA RÉVÉLATION", "L'APRÈS"),
            plan.scenes.map { it.role })
        assertEquals(6, plan.transitions.size)
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), plan.transitions.map { it.motifId })
        assertEquals(
            listOf(0, 13, 174, 186, 294, 306, 414, 426, 519, 531, 624, 636),
            plan.transitions.flatMap { listOf(it.startFrame, it.startFrame + it.durationFrames) }
        )
        assertTrue(plan.unknownCapabilities.isEmpty())
        assertEquals(4, pkg.captureGuides.size)
        assertEquals(listOf(6, 8, 7, 9), pkg.captureGuides.map { it.durationSec })
        assertEquals(listOf(1, 4, 7, 10), pkg.captureGuides.map { it.guideScreenNumber })
        assertEquals(6, pkg.formSpec.questions.size)
        assertEquals(listOf("nom", "produit", "prix", "whatsapp", "quartier", "accroche"),
            pkg.formSpec.questions.map { it.key })
        assertEquals(setOf("nom", "produit", "prix", "whatsapp", "quartier"),
            pkg.formSpec.requiredKeys())
        assertEquals(12, pkg.jobSchemaConstraints!!.expectedScreenNumbers.size)
        assertEquals(listOf("S1", "S2", "S3", "S4"), pkg.jobSchemaConstraints!!.requiredSceneIds)
    }

    @Test
    fun fourDeclaredFontsAreActuallyBoundToTheDynamicBlocks() {
        val pkg = load()
        val overlay = pkg.renderPlan.textOverlay!!
        assertEquals(
            setOf("signature", "serif", "utility", "utility_bold", "fallback"),
            overlay.extraFontPaths.keys
        )
        val expected = mapOf(
            "signature" to "4aab130a6ed27f8b8117738c84a5602edf9300cdcc0651a9a65bf96f451ac29a",
            "serif" to "c40f2293766a503bc70cce9e512ef844a4ccb7cbcde792fe2ea31d191917d8d6",
            "utility" to "57f73e11f51999432bf7ab22ce55b6f945d5eca1bf824404cfa9ec2e3718c84e",
            "utility_bold" to "a4c5bc453ca281d90ea079e596da7ae0dfeb5777497c29ec254e76d97ff6f890"
        )
        for ((role, hash) in expected) assertEquals(hash, overlay.extraFontSha256s[role])
        val byField = overlay.windows.associateBy { it.fieldKey }
        assertEquals(expected.getValue("signature"), byField.getValue("nom").fontSha256)
        assertEquals(expected.getValue("utility_bold"), byField.getValue("prix").fontSha256)
        assertEquals(expected.getValue("utility_bold"), byField.getValue("whatsapp").fontSha256)
        assertEquals(expected.getValue("utility"), byField.getValue("quartier").fontSha256)
        assertEquals(expected.getValue("serif"), byField.getValue("accroche").fontSha256)
        assertTrue(byField.getValue("whatsapp").skipAutoWhatsappPrefix)
        assertEquals(
            setOf("nom", "produit", "accroche", "prix", "whatsapp", "quartier"),
            byField.keys
        )
        // G03: empty accroche has no invented literal; nom is a separate bound window.
        assertTrue(overlay.windows.none {
            it.fieldKey == "accroche" && (it.templateLines?.any { line -> line.isNotBlank() } ?: false)
        })
        assertTrue(overlay.windows.any { it.fieldKey == "nom" && it.startFrame == 450 && it.endFrameExcluded == 606 })
    }

    @Test
    fun approvedFourTrackVaultMatchesDeclaredHashesAndRotationIsStrict() {
        val pkg = load()
        val profile = pkg.audioProfile!!
        assertEquals("afrobeat_restaurants_maquis", profile.id)
        assertEquals(listOf(1, 2, 3, 4), profile.approvedNumbers)
        assertTrue(profile.voiceApproved)
        assertTrue(profile.mixApproved)
        assertEquals(-3.0, profile.mix.bgGainDb, 0.0001)
        assertEquals(-10.0, profile.mix.underSpeechGainDb, 0.0001)
        assertEquals(120, profile.mix.attackMs)
        assertEquals(450, profile.mix.releaseMs)
        assertEquals(-16.0, profile.mix.targetLufs, 0.0001)
        assertEquals(-2.0, profile.mix.truePeakMaxDbtp!!, 0.0001)
        assertTrue(profile.mix.deliveryFormat.contains("48 kHz"))

        val catalogue = JSONObject(String(entries("RESTAURATION-T2-1.0.0.zip").getValue("audio/catalogue.json")))
        val manifest = InternalMusicLibrary.read(musicRoot(), "RESTAURATION-T2", "1.0.0")!!
        val resources = InternalMusicLibrary.materialize(
            manifest, musicRoot(), kotlin.io.path.createTempDirectory("resto_music").toFile()
        )
        val parsed = MusicCatalogueParser.parse(catalogue, JSONObject(String(entries("RESTAURATION-T2-1.0.0.zip").getValue("audio/voix.json"))), resources)
        assertEquals(listOf(300340, 293956, 300333, 328624),
            catalogue.getJSONArray("pistes").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getInt("pixabay_id") } })
        val declared = listOf(
            "d6f9c2e1c03bcda34e758958546c768114b3d2da84a1ac14e1bde3c26d1d940b",
            "820ba48c5ebe01de9e67da26e521112eedf558f67b506b7a85737cd112625e50",
            "1f78562b2bd6b169824afcddefc1764717f26221187574491c823e5f721c92b8",
            "96d43530088ce467547c4bda2993f3c58292feedcf62fcfc6d8666ce9bfee6e7"
        )
        assertEquals(declared, parsed.tracks.map { it.sha256 })
        for (track in parsed.tracks) {
            val res = resources.getValue(track.number)
            val extracted = File(pkg.packageDir, res.relativePath)
            assertEquals(track.sha256, sha(extracted.readBytes()))
        }

        assertEquals(1, StrictMusicRotation.next(null, listOf(1, 2, 3, 4)))
        assertEquals(2, StrictMusicRotation.next(1, listOf(1, 2, 3, 4)))
        assertEquals(1, StrictMusicRotation.next(4, listOf(1, 2, 3, 4)))
        assertThrows(IllegalArgumentException::class.java) {
            StrictMusicRotation.assertSuccess(1, listOf(1, 2, 3, 4), 3)
        }

        val store = object : RotationPersistence {
            val history = mutableListOf<Int>()
            override fun findTrackForJob(profileId: String, runId: String): Int? = null
            override fun getLastTrack(profileId: String): Int? = history.lastOrNull()
            override fun recordSuccess(profileId: String, runId: String, trackNumber: Int) { history += trackNumber }
        }
        val coords = TrackRotationCoordinator(store, pkg.packageDir)
        // A failed render never commits: the next selection stays on the same track.
        assertEquals(1, coords.selectTrackForRender(profile, "failed").first.number)
        coords.commitAfterVerifiedRender(profile.id, "ok-A", 1, listOf(1, 2, 3, 4))
        assertEquals(2, coords.selectTrackForRender(profile, "ok-B").first.number)
        coords.commitAfterVerifiedRender(profile.id, "ok-B", 2, listOf(1, 2, 3, 4))
        assertEquals(listOf(1, 2), store.history)
        assertThrows(IllegalArgumentException::class.java) {
            coords.commitAfterVerifiedRender(profile.id, "skip-to-D", 4, listOf(1, 2, 3, 4))
        }
        assertEquals(listOf(1, 2), store.history)
    }

    @Test
    fun productionPackageContainsNoExcludedDevelopmentMediaOrT4() {
        val files = entries("RESTAURATION-T2-1.0.0.zip")
        val manifest = JSONObject(String(files.getValue("MANIFEST.json")))
        assertTrue(manifest.getBoolean("production_only"))
        assertEquals("360c01d0bd91c76614abcc1df31ddf4373eae8e360f7b3b41a891628552e820e",
            manifest.getString("source_sha256"))
        assertEquals(files.size - 1, manifest.getJSONArray("files").length())
        assertTrue(files.keys.none { it.endsWith(".m4a") || it.endsWith(".wav") || it.endsWith(".mp3") || it.endsWith(".mp4") })
        assertTrue(files.keys.none { it.contains("temoignage") || it.contains("T4") })
        for (i in 0 until manifest.getJSONArray("files").length()) {
            val e = manifest.getJSONArray("files").getJSONObject(i)
            assertEquals(e.getString("sha256"), sha(files.getValue(e.getString("path"))))
        }
        val flow = JSONObject(String(files.getValue("contracts/config/T2_capture_flow_v1_0_0.json")))
        assertEquals(12, flow.getJSONObject("counts").getInt("stable_capture_screens"))
        assertEquals("COUNTDOWN_3_2_1", flow.getJSONArray("transient_states").getJSONObject(0).getString("id"))
    }
}
