package com.vitrinevideo.engine

import com.vitrinevideo.engine.pkg.TemplatePackageException
import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import com.vitrinevideo.engine.pkg.TemplateRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TemplatePackageLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun importsRealCommerceT3ZipAndVerifiesAllSha256AndCrossVersions() {
        val realZip = TestAssetLocator.packageZip("COMMERCE-T3-1.0.0.zip")
        assertTrue("COMMERCE-T3-1.0.0.zip doit exister pour validation réelle", realZip.exists())

        val storage = tmp.newFolder("registry")
        val registry = TemplateRegistry(storage)
        val pkg = registry.installZip(realZip)

        assertEquals("COMMERCE-T3", pkg.templateId)
        assertEquals("1.0.0", pkg.version)
        assertEquals("Commerce", pkg.sector)
        assertEquals(6, pkg.formSpec.questions.size)
        assertEquals(4, pkg.captureGuides.size)
        assertEquals(4, pkg.renderPlan.scenes.size)
        assertEquals(900, pkg.renderPlan.totalFrames)
        assertNotNull(pkg.audioProfile)
        assertEquals(4, pkg.audioProfile!!.tracks.size)
        assertTrue(pkg.resolvePackageRelative(pkg.renderPlan.textOverlay!!.fontRelativePath).exists())
        assertTrue(pkg.manifest.files.isNotEmpty())

        val installed = registry.listInstalled()
        assertEquals(1, installed.size)
        assertNotNull(registry.get("COMMERCE-T3", "1.0.0"))
        assertEquals(1, registry.findBySector("commerce").size)
    }

    @Test
    fun rejectsTamperedFileSha256() {
        val realZip = TestAssetLocator.packageZip("COMMERCE-T3-1.0.0.zip")
        assertTrue(realZip.exists())
        val storage = tmp.newFolder("tamper_reg")
        val pkg = TemplatePackageLoader.importZip(realZip, storage)

        // Alter catalogue/catalogue.json by 1 byte -> must fail SHA-256 verification
        val catFile = pkg.resolvePackageRelative("catalogue/catalogue.json")
        catFile.appendText(" ")

        val ex = assertThrows(TemplatePackageException::class.java) {
            TemplatePackageLoader.loadFromDirectory(pkg.packageDir)
        }
        assertEquals("MANIFEST_SIZE_MISMATCH", ex.code)
    }

    @Test
    fun rejectsZipSlipAttempt() {
        val maliciousZip = tmp.newFile("evil.zip")
        ZipOutputStream(FileOutputStream(maliciousZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../etc/evil.txt"))
            zos.write("pwn".toByteArray())
            zos.closeEntry()
        }
        val ex = assertThrows(TemplatePackageException::class.java) {
            TemplatePackageLoader.importZip(maliciousZip, tmp.newFolder("evil_reg"))
        }
        assertEquals("ZIP_SLIP", ex.code)
    }

    @Test
    fun registersArbitrarySecondSectorTemplateWithoutCodeChange() {
        // Synthetic RESTAURANT-R1 v1.0.0 package with zero Commerce/T3 constants
        val files = linkedMapOf<String, ByteArray>()
        files["catalogue/catalogue.json"] = JSONObject().apply {
            put("template_id", "RESTAURANT-R1")
            put("version", "1.0.0")
            put("titre", "Menu du Jour")
            put("secteur", "Restauration")
            put("contrat", "montage/partition.json")
            put("audio", "audio/profil.json")
            put("formulaire", "form/questions.json")
            put("cartes", "capture-guide/ecrans.json")
        }.toString(2).toByteArray()

        files["form/questions.json"] = JSONObject().apply {
            put("titre", "Infos Restaurant")
            put("questions", JSONArray().put(JSONObject().apply {
                put("cle", "plat")
                put("question", "Quel plat présentez-vous ?")
                put("obligatoire", true)
            }))
        }.toString(2).toByteArray()

        files["capture-guide/ecrans.json"] = JSONObject().apply {
            put("ecrans", JSONArray()
                .put(JSONObject().put("numero", 1).put("scene", 1).put("etat", "A").put("duree_s", 5).put("titre", "PLAT"))
                .put(JSONObject().put("numero", 2).put("scene", 1).put("etat", "B").put("duree_s", 5).put("consigne", "Filmez le plat"))
            )
        }.toString(2).toByteArray()

        files["montage/partition.json"] = JSONObject().apply {
            put("schema_version", "1.0")
            put("template_id", "RESTAURANT-R1")
            put("version", "1.0.0")
            put("dimensions", JSONArray().put(720).put(1280))
            put("fps", 30)
            put("duree_s", 5)
            put("images", 150)
            put("audio_profil_id", "resto_audio_v1")
            put("scenes", JSONArray().put(JSONObject().apply {
                put("id", "S1")
                put("debut", 0)
                put("fin_exclue", 150)
                put("cadrage", JSONObject().put("cx", 0.5).put("cy", 0.5).put("zoom", 1.0))
            }))
        }.toString(2).toByteArray()

        val trackBytes = "dummy_mp3_bytes_for_hash".toByteArray()
        files["audio/pistes/01-Loop.mp3"] = trackBytes
        files["audio/profil.json"] = JSONObject().apply {
            put("id", "resto_audio_v1")
            put("version", "1.0.0")
            put("pistes_approuvees", JSONArray().put(1))
            put("pistes", JSONArray().put(JSONObject().apply {
                put("numero", 1)
                put("titre", "Afro Kitchen")
                put("licence", "CC BY 4.0")
                put("credit", "Afro Kitchen — CC BY 4.0")
                put("fichier", "audio/pistes/01-Loop.mp3")
                put("extrait_sha256", sha256(trackBytes))
            }))
        }.toString(2).toByteArray()

        val manifestFiles = JSONArray()
        for ((path, content) in files) {
            manifestFiles.put(JSONObject().apply {
                put("chemin", path)
                put("sha256", sha256(content))
                put("bytes", content.size)
            })
        }
        files["MANIFEST.json"] = JSONObject().apply {
            put("schema_version", "1.0.0")
            put("package_id", "RESTAURANT-R1-1.0.0")
            put("version", "1.0.0")
            put("fichiers", manifestFiles)
        }.toString(2).toByteArray()

        val zipFile = tmp.newFile("RESTAURANT-R1-1.0.0.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for ((path, content) in files) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content)
                zos.closeEntry()
            }
        }

        val registry = TemplateRegistry(tmp.newFolder("multi_reg"))
        val pkg = registry.installZip(zipFile)
        assertEquals("RESTAURANT-R1", pkg.templateId)
        assertEquals("Restauration", pkg.sector)
        assertEquals(150, pkg.renderPlan.totalFrames)
        assertEquals(1, registry.findBySector("Restauration").size)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
