package com.safewe.security

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

class EncryptOutcome(val name: String, val plainBytes: Long, val cipherBytes: Long)
class DecryptOutcome(val name: String, val plainBytes: Long)

/** Storage Access Framework front end for [FileCrypto]. Works with any file type (images, video, audio, PDFs, docs, archives). */
@Singleton
class FileEncryptor @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val resolver get() = ctx.contentResolver
    private val keys = KeystoreKeys("safewe_files_v1")
    private val keyAad = "SWEF-key-v1".toByteArray()

    private val wrap: (ByteArray) -> ByteArray = { AesGcm.encrypt(keys.key(), it, keyAad) }
    private val unwrap: (ByteArray) -> ByteArray = { AesGcm.decrypt(keys.key(), it, keyAad) }

    fun displayName(uri: Uri): String =
        query(uri, OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: "file"

    /** Encrypts [src] into [dst], then re-reads [dst] fully to prove it decrypts to the same bytes. */
    suspend fun encrypt(src: Uri, dst: Uri): EncryptOutcome = withContext(Dispatchers.IO) {
        val name = displayName(src)
        val meta = FileMeta(name, resolver.getType(src) ?: "application/octet-stream", query(src, OpenableColumns.SIZE)?.toLongOrNull() ?: -1L, System.currentTimeMillis())
        try {
            val enc = resolver.openInputStream(src).requireStream().use { i ->
                resolver.openOutputStream(dst, "wt").requireStream().use { o ->
                    FileCrypto.encrypt(BufferedInputStream(i), BufferedOutputStream(o), meta, wrap)
                }
            }
            val verify = resolver.openInputStream(dst).requireStream().use { FileCrypto.decrypt(BufferedInputStream(it), NullOut, unwrap) }
            if (verify.plainSha256 != enc.plainSha256 || verify.plainBytes != enc.plainBytes) throw CorruptFileException()
            EncryptOutcome(name, enc.plainBytes, enc.cipherBytes)
        } catch (e: Exception) {
            deleteQuietly(dst); throw e   // never leave a half-written or unverified output behind
        }
    }

    suspend fun readMeta(src: Uri): FileMeta = withContext(Dispatchers.IO) {
        resolver.openInputStream(src).requireStream().use { FileCrypto.readMeta(BufferedInputStream(it), unwrap) }
    }

    suspend fun decrypt(src: Uri, dst: Uri): DecryptOutcome = withContext(Dispatchers.IO) {
        try {
            val r = resolver.openInputStream(src).requireStream().use { i ->
                resolver.openOutputStream(dst, "wt").requireStream().use { o -> FileCrypto.decrypt(BufferedInputStream(i), BufferedOutputStream(o), unwrap) }
            }
            DecryptOutcome(r.meta.name, r.plainBytes)
        } catch (e: Exception) {
            deleteQuietly(dst); throw e   // authenticated-so-far plaintext must not survive a failed decryption
        }
    }

    /** Only works when the picker granted write access to the original; otherwise returns false. */
    suspend fun deleteOriginal(src: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(resolver, src) }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ helpers
    private fun query(uri: Uri, column: String): String? =
        runCatching { resolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()

    private fun deleteQuietly(uri: Uri) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }

    private fun <T> T?.requireStream(): T = this ?: throw FileFormatException("The file could not be opened.")

    private object NullOut : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
