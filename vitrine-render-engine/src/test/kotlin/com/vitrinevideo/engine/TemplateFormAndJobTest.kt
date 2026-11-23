package com.vitrinevideo.engine

import com.vitrinevideo.engine.capture.SceneCaptureDescriptor
import com.vitrinevideo.engine.capture.SceneRecordingStatus
import com.vitrinevideo.engine.form.TemplateFormValidationException
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.timeline.PreFlightValidator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplateFormAndJobTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixtureCaptureFiles(): List<File> {
        val dir = File("/home/user/vitrine-engine-test-harness/app/src/main/assets/t3_fixture/captures")
        return listOf("S1.mp4", "S2.mp4", "S3.mp4", "S4.mp4").map { File(dir, it) }
    }

    @Test
    fun assemblesValidRenderJobFromRealPackageAndPreservesMerchantResponses() {
        val realZip = File("/home/user/google_drive/COMMERCE-T3-1.0.0.zip")
        assertTrue(realZip.exists())
        val pkg = TemplatePackageLoader.importZip(realZip, tmp.newFolder("reg"))

        val capFiles = fixtureCaptureFiles()
        assertTrue(capFiles.all { it.exists() })

        val responses = mapOf(
            "nom" to "Atelier Kandi",
            "produit" to "Sac en cuir tressé",
            "prix" to "", // optional field explicitly omitted by merchant
            "demonstration" to "Ouverture facile et bandoulière réglable.",
            "quartier" to "Kandi, Alibori",
            "whatsapp" to "+229 01 97 00 00"
        )
        val descriptors = pkg.renderPlan.scenes.mapIndexed { idx, sc ->
            val f = capFiles[idx]
            SceneCaptureDescriptor(
                sessionId = "sess_01",
                templateId = pkg.templateId,
                templateVersion = pkg.version,
                sceneId = sc.id,
                localVideoPath = f.absolutePath,
                durationUs = ((sc.endFrameExcluded - sc.startFrame).toLong() * 1_000_000L) / pkg.renderPlan.fps,
                width = 720,
                height = 1280,
                rotation = 0,
                audioPresent = true,
                sha256 = PreFlightValidator.sha256File(f),
                recordingStatus = SceneRecordingStatus.COMPLETED,
                validatedByMerchant = true
            )
        }

        val assembled = TemplateJobAssembler.assemble(
            pkg = pkg,
            sessionId = "sess_01",
            rawResponses = responses,
            captures = descriptors,
            outputDir = tmp.newFolder("out"),
            rotationDbFile = tmp.newFile("rot.db")
        )

        assertEquals("COMMERCE-T3", assembled.jobJson.getString("template_id"))
        assertEquals("1.0.0", assembled.jobJson.getString("template_version"))
        assertEquals("", assembled.jobJson.getJSONObject("reponses").getString("prix"))
        assertEquals("Atelier Kandi", assembled.jobJson.getJSONObject("reponses").getString("nom"))
        assertEquals(4, assembled.jobJson.getJSONArray("captures").length())
        assertEquals(12, assembled.jobJson.getJSONArray("ecrans").length())
    }

    @Test
    fun refusesMissingRequiredResponseAndUnknownKeysWithoutInventingText() {
        val realZip = File("/home/user/google_drive/COMMERCE-T3-1.0.0.zip")
        val pkg = TemplatePackageLoader.importZip(realZip, tmp.newFolder("reg2"))
        val capFiles = fixtureCaptureFiles()
        val descriptors = pkg.renderPlan.scenes.mapIndexed { idx, sc ->
            val f = capFiles[idx]
            SceneCaptureDescriptor(
                sessionId = "sess_02",
                templateId = pkg.templateId,
                templateVersion = pkg.version,
                sceneId = sc.id,
                localVideoPath = f.absolutePath,
                durationUs = 10_000_000L,
                width = 720,
                height = 1280,
                rotation = 0,
                audioPresent = true,
                sha256 = PreFlightValidator.sha256File(f)
            )
        }

        // Missing 'produit' must fail cleanly
        val missingEx = assertThrows(TemplateFormValidationException::class.java) {
            TemplateJobAssembler.assemble(
                pkg = pkg,
                sessionId = "sess_02",
                rawResponses = mapOf(
                    "nom" to "Atelier Kandi",
                    "demonstration" to "Solidité du cuir.",
                    "quartier" to "Kandi",
                    "whatsapp" to "+229 00"
                ),
                captures = descriptors,
                outputDir = tmp.newFolder("out2"),
                rotationDbFile = tmp.newFile("rot2.db")
            )
        }
        assertEquals("MISSING_REQUIRED_RESPONSE", missingEx.code)

        // Unknown extra key must fail when schema sets additionalProperties=false
        val extraEx = assertThrows(TemplateFormValidationException::class.java) {
            TemplateJobAssembler.assemble(
                pkg = pkg,
                sessionId = "sess_02",
                rawResponses = mapOf(
                    "nom" to "Atelier Kandi",
                    "produit" to "Sac",
                    "demonstration" to "Solidité du cuir.",
                    "quartier" to "Kandi",
                    "whatsapp" to "+229 00",
                    "champ_invente" to "Interdit"
                ),
                captures = descriptors,
                outputDir = tmp.newFolder("out3"),
                rotationDbFile = tmp.newFile("rot3.db")
            )
        }
        assertEquals("UNKNOWN_RESPONSE_KEY", extraEx.code)
    }

    @Test
    fun refusesDuplicateCaptureFileAcrossScenes() {
        val realZip = File("/home/user/google_drive/COMMERCE-T3-1.0.0.zip")
        val pkg = TemplatePackageLoader.importZip(realZip, tmp.newFolder("reg3"))
        val s1File = fixtureCaptureFiles().first()
        val s1Sha = PreFlightValidator.sha256File(s1File)

        val duplicateDescriptors = pkg.renderPlan.scenes.map { sc ->
            SceneCaptureDescriptor(
                sessionId = "sess_dup",
                templateId = pkg.templateId,
                templateVersion = pkg.version,
                sceneId = sc.id,
                localVideoPath = s1File.absolutePath,
                durationUs = 10_000_000L,
                width = 720,
                height = 1280,
                rotation = 0,
                audioPresent = true,
                sha256 = s1Sha
            )
        }

        val ex = assertThrows(TemplateFormValidationException::class.java) {
            TemplateJobAssembler.assemble(
                pkg = pkg,
                sessionId = "sess_dup",
                rawResponses = mapOf(
                    "nom" to "Atelier Kandi",
                    "produit" to "Sac",
                    "demonstration" to "Solidité du cuir.",
                    "quartier" to "Kandi",
                    "whatsapp" to "+229 00"
                ),
                captures = duplicateDescriptors,
                outputDir = tmp.newFolder("out4"),
                rotationDbFile = tmp.newFile("rot4.db")
            )
        }
        assertEquals("DUPLICATE_CAPTURE_FILE", ex.code)
    }
}
