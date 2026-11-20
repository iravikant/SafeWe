package com.safewe.security

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class FileCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
class FileFormatException(message: String) : FileCryptoException(message)
class CorruptFileException(cause: Throwable? = null) : FileCryptoException("This file is damaged or has been modified.", cause)
class KeyUnavailableException(cause: Throwable? = null) :
    FileCryptoException("This file was encrypted with a key that is not available on this device.", cause)

data class FileMeta(val name: String, val mime: String, val size: Long, val createdAt: Long)
class EncryptResult(val plainBytes: Long, val plainSha256: String, val cipherBytes: Long)
class DecryptResult(val meta: FileMeta, val plainBytes: Long, val plainSha256: String)

/**
 * SafeWe encrypted file format "SWEF" v1 (streaming, chunked AES-256-GCM, "STREAM" construction).
 *
 *   header : "SWEF" | version(1) | chunkSize(4) | noncePrefix(8) | wrappedKeyLen(4) | wrappedKey
 *            | metaLen(4) | metaCiphertext
 *   body   : chunk_0 .. chunk_n, each = AES-GCM(plain_i) with 16-byte tag appended
 *
 *   - A fresh random 256-bit FILE KEY per file; it is wrapped by the caller (Android Keystore) and never stored in clear.
 *   - nonce_i = noncePrefix(8) || counter_i(4). Counter 0xFFFFFFFF is reserved for the metadata block.
 *   - chunk AAD = SHA-256(entire header) || counter_i || finalFlag. This binds every chunk to the header,
 *     to its position (reordering fails) and to being last or not (truncation / appended data fails).
 *   - File name, MIME type, (informational) size and date live inside the encrypted metadata block.
 *     Completeness is guaranteed by the final-chunk flag, not by the stored size.
 *
 * Plaintext is only written to [OutputStream] after the chunk that produced it authenticated.
 * A failure can still occur LATER in the file (e.g. truncation), so callers must delete partial output on error.
 */
object FileCrypto {
    const val DEFAULT_CHUNK = 1 shl 20
    const val MIN_CHUNK = 1024
    const val MAX_CHUNK = 16 shl 20

    private val MAGIC = byteArrayOf(0x53, 0x57, 0x45, 0x46) // "SWEF"
    private const val VERSION = 1
    private const val TAG_LEN = 16
    private const val PREFIX_LEN = 8
    private const val META_COUNTER = 0xFFFFFFFFL
    private const val MAX_COUNTER = 0xFFFFFFFEL
    private const val MAX_WRAPPED = 512
    private const val MAX_META = 64 * 1024

