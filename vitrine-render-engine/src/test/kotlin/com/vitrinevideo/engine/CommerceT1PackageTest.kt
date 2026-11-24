package com.vitrinevideo.engine

import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.graphics.TextFieldResolver
import com.vitrinevideo.engine.interpreter.TextEntryAnim
import com.vitrinevideo.engine.interpreter.TransitionType
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.video.TransitionGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommerceT1PackageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun importsT1PackageWithContractRelativeAssetsAndConstraints() {
        val zip = File("/home/user/vitrine-render-engine/vitrine-video-app/src/main/assets/packages/COMMERCE-T1-2.0.0.zip")
        assertTrue("Runtime T1 ZIP missing", zip.isFile)
        val pkg = TemplatePackageLoader.importZip(zip, tmp.newFolder("registry"))

        assertEquals("COMMERCE-T1", pkg.templateId)
        assertEquals("2.0.0", pkg.version)
        assertEquals(900, pkg.renderPlan.totalFrames)
        assertEquals(listOf("S1", "S2", "S3", "S4"), pkg.renderPlan.scenes.map { it.id })
        assertEquals(listOf(210, 270, 233, 300), pkg.renderPlan.scenes.map { scene -> scene.shots.maxOf { it.sourceOutFrame } })
        assertEquals(listOf(6, 8, 7, 9), pkg.captureGuides.map { it.keptDurationSec })
        assertEquals(listOf(8, 10, 9, 11), pkg.captureGuides.map { it.durationSec })
        assertEquals(8, pkg.renderPlan.transitions.size)
        assertEquals(
            listOf(
                TransitionType.LAMELLES_OUVERTURE, TransitionType.ECLAT_COLORE,
                TransitionType.PAPIER_OBLIQUE, TransitionType.POUSSEE_VOILE,
                TransitionType.FENTE_HORIZONTALE, TransitionType.TRANCHE_PIP,
                TransitionType.BANDE_OBLIQUE_RECUL, TransitionType.LAMELLES_FERMETURE
            ), pkg.renderPlan.transitions.map { it.type }
        )
        assertTrue(TransitionGeometry.evaluate(pkg.renderPlan.transitions[1], 90)!!.progressQ in 0f..1f)
        val text = pkg.renderPlan.textOverlay!!
        assertEquals(8, text.windows.size)
        assertEquals(TextEntryAnim.PLUME, text.windows.first { it.fieldKey == "nom" }.entryAnim)
        val phoneWindow = text.windows.first { it.fieldKey == "whatsapp" }
        assertTrue(phoneWindow.skipAutoWhatsappPrefix)
        assertEquals("+22997000000", TextFieldResolver.resolveFieldText(phoneWindow, text, mapOf("whatsapp" to "+22997000000")))
        assertEquals(6, pkg.formSpec.questions.size)
        assertNotNull(pkg.audioProfile)
        assertEquals(4, pkg.audioProfile!!.tracks.size)
        assertTrue(pkg.resolvePackageRelative(pkg.renderPlan.textOverlay!!.fontRelativePath).isFile)
        assertEquals(55, pkg.jobSchemaConstraints!!.responseMaxLengths["accroche"])
        assertEquals("merchant_payload", pkg.jobSchemaConstraints!!.responsePayloadKey)
        assertTrue(pkg.jobSchemaConstraints!!.capturesAsObject)

        val ex = runCatching {
            TemplateJobAssembler.validateResponses(pkg, mapOf(
                "nom" to "Boutique", "produit" to "Jus", "prix" to "750 FCFA",
                "accroche" to "Très frais", "quartier" to "Kandi", "whatsapp" to "+229 97000000"
            ))
        }.exceptionOrNull()
        assertEquals(null, ex)
    }
}
