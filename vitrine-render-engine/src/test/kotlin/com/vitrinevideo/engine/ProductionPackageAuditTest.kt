package com.vitrinevideo.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Physical audit of the packages that are actually shipped inside the APK. */
class ProductionPackageAuditTest {

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readProductionPackage(name: String): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(TestAssetLocator.packageZip(name))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    @Test
    fun allFourProductionPackagesAreSelfConsistentAndFreeOfDevelopmentMedia() {
        val names = listOf(
            "COMMERCE-T1-2.0.0.zip",
            "COMMERCE-T2-1.0.0.zip",
            "COMMERCE-T3-1.0.0.zip",
            "COMMERCE-T4-1.0.0.zip"
        )
        for (name in names) {
            val entries = readProductionPackage(name)
            val manifest = JSONObject(String(entries.getValue("MANIFEST.json")))
            assertTrue("$name must be a production-only bundle", manifest.optBoolean("production_only"))
            val files = manifest.getJSONArray("files")
            assertEquals("$name manifest must cover every shipped file", entries.size - 1, files.length())
            for (i in 0 until files.length()) {
                val e = files.getJSONObject(i)
                val data = entries.getValue(e.getString("path"))
                assertEquals(e.getInt("bytes"), data.size)
                assertEquals(e.getString("sha256"), sha(data))
            }
            val templateId = manifest.getString("template_id")
            if (templateId != "COMMERCE-T1") {
                val forbidden = entries.keys.filter { it.endsWith(".mp4") || it.endsWith(".wav") || it.endsWith(".m4a") }
                assertTrue("$name still ships development media: $forbidden", forbidden.isEmpty())
            }
            val catalogue = JSONObject(String(entries.getValue("catalogue/catalogue.json")))
            val entry = catalogue.getJSONArray("entries").getJSONObject(0)
            val contract = entry.optString("contrat").ifBlank { entry.optString("partition") }
            assertTrue("$name missing render contract $contract", contract.isNotBlank() && entries.containsKey(contract))
        }
    }

    @Test
    fun t2ProductionPackageKeepsTheDeclarativeSourceOfTruth() {
        val entries = readProductionPackage("COMMERCE-T2-1.0.0.zip")
        val manifest = JSONObject(String(entries.getValue("MANIFEST.json")))
        assertEquals(
            "production bundle must stay anchored to the immutable reference ZIP",
            "0de7c2834cefa9152822b8b85211bbae6b62c40acda3cfa59f4c2d02cbf93276",
            manifest.getString("source_sha256")
        )
        for (required in listOf(
            "render/film.py", "reverse-engineering/effets.json", "form/questions.json",
            "schemas/render-job.json", "capture-guide/ecrans.json", "audio/catalogue.json",
            "audio/voix.json"
        )) {
            assertTrue("T2 production package missing $required", entries.containsKey(required))
        }
        val effets = JSONObject(String(entries.getValue("reverse-engineering/effets.json")))
        assertEquals(4, effets.getJSONArray("scenes").length())
        assertEquals(13, effets.getJSONArray("effets").length())
        val form = JSONObject(String(entries.getValue("form/questions.json")))
        val keys = (0 until form.getJSONArray("champs").length()).map {
            form.getJSONArray("champs").getJSONObject(it).getString("cle")
        }
        assertEquals(
            listOf("nom", "produit", "prix", "accroche", "quartier", "whatsapp"), keys
        )
        assertFalse(keys.contains("demonstration"))
    }
}
