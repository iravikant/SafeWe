package com.safewe.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length in 1..(255 * 32))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0); var pos = 0; var counter = 1
        while (pos < length) {
            mac.update(t); mac.update(info); mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n); pos += n; counter++
        }
        return out
    }
}
