package com.safewe.security

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Short code derived from all four public keys of a pairing. Both devices show the same code;
 * if someone substituted a key, the codes differ. Users compare them out loud / in person.
 */
object SafetyCode {
    fun compute(parentAgreement: String, parentSigning: String, childAgreement: String, childSigning: String): String? {
        val parts = listOf(parentAgreement, parentSigning, childAgreement, childSigning)
        if (parts.any { it.isBlank() }) return null
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            val b = it.toByteArray()
            md.update(ByteBuffer.allocate(4).putInt(b.size).array()); md.update(b)
        }
        val h = md.digest()
        return (0 until 5).joinToString(" ") { i ->
            val v = ByteBuffer.wrap(h, i * 4, 4).int.toLong() and 0xFFFFFFFFL
            "%05d".format(v % 100_000)
        }
    }
}
