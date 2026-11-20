package com.safewe.security

import java.util.Base64

object B64 {
    fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun dec(s: String): ByteArray = Base64.getDecoder().decode(s)
}
