package com.safewe

import com.safewe.security.PreviewChunker
import org.junit.Assert.*
import org.junit.Test

class ChunkerTest {
    @Test fun roundTrip() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val chunks = PreviewChunker.split(data, 64 * 1024)
        assertEquals(4, chunks.size)
        assertTrue(chunks.all { it.size <= 64 * 1024 })
        val joined = PreviewChunker.join(chunks, chunks.size, PreviewChunker.sha256(data))
        assertArrayEquals(data, joined)
    }

    @Test(expected = IllegalArgumentException::class) fun detectsTampering() {
        val data = ByteArray(1000) { it.toByte() }
        val chunks = PreviewChunker.split(data, 400).toMutableList()
        chunks[1] = chunks[1].also { it[0] = (it[0] + 1).toByte() }
        PreviewChunker.join(chunks, 3, PreviewChunker.sha256(data))
    }

    @Test(expected = IllegalArgumentException::class) fun detectsMissingChunk() {
        val data = ByteArray(1000)
        val chunks = PreviewChunker.split(data, 400)
        PreviewChunker.join(chunks.dropLast(1), 3, PreviewChunker.sha256(data))
    }
}
