package com.safewe.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM. Output layout: [12-byte IV][ciphertext || 16-byte tag]. */
object AesGcm {
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun encrypt(key: SecretKey, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key) // Keystore keys: provider generates a fresh IV
        aad?.let { c.updateAAD(it) }
        return c.iv + c.doFinal(plain)
    }

    fun encrypt(rawKey: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        aad?.let { c.updateAAD(it) }
        return iv + c.doFinal(plain)
    }

    /** Throws AEAD exception if the data or AAD was modified. */
    fun decrypt(key: SecretKey, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(blob.size > IV_LEN + 16) { "Ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)))
        aad?.let { c.updateAAD(it) }
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    fun decrypt(rawKey: ByteArray, blob: ByteArray, aad: ByteArray? = null): ByteArray =
        decrypt(SecretKeySpec(rawKey, "AES"), blob, aad)
}

/** Hardware-backed (where available) AES-256 key held in Android Keystore. Never hardcoded, never exportable. */
class KeystoreKeys(private val alias: String = "safewe_master_v1") {
    fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        // Prefer StrongBox (dedicated secure chip, API 28+); fall back to the TEE / software Keystore.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching { return generate(strongBox = true) }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && android.os.Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
        gen.init(spec.build())
        return gen.generateKey()
    }
}
