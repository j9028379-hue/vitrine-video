package com.vitrinevideo.engine.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects proprietary templates (`partition.json`, `profil.json`, fonts, music tracks) on-device:
 * - Never stores plaintext templates or music in public storage or APK `assets/`.
 * - Encrypts downloaded template packs at rest using AES-256-GCM backed by Android Keystore.
 * - Materializes plaintext into an ephemeral `.vitrine_vault_work_*` directory strictly for
 *   an active preview/export session and wipes it immediately on cleanup.
 *
 * Honest security boundary: on a rooted device with runtime hooking (e.g. Frida), in-memory
 * plaintext buffers during active playback/Media3 decode can still be observed; full server
 * rendering fallback remains available when maximum DRM isolation is required.
 */
class ProtectedResourceVault(
    private val vaultRootDir: File,
    private val keyProvider: () -> SecretKey
) {
    init {
        vaultRootDir.mkdirs()
    }

    fun storeEncrypted(relativePath: String, plaintextBytes: ByteArray): String {
        val safeRel = sanitizeRelativePath(relativePath)
        val outFile = vaultFileFor(safeRel)
        outFile.parentFile?.mkdirs()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintextBytes)

        // Binary envelope: [1 byte ivLen][iv][ciphertext+GCM tag]
        val tmp = File(outFile.parentFile, "${outFile.name}.tmp")
        tmp.outputStream().use { os ->
            os.write(iv.size and 0xFF)
            os.write(iv)
            os.write(ciphertext)
        }
        if (!tmp.renameTo(outFile)) {
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
        }
        return sha256Hex(plaintextBytes)
    }

    fun hasEncryptedAsset(relativePath: String): Boolean =
        vaultFileFor(sanitizeRelativePath(relativePath)).exists()

    fun decryptBytes(relativePath: String): ByteArray {
        val inFile = vaultFileFor(sanitizeRelativePath(relativePath))
        require(inFile.exists()) { "Ressource chiffrée absente du coffre: $relativePath" }
        val all = inFile.readBytes()
        require(all.size > 16) { "Enveloppe chiffrée corrompue: $relativePath" }
        val ivLen = all[0].toInt() and 0xFF
        require(ivLen in 12..16 && all.size > 1 + ivLen + 16) { "IV invalide dans $relativePath" }
        val iv = all.copyOfRange(1, 1 + ivLen)
        val ciphertext = all.copyOfRange(1 + ivLen, all.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun materializeToWorkdir(relativePaths: List<String>, ephemeralWorkDir: File): File {
        ephemeralWorkDir.mkdirs()
        for (rel in relativePaths) {
            val safeRel = sanitizeRelativePath(rel)
            val target = File(ephemeralWorkDir, safeRel)
            target.parentFile?.mkdirs()
            target.writeBytes(decryptBytes(safeRel))
        }
        return ephemeralWorkDir
    }

    fun wipeWorkdir(ephemeralWorkDir: File) {
        if (ephemeralWorkDir.exists()) {
            ephemeralWorkDir.deleteRecursively()
        }
    }

    private fun vaultFileFor(safeRel: String): File {
        val hashed = sha256Hex(safeRel.toByteArray(Charsets.UTF_8))
        return File(vaultRootDir, "$hashed.vpack")
    }

    private fun sanitizeRelativePath(rel: String): String {
        val norm = rel.replace('\\', '/').trimStart('/')
        require(!norm.contains("..")) { "Chemin invalide: $rel" }
        require(norm.isNotEmpty()) { "Chemin vide" }
        return norm
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "vitrine_resource_vault_aes256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        fun createForAndroid(context: Context, keyAlias: String = DEFAULT_ALIAS): ProtectedResourceVault {
            val dir = File(context.noBackupFilesDir, "vitrine_vault")
            return ProtectedResourceVault(dir) { getOrCreateAndroidKeystoreKey(keyAlias) }
        }

        private fun getOrCreateAndroidKeystoreKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setUnlockedDeviceRequired(false)
            }
            keyGen.init(builder.build())
            return keyGen.generateKey()
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