    fun encrypt(
        input: InputStream, output: OutputStream, meta: FileMeta, wrap: (ByteArray) -> ByteArray,
        chunkSize: Int = DEFAULT_CHUNK, random: SecureRandom = SecureRandom()
    ): EncryptResult {
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "chunkSize out of range" }
        val fileKey = ByteArray(32).also(random::nextBytes)
        try {
            val prefix = ByteArray(PREFIX_LEN).also(random::nextBytes)
            val core = buildCore(chunkSize, prefix, wrap(fileKey))
            val metaCt = gcm(true, fileKey, nonce(prefix, META_COUNTER), sha256(core) + META_AAD, serializeMeta(meta))
            val header = core + ByteBuffer.allocate(4).putInt(metaCt.size).array() + metaCt
            output.write(header)
            val headerHash = sha256(header)

            val digest = MessageDigest.getInstance("SHA-256")
            var cur = ByteArray(chunkSize); var next = ByteArray(chunkSize)
            var curLen = readUpTo(input, cur)
            var counter = 0L; var plainTotal = 0L; var cipherTotal = header.size.toLong()
            while (true) {
                val nextLen = if (curLen == chunkSize) readUpTo(input, next) else 0
                val isFinal = nextLen == 0                       // empty file => one empty final chunk
                check(counter <= MAX_COUNTER) { "File too large" }
                val ct = gcmRange(true, fileKey, nonce(prefix, counter), chunkAad(headerHash, counter, isFinal), cur, curLen)
                output.write(ct)
                digest.update(cur, 0, curLen)
                plainTotal += curLen; cipherTotal += ct.size
                if (isFinal) break
                val t = cur; cur = next; next = t; curLen = nextLen; counter++
            }
            output.flush()
            return EncryptResult(plainTotal, hex(digest.digest()), cipherTotal)
        } finally { fileKey.fill(0) }
    }

    fun decrypt(input: InputStream, output: OutputStream, unwrap: (ByteArray) -> ByteArray): DecryptResult {
        val h = readHeader(input, unwrap)
        try {
            val ctSize = h.chunkSize + TAG_LEN
            val digest = MessageDigest.getInstance("SHA-256")
            var cur = ByteArray(ctSize); var next = ByteArray(ctSize)
            var curLen = readUpTo(input, cur)
            if (curLen < TAG_LEN) throw FileFormatException("The file is incomplete.")
            var counter = 0L; var plainTotal = 0L
            while (true) {
                val nextLen = if (curLen == ctSize) readUpTo(input, next) else 0
                val isFinal = nextLen == 0
                if (counter > MAX_COUNTER) throw FileFormatException("The file is too large.")
                val plain = try {
                    gcmRange(false, h.fileKey, nonce(h.prefix, counter), chunkAad(h.headerHash, counter, isFinal), cur, curLen)
                } catch (e: GeneralSecurityException) { throw CorruptFileException(e) }
                output.write(plain); digest.update(plain)
                plainTotal += plain.size
                if (isFinal) break
                val t = cur; cur = next; next = t; curLen = nextLen; counter++
                if (curLen < TAG_LEN) throw CorruptFileException()
            }
            output.flush()
            return DecryptResult(h.meta, plainTotal, hex(digest.digest()))
        } finally { h.fileKey.fill(0) }
    }

    /** Reads only the header (needs the key to decrypt the metadata). */
    fun readMeta(input: InputStream, unwrap: (ByteArray) -> ByteArray): FileMeta =
        readHeader(input, unwrap).also { it.fileKey.fill(0) }.meta

    // ------------------------------------------------------------------ random access (secure preview)

    interface RandomSource {
        val length: Long
        fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int
    }

    /**
     * Seekable decrypted view of a SWEF file. Only the chunks that are actually read get decrypted and
     * authenticated (one chunk is cached). Lets video / PDF viewers read an encrypted file WITHOUT a plaintext copy on disk.
     */
    class RandomAccessReader internal constructor(private val src: RandomSource, private val hdr: Header) : java.io.Closeable {
        val meta: FileMeta get() = hdr.meta
        val plainSize: Long
        private val ctSize = hdr.chunkSize + TAG_LEN
        private val bodyLen = src.length - hdr.headerLen
        private val chunkCount: Long
        private var cacheIndex = -1L
        private var cache: ByteArray? = null
        private var closed = false

        init {
            if (bodyLen < TAG_LEN) throw FileFormatException("The file is incomplete.")
            chunkCount = (bodyLen + ctSize - 1) / ctSize
            val lastCt = bodyLen - (chunkCount - 1) * ctSize
            if (lastCt < TAG_LEN) throw FileFormatException("The file is incomplete.")
            plainSize = (chunkCount - 1) * hdr.chunkSize + (lastCt - TAG_LEN)
        }

        /** Returns bytes read, or -1 at end of file. Throws [CorruptFileException] if a chunk fails authentication. */
        @Synchronized
        fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            check(!closed) { "Reader is closed" }
            require(pos >= 0 && len >= 0)
            if (pos >= plainSize) return -1
            var done = 0; var p = pos
            while (done < len && p < plainSize) {
                val c = chunk(p / hdr.chunkSize)
                val inChunk = (p % hdr.chunkSize).toInt()
                val n = minOf(len - done, c.size - inChunk)
                System.arraycopy(c, inChunk, buf, off + done, n)
                done += n; p += n
            }
            return done
        }

        private fun chunk(i: Long): ByteArray {
            if (i == cacheIndex) cache?.let { return it }
            val isLast = i == chunkCount - 1
            val start = hdr.headerLen + i * ctSize
            val n = (if (isLast) bodyLen - i * ctSize else ctSize.toLong()).toInt()
            val ct = ByteArray(n); var got = 0
            while (got < n) {
                val r = src.readAt(start + got, ct, got, n - got)
                if (r <= 0) throw FileFormatException("The file is incomplete.")
                got += r
            }
            val plain = try {
                gcmRange(false, hdr.fileKey, nonce(hdr.prefix, i), chunkAad(hdr.headerHash, i, isLast), ct, n)
            } catch (e: GeneralSecurityException) { throw CorruptFileException(e) }
            cacheIndex = i; cache = plain
            return plain
        }

        @Synchronized
        override fun close() { closed = true; hdr.fileKey.fill(0); cache = null; cacheIndex = -1 }
    }

    fun openRandomAccess(src: RandomSource, unwrap: (ByteArray) -> ByteArray): RandomAccessReader {
        val h = readHeader(SourceInputStream(src), unwrap)
        return try { RandomAccessReader(src, h) } catch (e: FileCryptoException) { h.fileKey.fill(0); throw e }
    }

    private class SourceInputStream(private val src: RandomSource) : InputStream() {
        private var pos = 0L
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = src.readAt(pos, b, off, len)
            if (n <= 0) return -1
            pos += n; return n
        }
    }

    // ------------------------------------------------------------------ internals

    internal class Header(val chunkSize: Int, val prefix: ByteArray, val fileKey: ByteArray, val meta: FileMeta, val headerHash: ByteArray, val headerLen: Int)

    private val META_AAD = "meta".toByteArray()

    private fun buildCore(chunkSize: Int, prefix: ByteArray, wrapped: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).run { write(MAGIC); writeByte(VERSION); writeInt(chunkSize); write(prefix); writeInt(wrapped.size); write(wrapped) }
        return bos.toByteArray()
    }

    private fun readHeader(input: InputStream, unwrap: (ByteArray) -> ByteArray): Header {
        val d = DataInputStream(input)
        try {
            val magic = ByteArray(4); d.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw FileFormatException("This is not a SafeWe encrypted file.")
            if (d.readUnsignedByte() != VERSION) throw FileFormatException("This file needs a newer version of SafeWe.")
            val chunkSize = d.readInt()
            if (chunkSize !in MIN_CHUNK..MAX_CHUNK) throw FileFormatException("The file header is invalid.")
            val prefix = ByteArray(PREFIX_LEN); d.readFully(prefix)
            val wrappedLen = d.readInt()
            if (wrappedLen !in 1..MAX_WRAPPED) throw FileFormatException("The file header is invalid.")
            val wrapped = ByteArray(wrappedLen); d.readFully(wrapped)
            val core = buildCore(chunkSize, prefix, wrapped)

            val metaLen = d.readInt()
            if (metaLen !in TAG_LEN..MAX_META) throw FileFormatException("The file header is invalid.")
            val metaCt = ByteArray(metaLen); d.readFully(metaCt)

            val fileKey = try { unwrap(wrapped) } catch (e: Exception) { throw KeyUnavailableException(e) }
            if (fileKey.size != 32) { fileKey.fill(0); throw FileFormatException("The file header is invalid.") }
            val meta = try {
                deserializeMeta(gcm(false, fileKey, nonce(prefix, META_COUNTER), sha256(core) + META_AAD, metaCt))
            } catch (e: GeneralSecurityException) { fileKey.fill(0); throw CorruptFileException(e) }

            val header = core + ByteBuffer.allocate(4).putInt(metaLen).array() + metaCt
            return Header(chunkSize, prefix, fileKey, meta, sha256(header), header.size)
        } catch (e: EOFException) { throw FileFormatException("The file is incomplete.") }
    }

    private fun serializeMeta(m: FileMeta): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).run { writeUTF(m.name); writeUTF(m.mime); writeLong(m.size); writeLong(m.createdAt) }
        return bos.toByteArray()
    }

    private fun deserializeMeta(b: ByteArray): FileMeta = try {
        DataInputStream(b.inputStream()).run { FileMeta(readUTF(), readUTF(), readLong(), readLong()) }
    } catch (e: Exception) { throw CorruptFileException(e) }

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter.toInt()).array()

    private fun chunkAad(headerHash: ByteArray, counter: Long, isFinal: Boolean): ByteArray =
        ByteBuffer.allocate(headerHash.size + 5).put(headerHash).putInt(counter.toInt()).put(if (isFinal) 1 else 0).array()

    private fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        gcmRange(encrypt, key, nonce, aad, data, data.size)

    private fun gcmRange(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(data, 0, len)
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
