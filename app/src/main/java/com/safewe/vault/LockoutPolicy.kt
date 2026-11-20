package com.safewe.vault

enum class SecretKind { PIN, PASSWORD }

/** Escalating delay after repeated wrong PIN/password attempts (online brute-force protection). */
object LockoutPolicy {
    private const val MIN = 60_000L
    fun delayMs(failures: Int): Long = when {
        failures < 5 -> 0
        failures == 5 -> 30_000
        failures == 6 -> MIN
        failures == 7 -> 5 * MIN
        failures == 8 -> 15 * MIN
        else -> 60 * MIN
    }
    fun lockedUntil(failures: Int, now: Long): Long = if (delayMs(failures) == 0L) 0 else now + delayMs(failures)
}

object CredentialRules {
    const val PIN_MIN = 6
    const val PIN_MAX = 12
    const val PW_MIN = 8
    const val PW_MAX = 128

    /** Returns a user-facing problem, or null if acceptable. */
    fun validate(kind: SecretKind, secret: String): String? = when (kind) {
        SecretKind.PIN -> when {
            !secret.all { it in '0'..'9' } -> "A PIN can only contain digits."
            secret.length !in PIN_MIN..PIN_MAX -> "Use $PIN_MIN to $PIN_MAX digits."
            secret.toSet().size == 1 -> "That PIN is too easy to guess."
            isSequence(secret) -> "That PIN is too easy to guess."
            else -> null
        }
        SecretKind.PASSWORD -> when {
            secret.length !in PW_MIN..PW_MAX -> "Use $PW_MIN to $PW_MAX characters."
            secret.all { it.isWhitespace() } -> "That password is too easy to guess."
            secret.toSet().size == 1 -> "That password is too easy to guess."
            else -> null
        }
    }

    private fun isSequence(s: String): Boolean {
        val d = s.zipWithNext { a, b -> b - a }
        return d.all { it == 1 } || d.all { it == -1 }
    }
}
