package com.safewe.security

import java.security.MessageDigest

/** Splits an encrypted payload into bounded chunks that fit Firestore's 1 MiB document limit. */
object PreviewChunker {
    const val DEFAULT_CHUNK = 64 * 1024

    fun split(data: ByteArray, chunkSize: Int = DEFAULT_CHUNK): List<ByteArray> {
        require(chunkSize in 1..(900 * 1024)) { "chunkSize out of range" }
        if (data.isEmpty()) return emptyList()
        return (data.indices step chunkSize).map { data.copyOfRange(it, minOf(it + chunkSize, data.size)) }
    }

    fun join(chunks: List<ByteArray>, expectedCount: Int, expectedSha256: String): ByteArray {
        require(chunks.size == expectedCount) { "Chunk count mismatch: ${chunks.size} != $expectedCount" }
        val out = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(out, pos); pos += c.size }
        require(sha256(out).equals(expectedSha256, ignoreCase = true)) { "Integrity check failed" }
        return out
    }

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
