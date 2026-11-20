package com.safewe.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-held secrets used by the vault. None of them can be exported from the device. */
class VaultKeys {
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Deterministic device-bound value mixed into the PIN/password key derivation. */
    fun pepper(): ByteArray {
        val key = (ks().getKey(PEPPER, null) as? SecretKey) ?: run {
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(PEPPER, KeyProperties.PURPOSE_SIGN).build())
            g.generateKey()
        }
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal("SafeWe-vault-pepper-v1".toByteArray()) }
    }

    /**
     * Biometric key: usable only right after a STRONG biometric. By default Android invalidates it when new
     * biometrics are enrolled; the PIN/password wrap of the vault key still exists, so nothing is lost.
     */
    private fun bioKey(): SecretKey {
        (ks().getKey(BIO, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(BIO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (android.os.Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1) // authenticate for every use
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec.build()); generateKey() }
    }

    fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, bioKey()) }

    /** Throws KeyPermanentlyInvalidatedException if the biometric set changed. */
    fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, bioKey(), GCMParameterSpec(128, iv)) }

    fun deleteBioKey() { runCatching { ks().deleteEntry(BIO) } }

    private companion object { const val PEPPER = "safewe_vault_pepper_v1"; const val BIO = "safewe_vault_bio_v1" }
}
