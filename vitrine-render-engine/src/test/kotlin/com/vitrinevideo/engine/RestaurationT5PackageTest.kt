package com.vitrinevideo.engine

import com.vitrinevideo.engine.audio.RotationPersistence
import com.vitrinevideo.engine.audio.StrictMusicRotation
import com.vitrinevideo.engine.audio.TrackRotationCoordinator
import com.vitrinevideo.engine.capture.CaptureDurationPolicy
import com.vitrinevideo.engine.capture.SceneCaptureValidationSnapshot
import com.vitrinevideo.engine.capture.SceneCaptureValidator
import com.vitrinevideo.engine.interpreter.TextEntryAnim
import com.vitrinevideo.engine.interpreter.TransitionType
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.security.InternalMusicLibrary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Restauration T5 "Formule, combo et offre": source-faithful package and universal-engine proof. */
class RestaurationT5PackageTest {

    private fun musicRoot(): File = TestAssetLocator.projectFile("vitrine-video-app/src/main/assets/music")
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entries(): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(TestAssetLocator.packageZip("RESTAURATION-T5-1.0.0.zip"))).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        return out
    }

    private fun load() = TemplatePackageLoader.importZip(
        TestAssetLocator.packageZip("RESTAURATION-T5-1.0.0.zip"),
        kotlin.io.path.createTempDirectory("restauration_t5_pkg").toFile(),
        musicRoot()
    )

    @Test
    fun sourceArchiveInventoryIsSelectiveAndAnchored() {
        val inventory = TestAssetLocator.projectFile("docs/restauration_t5/SOURCE_INVENTORY.txt").readText()
        assertTrue(inventory.contains("SHA256 a4c5a11af20565c845bfb31baf8ae787003cd23b164fdf0f5205250c5f1dd08e"))
        assertTrue(inventory.contains("ENTRIES 176"))
        val kept = inventory.lineSequence().filter { it.startsWith("KEEP ") }.toList()
        val dropped = inventory.lineSequence().filter { it.startsWith("DROP ") }.toList()
        assertEquals(23, kept.size)
        assertEquals(153, dropped.size)
        assertTrue(kept.any { it.contains("T5_RUNTIME_CONTRACT_V3_0_0.json") })
        assertTrue(kept.any { it.contains("T5_MONTAGE_COMPLET_V3_0_0.json") })
        assertTrue(kept.any { it.contains("T5_TEXT_MOTION_V4_0_0.json") })
        assertTrue(kept.any { it.contains("PlayfairDisplay[wght].ttf") })
        assertTrue(kept.none {
            it.contains("/demo/") || it.contains("/tests/") || it.contains("cloudflare/") ||
                it.contains("/rushes/") || it.contains("ui-screens/") || it.contains(".mp3")
        })
        assertTrue(inventory.contains("VERIFY approvals/APPROBATION_CORRECTION_BASELINE_SOUS_TITRES_T5_2026-11-18.json"))
    }

    @Test
    fun universalEngineLoadsOfficialMontageRuntimeAndTextMotion() {
        val pkg = load()
        assertEquals("RESTAURATION-T5", pkg.templateId)
        assertEquals("1.0.0", pkg.version)
        assertEquals("Restauration", pkg.sector)
        assertEquals("Formule, combo et offre", pkg.title)
        val plan = pkg.renderPlan
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
        assertEquals(30, plan.fps)
        assertEquals(900, plan.totalFrames)
        assertEquals(30.0, plan.durationSec, 0.0001)
        assertEquals(listOf(0, 180, 420, 630, 900),
            plan.scenes.flatMap { listOf(it.startFrame, it.endFrameExcluded) }.distinct().sorted())
        assertEquals(listOf("LA FORMULE", "CE QU'IL Y A DANS LA FORMULE", "LE PRIX", "COMMANDER"),
            plan.scenes.map { it.role })
        assertEquals(3, plan.transitions.size)
        assertEquals(listOf(TransitionType.WIPE_DIRECTIONNEL, TransitionType.WIPE_DIRECTIONNEL, TransitionType.WIPE_DIRECTIONNEL),
            plan.transitions.map { it.type })
        assertEquals(listOf(176, 416, 626), plan.transitions.map { it.startFrame })
        assertEquals(listOf(8, 8, 8), plan.transitions.map { it.durationFrames })
        assertEquals(listOf("left", "right", "left"), plan.transitions.map { it.direction })
        assertTrue(plan.unknownCapabilities.isEmpty())
        assertEquals(4, pkg.captureGuides.size)
        assertEquals(listOf(6, 8, 7, 9), pkg.captureGuides.map { it.durationSec })

        val files = entries()
        val partition = JSONObject(String(files.getValue("templates/restauration/restauration-t5/montage/contrat-candidat.json")))
        val sources = partition.getJSONObject("source_templates")
        assertEquals("contracts/config/T5_RUNTIME_CONTRACT_V3_0_0.json", sources.getString("runtime"))
        assertEquals("contracts/montage/T5_MONTAGE_COMPLET_V3_0_0.json", sources.getString("montage"))
        assertEquals("contracts/config/T5_TEXT_MOTION_V4_0_0.json", sources.getString("text_motion"))
        val runtime = JSONObject(String(files.getValue("contracts/config/T5_RUNTIME_CONTRACT_V3_0_0.json")))
        assertEquals("montage/T5_MONTAGE_COMPLET_V3_0_0.json", runtime.getString("montage_contract"))
        assertEquals("config/T5_TEXT_MOTION_V4_0_0.json", runtime.getString("text_motion_contract"))
    }

    @Test
    fun sourcePositionsAreSceneRelativeAndReadinessUsesTheSameRule() {
        val pkg = load()
        val plan = pkg.renderPlan
        assertEquals(
            mapOf("S1" to 6_000_000L, "S2" to 8_000_000L, "S3" to 7_000_000L, "S4" to 9_000_000L),
            plan.scenes.associate { it.id to CaptureDurationPolicy.requiredDurationUs(it, plan.fps) }
        )
        for (scene in plan.scenes) {
            val length = scene.endFrameExcluded - scene.startFrame
            assertEquals(0, scene.shots.minOf { it.sourceInFrame }, scene.id)
            assertEquals(length, scene.shots.maxOf { it.sourceOutFrame }, scene.id)
            // The timeline offset (S2 starts at 180) must never become a source offset.
            assertFalse(scene.shots.any { it.sourceInFrame == scene.startFrame && scene.startFrame > 0 })
        }

        val file = kotlin.io.path.createTempFile("t5_s2_take", ".mp4").toFile().apply { writeText("camera take") }
        fun snapshot(durationUs: Long) = SceneCaptureValidationSnapshot(
            sceneId = "S2", filePath = file.absolutePath, fileExists = file.exists(),
            fileReadable = file.canRead(), fileNonEmpty = file.length() > 0L,
            durationUs = durationUs, width = 720, height = 1280, rotation = 0,
            hasVideoStream = true, audioPresent = false, sha256 = "a".repeat(64), fromRealCamera = true
        )
        assertNull(SceneCaptureValidator.validate(snapshot(8_150_000L), 8_000_000L)?.message)
        assertEquals("CAPTURE_DURATION_TOO_SHORT",
            SceneCaptureValidator.validate(snapshot(7_700_000L), 8_000_000L)?.code)
    }

    @Test
    fun playfairBlocksFadeAsWholeFieldsWithNoLetterAnimationOrStaticStack() {
        val pkg = load()
        val overlay = pkg.renderPlan.textOverlay!!
        val keys = overlay.windows.map { it.fieldKey }.toSet()
        assertEquals(setOf("nom", "offre", "contenu", "prix", "whatsapp", "quartier"), keys)
        assertEquals(10, overlay.windows.size)
        assertTrue(overlay.windows.all { it.entryAnim == TextEntryAnim.FONDU })
        assertTrue(overlay.windows.all { it.templateLines == null })
        assertTrue(overlay.windows.all { it.maxLines == null || it.maxLines!! <= 2 })
        assertTrue(overlay.windows.all { it.enterFrames == 3 })
        assertEquals("c40f2293766a503bc70cce9e512ef844a4ccb7cbcde792fe2ea31d191917d8d6",
            overlay.extraFontSha256s["serif"])
        assertTrue(overlay.extraFontPaths.values.all { it.endsWith("PlayfairDisplay[wght].ttf") })
        assertTrue(overlay.windows.all {
            it.overrideStyle?.lineSpacingPx?.div(it.overrideStyle.fontSizePx)?.let { ratio -> kotlin.math.abs(ratio - 1.2) < 0.0001 } ?: false
        })
        assertTrue(overlay.windows.all { (it.centerX ?: 0.0) in 50.0..670.0 && (it.customY ?: 0.0) in 780.0..1100.0 })
        // No more than two fields may be visible simultaneously.
        val simultaneous = (0 until 900).maxOf { frame ->
            overlay.windows.count { frame in it.startFrame until it.endFrameExcluded }
        }
        assertTrue("simultaneous fields=$simultaneous", simultaneous <= 2)
        assertTrue(overlay.windows.none { it.fieldKey == "whatsapp" && !it.skipAutoWhatsappPrefix })
        val motion = JSONObject(String(entries().getValue("contracts/config/T5_TEXT_MOTION_V4_0_0.json")))
        assertEquals("Playfair Display", motion.getJSONObject("font").getString("family"))
        assertEquals(false, motion.getJSONObject("animation").optBoolean("per_letter_animation", false))
        assertEquals(900, motion.getJSONObject("scenes").getJSONObject("S4").getInt("scene_frames"))
    }

    @Test
    fun sixMerchantFieldsAreTheOnlyBurnedInText() {
        val pkg = load()
        assertEquals(listOf("nom", "offre", "contenu", "prix", "whatsapp", "quartier"),
            pkg.formSpec.questions.map { it.key })
        assertEquals(6, pkg.formSpec.requiredKeys().size)
        assertEquals(12, pkg.jobSchemaConstraints!!.expectedScreenNumbers.size)
        assertEquals(listOf("S1", "S2", "S3", "S4"), pkg.jobSchemaConstraints!!.requiredSceneIds)
        assertFalse(pkg.jobSchemaConstraints!!.additionalResponsesAllowed)
        val values = mapOf(
            "nom" to "Cantine Kandi", "offre" to "Formule du jour", "contenu" to "1 plat, 1 boisson",
            "prix" to "5 000 FCFA", "whatsapp" to "+229 01 97 00 00 00", "quartier" to "Kandi marché"
        )
        assertEquals(values, com.vitrinevideo.engine.form.TemplateJobAssembler.validateResponses(pkg, values))
        assertThrows(com.vitrinevideo.engine.form.TemplateFormValidationException::class.java) {
            com.vitrinevideo.engine.form.TemplateJobAssembler.validateResponses(pkg, values + ("side" to "frites inventées"))
        }
        val runtime = JSONObject(String(entries().getValue("contracts/config/T5_RUNTIME_CONTRACT_V3_0_0.json")))
        val bindings = runtime.getJSONArray("merchant_bindings")
        assertEquals(6, bindings.length())
        for (i in 0 until bindings.length()) {
            assertTrue(bindings.getJSONObject(i).getString("source").startsWith("merchant_payload."))
        }
    }

    @Test
    fun fourApprovedInstrumentalsAreVaultedAndRotateStrictlyAfterSuccessOnly() {
        val pkg = load()
        val profile = pkg.audioProfile!!
        assertEquals("restauration_T5_afrobeat_music_v1_0_0", profile.id)
        assertEquals(listOf(1, 2, 3, 4), profile.approvedNumbers)
        assertEquals(listOf(
            "41ab3ce4be5a50d7ce451f7ada9b74dbe53d176a7822ad8d6d61573d9ecd1fd4",
            "c4b0aef58e38e6fc69bf5a558df83355365d805bfaaf291ed1bb0aea108f023a",
            "565aca293ea38c330e1dbc27cbb7524cddf339eaf5299af92755be9e63668949",
            "b458c78386617c0527250e850ec648999ea25b8ed6ddb43bb01ce8ccb70c8348"
        ), profile.tracks.map { it.sha256 })
        assertEquals(-3.0, profile.mix.bgGainDb, 0.0001)
        assertEquals(-10.0, profile.mix.underSpeechGainDb, 0.0001)
        assertEquals(120, profile.mix.attackMs)
        assertEquals(450, profile.mix.releaseMs)
        assertTrue(profile.mix.deliveryFormat.contains("48 kHz"))
        val manifest = InternalMusicLibrary.read(musicRoot(), "RESTAURATION-T5", "1.0.0")!!
        val resources = InternalMusicLibrary.materialize(
            manifest, musicRoot(), kotlin.io.path.createTempDirectory("t5_music").toFile()
        )
        for (track in profile.tracks) {
            assertEquals(track.sha256, sha(File(pkg.packageDir, resources.getValue(track.number).relativePath).readBytes()))
        }
        assertEquals(1, StrictMusicRotation.next(null, listOf(1, 2, 3, 4)))
        assertEquals(2, StrictMusicRotation.next(1, listOf(1, 2, 3, 4)))
        assertEquals(1, StrictMusicRotation.next(4, listOf(1, 2, 3, 4)))
        assertThrows(IllegalArgumentException::class.java) { StrictMusicRotation.assertSuccess(1, listOf(1, 2, 3, 4), 3) }

        val ledger = mutableMapOf<Pair<String, String>, Int>()
        val store = object : RotationPersistence {
            val history = mutableListOf<Int>()
            override fun findTrackForJob(profileId: String, runId: String): Int? = ledger[profileId to runId]
            override fun getLastTrack(profileId: String): Int? = history.lastOrNull()
            override fun recordSuccess(profileId: String, runId: String, trackNumber: Int) {
                ledger[profileId to runId] = trackNumber
                history += trackNumber
            }
        }
        val coordinator = TrackRotationCoordinator(store, pkg.packageDir)
        assertEquals(1, coordinator.selectTrackForRender(profile, "failed").first.number)
        // A failed or replayed callback never commits or advances the cycle.
        coordinator.commitAfterVerifiedRender(profile.id, "ok-1", 1, profile.approvedNumbers.sorted())
        coordinator.commitAfterVerifiedRender(profile.id, "ok-1", 1, profile.approvedNumbers.sorted())
        assertEquals(listOf(1), store.history)
        assertEquals(2, coordinator.selectTrackForRender(profile, "ok-2").first.number)
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.commitAfterVerifiedRender(profile.id, "skip-to-4", 4, profile.approvedNumbers.sorted())
        }
        assertEquals(listOf(1), store.history)
    }

    @Test
    fun fixedVoiceExemplarAndDevelopmentArtefactsAreNotShipped() {
        val pkg = load()
        val voices = JSONObject(pkg.resolvePackageRelative("audio/voix.json").readText())
        assertEquals(false, voices.getBoolean("fixed_exemplar_clips_used"))
        assertEquals("captured_scene_audio", voices.getJSONArray("clips").getJSONObject(0).getString("source"))
        assertEquals("voice-11", voices.getJSONObject("voice_approval").getString("voice_id"))
        assertEquals("B_masculine", voices.getJSONObject("voice_approval").getString("selected_option"))
        assertTrue(voices.getString("clips_policy").contains("No registered synthetic narration"))
        assertTrue(voices.getString("clips_policy").contains("No TTS/API"))
        val files = entries()
        assertTrue(files.keys.none { it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".m4a") || it.endsWith(".zip") })
        assertTrue(files.keys.none { it.contains("T4") || it.contains("temoignage") || it.contains("cloudflare") })
        assertTrue(files.keys.none { it.contains("T5_MONTAGE_COMPLET_V2") || it.contains("T5_TEXT_MOTION_V1") || it.contains("T5_TEXT_MOTION_V2") || it.contains("T5_TEXT_MOTION_V3") })
        val allText = files.values.joinToString("\n") { String(it, Charsets.UTF_8) }
        assertFalse(allText.contains("Burger Atelier Kandi"))
        assertFalse(allText.contains("Formule Double Burger"))
        assertTrue(files.keys.any { it.contains("config/T5_CAPTURE_FLOW_V1_0_0.json") })
        assertTrue(files.keys.any { it.contains("capture-guide/consignes_T5.json") })
        assertNotNull(JSONObject(String(files.getValue("contracts/config/T5_AUDIO_MIX_V1_0_0.json"))))
    }
}
