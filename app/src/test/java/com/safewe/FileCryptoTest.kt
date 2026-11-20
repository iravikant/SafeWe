package com.safewe

import com.safewe.security.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

class FileCryptoTest {
    private val kek = AesGcm.newKey()
    private val keyAad = "k".toByteArray()
    private val wrap: (ByteArray) -> ByteArray = { AesGcm.encrypt(kek, it, keyAad) }
    private val unwrap: (ByteArray) -> ByteArray = { AesGcm.decrypt(kek, it, keyAad) }
    private val meta = FileMeta("photo.jpg", "image/jpeg", 0, 123L)
    private val chunk = 1024
    private val full = chunk + 16

    private fun data(n: Int) = ByteArray(n) { ((it * 31) xor (it shr 3)).toByte() }
    private fun enc(d: ByteArray, c: Int = chunk): ByteArray {
        val o = ByteArrayOutputStream(); FileCrypto.encrypt(ByteArrayInputStream(d), o, meta, wrap, c); return o.toByteArray()
    }
    private fun dec(blob: ByteArray, u: (ByteArray) -> ByteArray = unwrap): Pair<DecryptResult, ByteArray> {
        val o = ByteArrayOutputStream(); val r = FileCrypto.decrypt(ByteArrayInputStream(blob), o, u); return r to o.toByteArray()
    }
    /** header = magic4 ver1 chunk4 prefix8 wrappedLen4 wrapped metaLen4 metaCt */
    private fun headerLen(b: ByteArray): Int {
        val wk = ByteBuffer.wrap(b, 17, 4).int
        val metaOff = 21 + wk
        return metaOff + 4 + ByteBuffer.wrap(b, metaOff, 4).int
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun flip(b: ByteArray, i: Int) = b.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
    private fun bad(blob: ByteArray) { assertThrows(FileCryptoException::class.java) { dec(blob) } }

    @Test fun roundTripAroundChunkBoundaries() {
        for (n in listOf(0, 1, 1023, 1024, 1025, 2048, 3 * 1024 + 17, 10_000)) {
            val d = data(n)
            val (r, out) = dec(enc(d))
            assertArrayEquals("size $n", d, out)
            assertEquals(n.toLong(), r.plainBytes)
            assertEquals(sha(d), r.plainSha256)
        }
    }

    @Test fun encryptResultMatchesDecryptResult() {
        val d = data(5000); val o = ByteArrayOutputStream()
        val e = FileCrypto.encrypt(ByteArrayInputStream(d), o, meta, wrap, chunk)
        assertEquals(sha(d), e.plainSha256); assertEquals(o.size().toLong(), e.cipherBytes)
        assertEquals(e.plainSha256, dec(o.toByteArray()).first.plainSha256)
    }

    @Test fun overheadIsExactlyOneTagPerChunk() {
        val b = enc(data(3 * 1024 + 17)); assertEquals(headerLen(b) + 3 * full + 17 + 16, b.size)
        val c = enc(data(2048)); assertEquals(headerLen(c) + 2 * full, c.size)           // no extra empty chunk
        val e = enc(ByteArray(0)); assertEquals(headerLen(e) + 16, e.size)                // empty file = one tag-only chunk
    }

    @Test fun metadataIsRestoredAndOnlyNeedsTheHeader() {
        val b = enc(data(3000))
        assertEquals(meta, dec(b).first.meta)
        assertEquals(meta, FileCrypto.readMeta(ByteArrayInputStream(b.copyOf(headerLen(b))), unwrap))
    }

    @Test fun nameAndPlaintextAreNotVisibleInTheOutput() {
        val b = enc(ByteArray(5000) { 'A'.code.toByte() })
        assertFalse(String(b, Charsets.ISO_8859_1).contains("photo.jpg"))
        assertFalse(String(b, Charsets.ISO_8859_1).contains("A".repeat(32)))
    }

    @Test fun everyEncryptionIsDifferent() {
        val d = data(2000)
        val a = enc(d); val b = enc(d)
        assertFalse(a.contentEquals(b))
        assertFalse(a.copyOfRange(21, 40).contentEquals(b.copyOfRange(21, 40))) // fresh wrapped file key
    }

    @Test fun flippingAnyByteIsDetected() {
        val d = data(3 * 1024 + 17); val b = enc(d); val h = headerLen(b)
        for (i in listOf(0, 4, 8, 12, 22, h - 1, h + 5, h + full + 3, b.size - 1)) bad(flip(b, i))
    }

    @Test fun truncationIsDetected() {
        val b = enc(data(4 * 1024)); val h = headerLen(b)
        bad(b.copyOf(h + full))              // cut exactly on a chunk boundary
        bad(b.copyOf(h + 2 * full))
        bad(b.copyOf(h + full + 500))        // cut mid-chunk
        bad(b.copyOf(b.size - 1))            // last byte missing
        bad(b.copyOf(h))                     // header only
        bad(b.copyOf(h + 5))                 // less than one tag
        bad(b.copyOf(h - 3))                 // inside the header
    }

    @Test fun appendedDataIsDetected() {
        val b = enc(data(2 * 1024 + 17))
        bad(b + byteArrayOf(1, 2, 3))
        bad(b + ByteArray(full))
        val exact = enc(data(2048)); bad(exact + ByteArray(16))
    }

    @Test fun reorderedOrDuplicatedChunksAreDetected() {
        val b = enc(data(3 * 1024 + 10)); val h = headerLen(b)
        fun block(i: Int) = b.copyOfRange(h + i * full, h + (i + 1) * full)
        val swapped = b.copyOf().also { block(1).copyInto(it, h); block(0).copyInto(it, h + full) }
        bad(swapped)
        val dup = b.copyOf().also { block(0).copyInto(it, h + full) }
        bad(dup)
    }

    @Test fun chunksFromAnotherFileCannotBeSplicedIn() {
        val d = data(3 * 1024 + 10)
        val a = enc(d); val b = enc(d); val ha = headerLen(a); val hb = headerLen(b)
        bad(a.copyOf().also { b.copyOfRange(hb, hb + full).copyInto(it, ha) })
    }

    @Test fun wrongKeyIsReportedAsKeyUnavailable() {
        val b = enc(data(100)); val other = AesGcm.newKey()
        assertThrows(KeyUnavailableException::class.java) { dec(b) { AesGcm.decrypt(other, it, keyAad) } }
    }

    @Test fun notASafeWeFile() {
        assertThrows(FileFormatException::class.java) { dec("hello world, definitely not encrypted".toByteArray()) }
        assertThrows(FileFormatException::class.java) { dec(ByteArray(0)) }
    }

    @Test fun absurdLengthsInTheHeaderAreRejectedBeforeAllocating() {
        val b = enc(data(100))
        assertThrows(FileFormatException::class.java) { dec(b.copyOf().also { ByteBuffer.wrap(it).putInt(17, 0x40000000) }) }
        val metaOff = 21 + ByteBuffer.wrap(b, 17, 4).int
        assertThrows(FileFormatException::class.java) { dec(b.copyOf().also { ByteBuffer.wrap(it).putInt(metaOff, 0x7FFFFFFF) }) }
        assertThrows(FileFormatException::class.java) { dec(b.copyOf().also { ByteBuffer.wrap(it).putInt(5, 1 shl 30) }) }
    }

    @Test fun invalidChunkSizeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { enc(data(10), 10) }
    }

    @Test fun defaultChunkSizeWorks() {
        val d = data(3_000_000)
        val o = ByteArrayOutputStream(); FileCrypto.encrypt(ByteArrayInputStream(d), o, meta, wrap)
        assertArrayEquals(d, dec(o.toByteArray()).second)
    }
}
