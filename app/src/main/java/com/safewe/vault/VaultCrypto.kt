package com.safewe.vault

import com.safewe.security.AesGcm
import com.safewe.security.Hkdf
import java.security.GeneralSecurityException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The vault master key (VMK) is 256 random bits. It is stored only in WRAPPED form:
 *   KEK = HKDF( PBKDF2-HMAC-SHA256(secret, salt, iterations),  salt = devicePepper )
 * The pepper is an HMAC output from a non-exportable Android Keystore key, so a stolen copy of the app data
 * cannot be brute-forced off the device. A wrong PIN/password fails the GCM tag, so there is no separate
 * (and weaker) stored "password hash" to attack.
 */
object VaultCrypto {
    const val ITERATIONS = 310_000
    private val AAD = "SafeWe-vmk-v1".toByteArray()
    private val INFO = "SafeWe-vault-kek-v1".toByteArray()

    fun newVmk(): ByteArray = AesGcm.newKey()

    fun kek(secret: CharArray, salt: ByteArray, iterations: Int, pepper: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, 256)
        val raw = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { Hkdf.derive(raw, pepper, INFO, 32) } finally { raw.fill(0) }
    }

    fun wrap(vmk: ByteArray, kek: ByteArray): ByteArray = AesGcm.encrypt(kek, vmk, AAD)

    /** null = wrong secret (or tampered blob). */
    fun unwrap(blob: ByteArray, kek: ByteArray): ByteArray? =
        try { AesGcm.decrypt(kek, blob, AAD) } catch (e: GeneralSecurityException) { null } catch (e: IllegalArgumentException) { null }
}
