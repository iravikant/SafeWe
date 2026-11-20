package com.safewe

import com.safewe.security.*
import org.junit.Assert.*
import org.junit.Test

class TransferCryptoTest {
    private val recipient = Ecc.newKeyPair()
    private val tid = "t-1"
    private val aad = TransferCrypto.aad(tid, "r-1", "CAMERA")
    private val plain = ByteArray(5000) { (it * 7).toByte() }

    @Test fun roundTrip() {
        val s = TransferCrypto.seal(plain, recipient.public.encoded, tid, aad)
        val out = TransferCrypto.open(s.ciphertext, s.ephemeralPublic, recipient.private, tid, aad)
        assertArrayEquals(plain, out)
    }

    @Test fun ciphertextDoesNotContainPlaintext() {
        val s = TransferCrypto.seal(plain, recipient.public.encoded, tid, aad)
        assertFalse(s.ciphertext.toList().windowed(32).any { it == plain.toList().take(32) })
    }

    @Test fun eachSealUsesFreshEphemeralKey() {
        val a = TransferCrypto.seal(plain, recipient.public.encoded, tid, aad)
        val b = TransferCrypto.seal(plain, recipient.public.encoded, tid, aad)
        assertFalse(a.ephemeralPublic.contentEquals(b.ephemeralPublic))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test(expected = Exception::class) fun tamperedCiphertextRejected() {
        val s = TransferCrypto.seal(plain, recipient.public.encoded, tid, aad)
        s.ciphertext[s.ciphertext.size / 2] = (s.ciphertext[s.ciphertext.size / 2] + 1).toByte()
        TransferCrypto.open(s.ciphertext, s.ephemeralPublic, recipient.private, tid, aad)
    }

    @Test(expected = Exception::class) fun wrongAadRejected() {
        val s = TransferCrypto.seal(plain, recipient.public.encoded, tid, aad)
        TransferCrypto.open(s.ciphertext, s.ephemeralPublic, recipient.private, tid, TransferCrypto.aad(tid, "r-2", "CAMERA"))
    }

    @Test(expected = Exception::class) fun wrongTransferIdRejected() {
        val s = TransferCrypto.seal(plain, recipient.public.encoded, tid, aad)
        TransferCrypto.open(s.ciphertext, s.ephemeralPublic, recipient.private, "other", aad)
    }

    @Test(expected = Exception::class) fun wrongRecipientRejected() {
        val s = TransferCrypto.seal(plain, recipient.public.encoded, tid, aad)
        TransferCrypto.open(s.ciphertext, s.ephemeralPublic, Ecc.newKeyPair().private, tid, aad)
    }

    @Test fun signaturesVerifyAndDetectChange() {
        val k = Ecc.newKeyPair()
        val sig = Ecc.sign(k.private, "hello".toByteArray())
        assertTrue(Ecc.verify(k.public, "hello".toByteArray(), sig))
        assertFalse(Ecc.verify(k.public, "hellp".toByteArray(), sig))
        assertFalse(Ecc.verify(Ecc.newKeyPair().public, "hello".toByteArray(), sig))
    }
}
