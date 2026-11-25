package com.vitrinevideo.engine

import com.vitrinevideo.engine.capabilities.CapabilityId
import com.vitrinevideo.engine.interpreter.EffectKind
import com.vitrinevideo.engine.interpreter.EffectKeyframe
import com.vitrinevideo.engine.interpreter.EvidenceMontageContractParser
import com.vitrinevideo.engine.interpreter.RenderScriptTextLayoutParser
import com.vitrinevideo.engine.interpreter.UniversalJsonInterpreter
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/** End-to-end declarative integration of COMMERCE-T2 1.0.0 through the universal loader. */
class T2PackageIntegrationTest {

    private fun musicRoot(): File = TestAssetLocator.projectFile("vitrine-video-app/src/main/assets/music")

    @Test
    fun declaredT2PackageLoadsThroughUniversalLoaderWithoutEditingReferenceJson() {
        val pkg = TemplatePackageLoader.importZip(
            TestAssetLocator.packageZip("COMMERCE-T2-1.0.0.zip"),
            kotlin.io.path.createTempDirectory("t2_pkg").toFile(),
            musicRoot()
        )
        assertEquals("COMMERCE-T2", pkg.templateId)
        assertEquals("1.0.0", pkg.version)
        assertEquals("Commerce", pkg.sector)
        assertEquals(4, pkg.renderPlan.scenes.size)
        assertEquals(900, pkg.renderPlan.totalFrames)
        assertEquals(30, pkg.renderPlan.fps)
        assertEquals(720, pkg.renderPlan.width)
        assertEquals(1280, pkg.renderPlan.height)
        assertEquals(listOf(0, 180, 420, 630, 900),
            pkg.renderPlan.scenes.flatMap { listOf(it.startFrame, it.endFrameExcluded) }
                .distinct().sorted())
        assertEquals(12, pkg.captureGuides.size)
        assertEquals(6, pkg.formSpec.questions.size)
        assertEquals("afrobeat_arrivage_commerce", pkg.renderPlan.audioProfileId)
        assertEquals(pkg.renderPlan.audioProfileId, pkg.audioProfile?.id)
        assertEquals(4, pkg.audioProfile?.tracks?.size)
        assertTrue(pkg.audioProfile!!.tracks.all { it.relativePath.startsWith("audio/pistes/0") })
        assertTrue(pkg.audioProfile.tracks.all { it.relativePath.endsWith(".mp3") })
        assertTrue(pkg.renderPlan.requiredCapabilities.contains(CapabilityId.VIDEO_VIEWPORT_TRAJECTORY))
        assertTrue(pkg.renderPlan.unknownCapabilities.isEmpty())
    }

    @Test
    fun allThirteenDeclaredMontageEffectsSurviveParsingWithExactWindows() {
        val raw = readEntry("COMMERCE-T2-1.0.0.zip", "reverse-engineering/effets.json")
        val script = readEntry("COMMERCE-T2-1.0.0.zip", "render/film.py")
        val plan = UniversalJsonInterpreter.parsePartitionJson(
            raw, mapOf("render/film.py" to script)
        )
        val declared = JSONObject(raw).getJSONArray("effets")
        val expected = (0 until declared.length()).map { i ->
            val o = declared.getJSONObject(i)
            o.getString("id") to o.getJSONArray("images_t2").let { it.getInt(0) to it.getInt(1) }
        }.toMap()
        val actual = plan.visualEffects.filter { it.operation != "ombre_portee_texte" }
            .associate { it.id to (it.startFrame to it.endFrameExcluded) }
        assertEquals(expected, actual)
        assertEquals(13, actual.size)

        val byId = plan.visualEffects.associateBy { it.id }
        assertEquals(EffectKind.GLOW, byId.getValue("reflet").kind)
        assertEquals(listOf(EffectKeyframe(311, 0.0), EffectKeyframe(322, 0.53),
            EffectKeyframe(345, 0.41), EffectKeyframe(364, 0.0)),
            byId.getValue("reflet").opacityKeyframes)
        assertEquals(6.0, byId.getValue("recul").sigmaMaxPx, 0.0001)
        assertEquals(1.06 to 1.0, byId.getValue("recul").zoomCopy)
        assertEquals(-0.1 to 0.5, byId.getValue("reflet").centerStart)
        assertEquals(1.1 to 0.47, byId.getValue("reflet").centerEnd)
        assertEquals(EffectKind.FREEZE_CROSSFADE, byId.getValue("double").kind)
        assertEquals(1.04 to 1.0, byId.getValue("double").zoomCopy)
        assertEquals(EffectKind.FINAL_FADE, byId.getValue("noir").kind)
        assertEquals(listOf(EffectKeyframe(879, 0.0), EffectKeyframe(899, 1.0)),
            byId.getValue("noir").opacityKeyframes)
        assertTrue(plan.transitions.any {
            it.startFrame == 420 && it.durationFrames == 24 && it.motifId == "double"
        })
        assertEquals(5, plan.visualEffects.count { it.kind == EffectKind.SCRIM })
    }

    @Test
    fun textWindowsAreDeclarativeFieldBindingsNotDemoCopy() {
        val raw = readEntry("COMMERCE-T2-1.0.0.zip", "reverse-engineering/effets.json")
        val script = readEntry("COMMERCE-T2-1.0.0.zip", "render/film.py")
        val layout = RenderScriptTextLayoutParser.parseTextOverlay(
            script,
            EvidenceMontageContractParser.parse(JSONObject(raw), script).scenes,
            720,
            1280
        )!!
        val fieldKeys = layout.windows.map { it.fieldKey }.toSet()
        assertEquals(
            setOf("nom", "produit", "accroche", "prix", "quartier", "whatsapp",
                "__label_prix", "__label_commander_sur_whatsapp"),
            fieldKeys
        )
        assertFalse(layout.windows.any { it.fieldKey == "Sillon Studio" || it.fieldKey == "48 000 F CFA" })
        assertTrue(layout.windows.any { it.startFrame == 8 && it.endFrameExcluded == 177 && it.leftXPx == 46.0 })
        assertTrue(layout.windows.any { it.startFrame == 294 && it.fieldKey == "prix" && it.fontFamilyOverride == "serif" })
        assertTrue(layout.windows.any { it.fieldKey == "whatsapp" && it.skipAutoWhatsappPrefix })
        assertEquals(8, layout.fadeFrames)
    }

    @Test
    fun framingTrajectoryAndZoomClampComeFromContract() {
        val raw = readEntry("COMMERCE-T2-1.0.0.zip", "reverse-engineering/effets.json")
        val plan = UniversalJsonInterpreter.parsePartitionJson(raw)
        val s1 = plan.scenes[0].shots.single()
        assertEquals(listOf(0.0, 0.6, 1.0), s1.viewportKeyframes.map { it.progress })
        assertEquals(listOf(0.34, 0.48, 0.57), s1.viewportKeyframes.map { it.cx })
        assertEquals(1.06, s1.maxZoom, 0.0001)
        assertEquals(listOf(0, 180), listOf(s1.sourceInFrame, s1.sourceOutFrame))
    }

    private fun readEntry(zipName: String, entryName: String): String =
        ZipInputStream(FileInputStream(TestAssetLocator.packageZip(zipName))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == entryName) return zis.readBytes().toString(Charsets.UTF_8)
            }
            error("$entryName missing from $zipName")
        }
}
