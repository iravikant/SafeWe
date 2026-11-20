package com.safewe

import com.safewe.security.Hkdf
import org.junit.Assert.assertEquals
import org.junit.Test

class HkdfTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** RFC 5869, Appendix A.1 */
    @Test fun rfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex(okm))
    }
}
