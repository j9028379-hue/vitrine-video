package com.vitrinevideo.app

import com.vitrinevideo.app.flow.MockupResponseMapper
import com.vitrinevideo.app.flow.TemplateCatalogue
import com.vitrinevideo.engine.form.TemplateJobAssembler
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R1 is a complete Restauration T1 package on the existing guided-capture/render path. */
class RestaurationT1AppFlowTest {
    private fun assets() = listOf(File("src/main/assets"), File("vitrine-video-app/src/main/assets"))
        .firstOrNull { it.isDirectory } ?: error("assets root not found")
    private fun load() = TemplatePackageLoader.importZip(
        File(assets(),"packages/RESTAURATION-T1-1.0.0.zip"), kotlin.io.path.createTempDirectory("r1_pkg").toFile(), File(assets(),"music")
    )

    @Test
    fun r1HasIndependentRuntimeIdentityAndExistingCommerceT1IsPreserved() {
        assertEquals("RESTAURATION-T1",TemplateCatalogue.RESTAURATION_T1)
        assertTrue(TemplateCatalogue.isSupported("RESTAURATION-T1"))
        assertEquals("Plat du jour",TemplateCatalogue.displayName("RESTAURATION-T1"))
        assertTrue(TemplateCatalogue.isSupported("COMMERCE-T1"))
        assertEquals("Présentation de mon commerce",TemplateCatalogue.displayName("COMMERCE-T1"))
        assertFalse(TemplateCatalogue.isSupported("RESTAURATION-T4"))
        assertEquals("packages/RESTAURATION-T1-1.0.0.zip",
            com.vitrinevideo.app.flow.TemplatePackageRepository.packageAssetPath(TemplateCatalogue.RESTAURATION_T1))
        assertEquals("packages/COMMERCE-T1-2.0.0.zip",
            com.vitrinevideo.app.flow.TemplatePackageRepository.packageAssetPath("COMMERCE-T1"))
        assertEquals(8,TemplateCatalogue.SUPPORTED_IDS.count { id ->
            val path=com.vitrinevideo.app.flow.TemplatePackageRepository.packageAssetPath(id) ?: return@count false
            File(assets(),path).isFile
        })
    }

    @Test
    fun maquetteR1MapsExactMerchantKeysToThePackageAndUniversalJobAssembler() {
        val answers=linkedMapOf("nom" to "Chez Afi","produit" to "Plat du jour","prix" to "2500",
            "whatsapp" to "+229 01 97 00 00 00","quartier" to "Kandi","accroche" to "")
        val mapped=MockupResponseMapper.parseFromWebJson(JSONObject(answers).toString(),TemplateCatalogue.RESTAURATION_T1)
        assertEquals(answers,mapped)
        val pkg=load()
        assertEquals(answers,TemplateJobAssembler.validateResponses(pkg,mapped))
        assertEquals(setOf("nom","produit","prix","whatsapp","quartier"),pkg.formSpec.requiredKeys())
        val bridge=File(assets(),"mockup/android-bridge.js").readText()
        assertTrue(bridge.contains("fmt === \"r1\" ? \"RESTAURATION-T1\""))
        assertTrue(bridge.contains("activeTemplateFormat === \"r1\""))
        assertTrue(bridge.contains("\"r1\", \"r2\", \"r3\", \"r4\"].includes(fmt)"))
    }
}
