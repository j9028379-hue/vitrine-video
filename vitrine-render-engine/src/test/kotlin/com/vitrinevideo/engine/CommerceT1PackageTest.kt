package com.vitrinevideo.engine

import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.graphics.TextFieldResolver
import com.vitrinevideo.engine.interpreter.TextEntryAnim
import com.vitrinevideo.engine.interpreter.TransitionType
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.video.TransitionGeometry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommerceT1PackageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun t1AccrocheTextUsesReadableJsonTypographyAndFitsLongCopy() {
        val zip = TestAssetLocator.packageZip("COMMERCE-T1-2.0.0.zip")
        val pkg = TemplatePackageLoader.importZip(zip, tmp.newFolder("typography-contract"))
        val overlay = pkg.renderPlan.textOverlay!!
        val detail = overlay.windows.first { it.fieldKey == "accroche" }
        val candidate = JSONObject(pkg.resolvePackageRelative("templates/commerce/commerce-t1/montage/contrat-candidat.json").readText())
        val detailJson = candidate.getJSONArray("calques").let { array -> (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("id") == "detail" } }
        assertEquals("{accroche}", detailJson.getString("texte"))
        assertEquals("serif", detailJson.getString("police"))
        assertEquals(74, detailJson.getInt("taille_px"))
        val typography = candidate.getJSONObject("typographie")
        assertEquals(listOf(255, 245, 224), (0 until typography.getJSONArray("encre_rgb").length()).map { typography.getJSONArray("encre_rgb").getInt(it) })
        assertEquals(listOf(45, 28, 21), (0 until typography.getJSONArray("relief_rgb").length()).map { typography.getJSONArray("relief_rgb").getInt(it) })
        assertEquals("accroche", detail.fieldKey)
        assertNull(detail.templateLines)
        assertEquals(74.0, detail.overrideStyle!!.fontSizePx, 0.001)
        assertEquals(168.0, detail.maxHeightPx!!, 0.001)
        assertEquals(360.0, detail.centerX!!, 0.001)
        assertEquals(927.0, detail.customY!!, 0.001)
        assertTrue(detail.fontRelativePath!!.contains("DMSerifDisplay-Regular.ttf"))
        assertEquals(2, detail.maxLines)

        val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.52f }
        val sampleCopy = "Disponible dès maintenant"
        val oldContract = detail.copy(
            maxHeightPx = 132.0,
            overrideStyle = detail.overrideStyle!!.copy(fontSizePx = 68.0, lineSpacingPx = 73.44)
        )
        val oldLayout = TextFieldResolver.layoutWindow(oldContract, overlay, mapOf("accroche" to sampleCopy), measure)!!
        assertEquals("pre-fix default height cap reproduced the tiny text", 14f, oldLayout.fontSizePx, 0.001f)

        val short = TextFieldResolver.layoutWindow(detail, overlay, mapOf("accroche" to sampleCopy), measure)!!
        assertEquals("Disponible dès maintenant", TextFieldResolver.resolveFieldText(detail, overlay, mapOf("accroche" to "Disponible dès maintenant")))
        assertEquals(2, short.lines.size)
        assertEquals(74f, short.fontSizePx, 0.001f)
        assertTrue(short.lines.all { measure(it, short.fontSizePx) <= 606f })
        assertEquals(927f, short.baseY, 0.001f)

        val longerCopy = "Disponible dès maintenant — venez découvrir notre sélection de produits et profiter de nos conseils personnalisés à Kandi dès aujourd’hui."
        val long = TextFieldResolver.layoutWindow(detail, overlay, mapOf("accroche" to longerCopy), measure)!!
        assertEquals(longerCopy, TextFieldResolver.resolveFieldText(detail, overlay, mapOf("accroche" to longerCopy)))
        assertTrue("long dynamic text remains above the engine floor", long.fontSizePx > 14f)
        assertTrue("dynamic lines remain within the JSON width", long.lines.all { measure(it, long.fontSizePx) <= 606f })
        assertEquals(927f, long.baseY, 0.001f)

        // Unrelated commercial layers retain their existing typography and positions.
        val product = overlay.windows.first { it.fieldKey == "produit" }
        val offer = overlay.windows.first { it.fieldKey == "prix" }
        assertEquals(68.0, product.overrideStyle!!.fontSizePx, 0.001)
        assertEquals(78.0, offer.overrideStyle!!.fontSizePx, 0.001)
    }

    @Test
    fun importsT1PackageWithFreeTextContractAndContractRelativeAssets() {
        val zip = TestAssetLocator.packageZip("COMMERCE-T1-2.0.0.zip")
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
        assertTrue(pkg.jobSchemaConstraints!!.responseMaxLengths.isEmpty())
        assertTrue(pkg.jobSchemaConstraints!!.responseMinLengths.isEmpty())
        assertTrue(pkg.jobSchemaConstraints!!.responsePatterns.isEmpty())
        assertEquals("merchant_payload", pkg.jobSchemaConstraints!!.responsePayloadKey)
        assertTrue(pkg.jobSchemaConstraints!!.capturesAsObject)
        val requiredTextKeys = setOf("nom", "produit", "prix", "accroche", "quartier", "whatsapp")
        for (schemaFile in listOf(pkg.jobSchemaFile!!, pkg.resolvePackageRelative("templates/commerce/commerce-t1/form/schema-v2.json"))) {
            val root = JSONObject(schemaFile.readText())
            val payload = root.optJSONObject("properties")?.optJSONObject("merchant_payload") ?: root
            val required = payload.getJSONArray("required").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            assertEquals(requiredTextKeys, required)
            val properties = payload.getJSONObject("properties")
            for (key in requiredTextKeys) {
                val field = properties.getJSONObject(key)
                assertEquals("string", field.getString("type"))
                assertTrue("content schema for $key must not impose length or a pattern", !field.has("minLength") && !field.has("maxLength") && !field.has("pattern"))
            }
        }
        val candidateContract = JSONObject(pkg.resolvePackageRelative("templates/commerce/commerce-t1/montage/contrat-candidat.json").readText())
        assertTrue(candidateContract.getJSONObject("formulaire").getString("whatsapp").contains("exactement le texte saisi"))

        val ex = runCatching {
            TemplateJobAssembler.validateResponses(pkg, mapOf(
                "nom" to "Boutique", "produit" to "Jus", "prix" to "750 FCFA",
                "accroche" to "Très frais", "quartier" to "Kandi", "whatsapp" to "+229 97000000"
            ))
        }.exceptionOrNull()
        assertEquals(null, ex)
    }
}
