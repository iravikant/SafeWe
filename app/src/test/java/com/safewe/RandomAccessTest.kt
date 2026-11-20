package com.safewe

import com.safewe.security.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class RandomAccessTest {
    private val kek = AesGcm.newKey()
    private val aad = "k".toByteArray()
    private val wrap: (ByteArray) -> ByteArray = { AesGcm.encrypt(kek, it, aad) }
    private val unwrap: (ByteArray) -> ByteArray = { AesGcm.decrypt(kek, it, aad) }
    private val meta = FileMeta("v.mp4", "video/mp4", 0, 1L)
    private val chunk = 1024

    private fun src(b: ByteArray) = object : FileCrypto.RandomSource {
        override val length = b.size.toLong()
        override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= b.size) return -1
            val n = minOf(len, b.size - pos.toInt()); System.arraycopy(b, pos.toInt(), buf, off, n); return n
        }
    }
    private fun enc(d: ByteArray): ByteArray { val o = ByteArrayOutputStream(); FileCrypto.encrypt(ByteArrayInputStream(d), o, meta, wrap, chunk); return o.toByteArray() }
    private fun data(n: Int) = ByteArray(n).also { Random(7).nextBytes(it) }
    private fun readAll(r: FileCrypto.RandomAccessReader): ByteArray {
        val out = ByteArray(r.plainSize.toInt()); var p = 0
        while (p < out.size) { val n = r.readAt(p.toLong(), out, p, out.size - p); if (n <= 0) break; p += n }
        return out
    }

    @Test fun sizeAndFullReadMatch() {
        for (n in listOf(0, 1, 1023, 1024, 1025, 4096, 5000)) {
            val d = data(n); val r = FileCrypto.openRandomAccess(src(enc(d)), unwrap)
            assertEquals("size $n", n.toLong(), r.plainSize); assertArrayEquals(d, readAll(r))
        }
    }
    @Test fun metaIsAvailable() = assertEquals(meta, FileCrypto.openRandomAccess(src(enc(data(10))), unwrap).meta)

    @Test fun randomSeeksAcrossChunkBoundaries() {
        val d = data(10_000); val r = FileCrypto.openRandomAccess(src(enc(d)), unwrap); val rnd = Random(1)
        repeat(300) {
            val pos = rnd.nextInt(d.size); val len = 1 + rnd.nextInt(3000); val buf = ByteArray(len)
            val n = r.readAt(pos.toLong(), buf, 0, len)
            val expected = minOf(len, d.size - pos)
            assertEquals(expected, n); assertArrayEquals(d.copyOfRange(pos, pos + expected), buf.copyOf(n))
        }
    }
    @Test fun readingPastTheEndReturnsMinusOne() {
        val r = FileCrypto.openRandomAccess(src(enc(data(100))), unwrap)
        assertEquals(-1, r.readAt(100, ByteArray(10), 0, 10)); assertEquals(-1, r.readAt(5000, ByteArray(10), 0, 10))
    }
    @Test fun tamperedChunkFailsOnlyWhenRead() {
        val d = data(4096); val blob = enc(d)
        val hdr = blob.size - (4 * (chunk + 16))
        blob[hdr + (chunk + 16) + 5] = (blob[hdr + (chunk + 16) + 5] + 1).toByte() // chunk 1
        val r = FileCrypto.openRandomAccess(src(blob), unwrap)
        val buf = ByteArray(10)
        assertEquals(10, r.readAt(0, buf, 0, 10))                                  // chunk 0 is fine
        assertThrows(FileCryptoException::class.java) { r.readAt(1100, buf, 0, 10) } // chunk 1 is not
    }
    @Test fun truncatedFileFailsOnTheNewLastChunk() {
        val blob = enc(data(4096)); val cut = blob.copyOf(blob.size - (chunk + 16)) // drop the final chunk
        val r = FileCrypto.openRandomAccess(src(cut), unwrap)
        assertThrows(FileCryptoException::class.java) { readAll(r) }
    }
    @Test fun closedReaderRefusesReads() {
        val r = FileCrypto.openRandomAccess(src(enc(data(100))), unwrap); r.close()
        assertThrows(IllegalStateException::class.java) { r.readAt(0, ByteArray(1), 0, 1) }
    }
    @Test fun wrongKeyIsRefused() {
        val other = AesGcm.newKey()
        assertThrows(KeyUnavailableException::class.java) { FileCrypto.openRandomAccess(src(enc(data(100)))) { AesGcm.decrypt(other, it, aad) } }
    }
}
