package com.safewe.security

import java.security.PrivateKey

/**
 * Per-transfer hybrid encryption:
 *   child generates an EPHEMERAL P-256 key, does ECDH with the parent's agreement public key,
 *   HKDF(salt = transferId) -> 256-bit AES key, AES-256-GCM with AAD binding the transfer to its request.
 * The ephemeral private key is discarded, so past previews stay safe even if the child's long-term keys leak.
 * No key or secret is ever written to Firestore; only the ephemeral PUBLIC key travels with the metadata.
 */
object TransferCrypto {
    private val INFO = "SafeWe-preview-v1".toByteArray()

    class Sealed(val ciphertext: ByteArray, val ephemeralPublic: ByteArray)

    fun aad(transferId: String, requestId: String, type: String) = "$transferId|$requestId|$type".toByteArray()

    fun seal(plain: ByteArray, recipientAgreementPublic: ByteArray, transferId: String, aad: ByteArray): Sealed {
        val eph = Ecc.newKeyPair()
        val shared = Ecc.agree(eph.private, Ecc.publicFrom(recipientAgreementPublic))
        val key = Hkdf.derive(shared, transferId.toByteArray(), INFO)
        try { return Sealed(AesGcm.encrypt(key, plain, aad), eph.public.encoded) }
        finally { key.fill(0); shared.fill(0) }
    }

    /** Throws if the ciphertext, AAD, or key material was altered. */
    fun open(ciphertext: ByteArray, ephemeralPublic: ByteArray, recipientAgreementPrivate: PrivateKey, transferId: String, aad: ByteArray): ByteArray {
        val shared = Ecc.agree(recipientAgreementPrivate, Ecc.publicFrom(ephemeralPublic))
        val key = Hkdf.derive(shared, transferId.toByteArray(), INFO)
        try { return AesGcm.decrypt(key, ciphertext, aad) }
        finally { key.fill(0); shared.fill(0) }
    }
}
