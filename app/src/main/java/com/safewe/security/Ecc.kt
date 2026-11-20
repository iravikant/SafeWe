package com.safewe.security

import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** P-256 ECDH (key agreement) and ECDSA (signatures). */
object Ecc {
    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicFrom(x509: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))
    fun privateFrom(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun agree(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }

    fun sign(priv: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() }

    fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(sig) }
    } catch (e: GeneralSecurityException) { false }
}
