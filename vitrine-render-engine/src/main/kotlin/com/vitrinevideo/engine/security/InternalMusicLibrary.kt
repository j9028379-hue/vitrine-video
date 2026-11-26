package com.vitrinevideo.engine.security

import com.vitrinevideo.engine.interpreter.MusicCatalogueParser
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Internal, per-template music library sealed for distribution.
 *
 * MP3 bytes are never committed to the public repository: each track ships as an
 * authenticated HMAC-SHA256 counter-mode vault entry. This is resource protection,
 * not DRM; the honest boundary is documented in the T2 integration report. The
 * catalogue remains the authority for IDs, hashes, start points and approval.
 */
object InternalMusicLibrary {

    private const val MAGIC = "VMP1"
    private const val KDF_LABEL = "vitrine-internal-music-v1"

    data class Entry(
        val number: Int,
        val resourceRelativePath: String,
        val vaultRelativePath: String,
        val iv: ByteArray,
        val sha256: String,
        val bytes: Long,
        val startSec: Double
    )

    data class Manifest(
        val templateId: String,
        val version: String,
        val audioProfile: String,
        val cipher: String,
        val entries: List<Entry>
    )

    fun read(libraryRoot: File, templateId: String, version: String): Manifest? {
        val file = File(libraryRoot, "$templateId/$version/library.json")
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val arr = json.getJSONArray("tracks")
        val entries = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                number = o.getInt("numero"),
                resourceRelativePath = o.getString("resource"),
                vaultRelativePath = o.getString("vault"),
                iv = Base64.getDecoder().decode(o.getString("iv_b64")),
                sha256 = o.getString("sha256").lowercase(),
                bytes = o.getLong("bytes"),
                startSec = o.optDouble("source_debut_s", 0.0)
            )
        }
        return Manifest(
            templateId = json.getString("template_id"),
            version = json.getString("version"),
            audioProfile = json.getString("audio_profile"),
            cipher = json.getString("cipher"),
            entries = entries
        )
    }

    /** Decrypts into the private package cache, verifies SHA-256, and returns catalogue resources. */
    fun materialize(
        manifest: Manifest,
        libraryRoot: File,
        resourcesDir: File
    ): Map<Int, MusicCatalogueParser.DeclaredResource> {
        require(manifest.cipher == "HMAC_SHA256_CTR_V1") { "Coffre musical non pris en charge: ${manifest.cipher}" }
        val out = linkedMapOf<Int, MusicCatalogueParser.DeclaredResource>()
        for (entry in manifest.entries) {
            val vaultFile = File(libraryRoot, manifest.templateId + "/" + manifest.version + "/" + entry.vaultRelativePath)
            require(vaultFile.isFile) { "Fichier coffre absent: ${entry.vaultRelativePath}" }
            val plain = decrypt(vaultFile.readBytes(), manifest.templateId, manifest.version, entry.number, entry.iv)
            val actualSha = sha256Hex(plain)
            require(actualSha.equals(entry.sha256, ignoreCase = true)) {
                "SHA-256 du coffre invalide pour #${entry.number}: $actualSha"
            }
            require(plain.size.toLong() == entry.bytes) { "Taille du coffre invalide pour #${entry.number}" }
            val target = safeResolve(resourcesDir, entry.resourceRelativePath)
            target.parentFile?.mkdirs()
            if (!target.isFile || target.length() != plain.size.toLong() ||
                sha256Hex(target.readBytes()) != actualSha
            ) {
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(plain)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            out[entry.number] = MusicCatalogueParser.DeclaredResource(
                relativePath = entry.resourceRelativePath,
                sha256 = actualSha,
                bytes = plain.size.toLong(),
                startSec = entry.startSec
            )
        }
        return out
    }

    fun encrypt(
        plain: ByteArray,
        templateId: String,
        version: String,
        number: Int,
        iv: ByteArray
    ): ByteArray {
        val key = keyFor(templateId, version, number)
        val macKey = keyFor(templateId, version, number, "hmac")
        val cipher = xorStream(plain, key, iv)
        val header = MAGIC.toByteArray(Charsets.US_ASCII) + iv
        return header + cipher + hmac(macKey, header + cipher)
    }

    private fun decrypt(
        sealed: ByteArray,
        templateId: String,
        version: String,
        number: Int,
        iv: ByteArray
    ): ByteArray {
        val magic = sealed.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        require(magic == MAGIC) { "Enveloppe coffre invalide" }
        require(iv.size == 12) { "IV coffre invalide" }
        val actualIv = sealed.copyOfRange(4, 16)
        require(actualIv.contentEquals(iv)) { "IV coffre non conforme au manifeste" }
        val body = sealed.copyOfRange(0, sealed.size - 32)
        val tag = sealed.copyOfRange(sealed.size - 32, sealed.size)
        val macKey = keyFor(templateId, version, number, "hmac")
        require(hmac(macKey, body).contentEquals(tag)) { "Authentification coffre échouée pour #$number" }
        val key = keyFor(templateId, version, number)
        return xorStream(sealed.copyOfRange(16, sealed.size - 32), key, iv)
    }

    private fun xorStream(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var counter = 0
        var offset = 0
        while (offset < input.size) {
            val counterBytes = byteArrayOf(
                (counter ushr 24).toByte(), (counter ushr 16).toByte(),
                (counter ushr 8).toByte(), counter.toByte()
            )
            val block = hmac(key, counterBytes + iv)
            var j = 0
            while (j < block.size && offset < input.size) {
                out[offset] = (input[offset].toInt() xor block[j].toInt()).toByte()
                offset++
                j++
            }
            counter++
        }
        return out
    }

    private fun keyFor(templateId: String, version: String, number: Int, suffix: String = ""): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("$KDF_LABEL|$templateId|$version|$number|$suffix".toByteArray(Charsets.UTF_8))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun safeResolve(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute && !relativePath.contains("..")) {
            "Chemin musical invalide: $relativePath"
        }
        val canonical = root.canonicalFile
        val target = File(canonical, relativePath).canonicalFile
        require(target.path == canonical.path || target.path.startsWith(canonical.path + File.separator)) {
            "Évasion de répertoire interdite: $relativePath"
        }
        return target
    }
}
