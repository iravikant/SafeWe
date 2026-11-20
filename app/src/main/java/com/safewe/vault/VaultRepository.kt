package com.safewe.vault

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.LruCache
import androidx.fragment.app.FragmentActivity
import com.safewe.security.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VaultState {
    data object NotSetUp : VaultState
    data class Locked(val lockedUntil: Long, val biometric: Boolean) : VaultState
    data object Unlocked : VaultState
}

sealed interface UnlockResult {
    data object Ok : UnlockResult
    data class Wrong(val failures: Int, val lockedUntil: Long) : UnlockResult
    data class LockedOut(val until: Long) : UnlockResult
    data object NotSetUp : UnlockResult
}

class ImportResult(val imported: Int, val failed: Int, val sources: List<Uri>)

/** An open, seekable, decrypted view of one vault file. Close it to wipe its key copy. */
class VaultHandle(val reader: FileCrypto.RandomAccessReader, private val raf: RandomAccessFile) : Closeable {
    override fun close() { runCatching { reader.close() }; runCatching { raf.close() } }
}

/**
 * The vault. Everything lives in the app-private Context.filesDir/vault:
 *   - random file names (never the original name), content encrypted with FileCrypto (SWEF),
 *   - an encrypted index (folders, names, mime types), encrypted thumbnails,
 *   - no MediaStore insertion, no exported ContentProvider, no cache copies of plaintext, no cloud backup.
 * The vault key exists in memory only while unlocked. Nothing in the parent-monitoring code reads this class.
 */
@Singleton
class VaultRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("safewe_vault", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "vault").apply { mkdirs() }
    private val keys = VaultKeys()
    private val mutex = Mutex()
    private val random = SecureRandom()
    @Volatile private var vmk: ByteArray? = null

    private val _state = MutableStateFlow<VaultState>(initialState())
    private val _index = MutableStateFlow(VaultIndex())
    val state: StateFlow<VaultState> = _state
    val index: StateFlow<VaultIndex> = _index
    val thumbs = LruCache<String, Bitmap>(64)

    // auto-lock bookkeeping (monotonic clock)
    @Volatile private var lastTouch = 0L
    @Volatile private var backgroundedAt = 0L
    @Volatile private var externalUi = false

    var autoLockSeconds: Int
        get() = prefs.getInt("autolock", 30)
        set(v) { prefs.edit().putInt("autolock", v).apply() }

    // ------------------------------------------------------------------ lock state

    private fun initialState(): VaultState =
        if (!prefs.contains("wrap")) VaultState.NotSetUp else VaultState.Locked(prefs.getLong("lockUntil", 0), prefs.contains("bio"))

    fun biometricEnabled() = prefs.contains("bio")

    suspend fun setup(secret: String, kind: SecretKind) = withContext(Dispatchers.Default) {
        check(!prefs.contains("wrap")) { "A vault already exists." }
        CredentialRules.validate(kind, secret)?.let { throw IllegalArgumentException(it) }
        val salt = ByteArray(16).also(random::nextBytes)
        val kek = VaultCrypto.kek(secret.toCharArray(), salt, VaultCrypto.ITERATIONS, keys.pepper())
        val v = VaultCrypto.newVmk()
        try {
            prefs.edit().putString("kind", kind.name).putString("salt", B64.enc(salt)).putInt("iters", VaultCrypto.ITERATIONS)
                .putString("wrap", B64.enc(VaultCrypto.wrap(v, kek))).putInt("fails", 0).putLong("lockUntil", 0).commit()
        } finally { kek.fill(0) }
        vmk = v
        _index.value = VaultIndex()
        writeIndex(_index.value)
        _state.value = VaultState.Unlocked
        touch()
    }

    fun secretKind(): SecretKind = runCatching { SecretKind.valueOf(prefs.getString("kind", "PIN")!!) }.getOrDefault(SecretKind.PIN)

    suspend fun unlock(secret: String): UnlockResult = withContext(Dispatchers.Default) {
        if (!prefs.contains("wrap")) return@withContext UnlockResult.NotSetUp
        val now = System.currentTimeMillis()
        val until = prefs.getLong("lockUntil", 0)
        if (until > now) return@withContext UnlockResult.LockedOut(until)

        val kek = VaultCrypto.kek(secret.toCharArray(), B64.dec(prefs.getString("salt", "")!!), prefs.getInt("iters", VaultCrypto.ITERATIONS), keys.pepper())
        val v = try { VaultCrypto.unwrap(B64.dec(prefs.getString("wrap", "")!!), kek) } finally { kek.fill(0) }
        if (v == null) {
            val f = prefs.getInt("fails", 0) + 1
            val lu = LockoutPolicy.lockedUntil(f, now)
            prefs.edit().putInt("fails", f).putLong("lockUntil", lu).commit()
            _state.value = VaultState.Locked(lu, biometricEnabled())
            UnlockResult.Wrong(f, lu)
        } else { startSession(v); UnlockResult.Ok }
    }

    suspend fun enableBiometric(activity: FragmentActivity): Boolean {
        val v = vmk ?: return false
        val cipher = runCatching { keys.encryptCipher() }.getOrNull() ?: return false
        val ready = BiometricGate.authenticateWithCipher(activity, cipher, "Turn on biometric unlock", "Confirm with your fingerprint or face", "Cancel") ?: return false
        val ct = runCatching { ready.doFinal(v) }.getOrNull() ?: return false
        prefs.edit().putString("bio", B64.enc(ready.iv + ct)).commit()
        return true
    }

    suspend fun unlockWithBiometric(activity: FragmentActivity): Boolean {
        val blob = prefs.getString("bio", null)?.let(B64::dec) ?: return false
        if (blob.size <= 12) return false
        val cipher = try { keys.decryptCipher(blob.copyOfRange(0, 12)) }
        catch (e: KeyPermanentlyInvalidatedException) { disableBiometric(); return false }
        catch (e: Exception) { return false }
        val ready = BiometricGate.authenticateWithCipher(activity, cipher, "Unlock private space", "Confirm with your fingerprint or face", "Use PIN / password") ?: return false
        val v = runCatching { ready.doFinal(blob, 12, blob.size - 12) }.getOrNull() ?: return false
        withContext(Dispatchers.Default) { startSession(v) }
        return true
    }

    fun disableBiometric() {
        prefs.edit().remove("bio").commit(); keys.deleteBioKey()
        (_state.value as? VaultState.Locked)?.let { _state.value = it.copy(biometric = false) }
    }

    private fun startSession(v: ByteArray) {
        vmk = v
        prefs.edit().putInt("fails", 0).putLong("lockUntil", 0).commit()
        _index.value = readIndex()
        _state.value = VaultState.Unlocked
        touch()
    }

    /** Wipes the key and every decrypted thing held in memory. */
    fun lock() {
        vmk?.fill(0); vmk = null
        _index.value = VaultIndex()
        thumbs.evictAll()
        if (_state.value == VaultState.Unlocked) _state.value = VaultState.Locked(prefs.getLong("lockUntil", 0), biometricEnabled())
    }

    // ------------------------------------------------------------------ auto lock

    fun touch() { lastTouch = SystemClock.elapsedRealtime() }

    /** Call before launching a file picker / system dialog so leaving the app for it does not lock the vault. */
    fun expectExternalUi() { externalUi = true }

    fun onBackgrounded() {
        if (externalUi || _state.value != VaultState.Unlocked) return
        backgroundedAt = SystemClock.elapsedRealtime()
        if (autoLockSeconds == 0) lock()
    }

    fun onForegrounded() {
        externalUi = false
        val bg = backgroundedAt; backgroundedAt = 0
        if (bg > 0 && _state.value == VaultState.Unlocked && SystemClock.elapsedRealtime() - bg >= autoLockSeconds * 1000L) lock()
    }

    /** Foreground inactivity check, called a few times a minute by the UI. */
    fun tick() {
        if (_state.value == VaultState.Unlocked && SystemClock.elapsedRealtime() - lastTouch >= IDLE_MS) lock()
    }

    // ------------------------------------------------------------------ index persistence

    private fun requireKey(): ByteArray = vmk ?: throw IllegalStateException("The vault is locked.")
    private val wrap: (ByteArray) -> ByteArray get() { val k = requireKey(); return { AesGcm.encrypt(k, it, KEY_AAD) } }
    private val unwrap: (ByteArray) -> ByteArray get() { val k = requireKey(); return { AesGcm.decrypt(k, it, KEY_AAD) } }
    private fun indexFile() = File(dir, "index.v")

    private fun readIndex(): VaultIndex {
        val f = indexFile(); if (!f.exists()) return VaultIndex()
        return try { VaultIndex.parse(AesGcm.decrypt(requireKey(), f.readBytes(), INDEX_AAD)) }
        catch (e: Exception) { f.renameTo(File(dir, "index.corrupt")); VaultIndex() } // keep the bad file, never overwrite it silently
    }

    private fun writeIndex(idx: VaultIndex) {
        val tmp = File(dir, "index.tmp")
        tmp.writeBytes(AesGcm.encrypt(requireKey(), idx.serialize(), INDEX_AAD))
        if (!tmp.renameTo(indexFile())) { indexFile().delete(); check(tmp.renameTo(indexFile())) { "Could not save the vault index." } }
    }

    private suspend fun mutate(f: (VaultIndex) -> VaultIndex) = mutex.withLock {
        val next = f(_index.value)
        writeIndex(next)          // persist first; only publish if it was saved
        _index.value = next
    }

    // ------------------------------------------------------------------ folders & items

    suspend fun createFolder(name: String): String { val id = UUID.randomUUID().toString(); mutate { it.addFolder(id, name) }; return id }
    suspend fun renameFolder(id: String, name: String) = mutate { it.renameFolder(id, name) }
    suspend fun deleteFolder(id: String) = mutate { it.removeFolder(id) }
    suspend fun moveItem(id: String, folderId: String?) = mutate { it.moveItem(id, folderId) }

    suspend fun deleteItem(item: VaultItem) {
        mutate { it.removeItem(item.id) }
        withContext(Dispatchers.IO) { fileOf(item.file)?.delete(); item.thumb?.let { fileOf(it)?.delete() } }
        thumbs.remove(item.id)
    }

    suspend fun importFiles(uris: List<Uri>, folderId: String?, onProgress: (Int, Int) -> Unit): ImportResult = withContext(Dispatchers.IO) {
        var ok = 0; var failed = 0; val done = mutableListOf<Uri>()
        uris.forEachIndexed { i, u ->
            onProgress(i, uris.size)
            val target = File(dir, randomName()); var thumbFile: File? = null
            try {
                val (name, size) = nameAndSize(u)
                val mime = ctx.contentResolver.getType(u) ?: "application/octet-stream"
                val meta = FileMeta(name.take(255), mime, size, System.currentTimeMillis())
                val w = wrap; val un = unwrap
                val enc = ctx.contentResolver.openInputStream(u).orFail().use { inp ->
                    BufferedOutputStream(target.outputStream()).use { out -> FileCrypto.encrypt(BufferedInputStream(inp), out, meta, w) }
                }
                val check = BufferedInputStream(target.inputStream()).use { FileCrypto.decrypt(it, NullOut, un) }
                if (check.plainSha256 != enc.plainSha256) throw CorruptFileException()

                val thumbName = Thumbnailer.make(ctx, u, mime, size)?.let { bytes ->
                    File(dir, randomName()).also { thumbFile = it }.also { f ->
                        BufferedOutputStream(f.outputStream()).use { FileCrypto.encrypt(bytes.inputStream(), it, FileMeta("t", "image/jpeg", bytes.size.toLong(), 0), w) }
                    }.name
                }
                mutate { it.addItem(VaultItem(UUID.randomUUID().toString(), target.name, thumbName, name.take(255), mime, enc.plainBytes, meta.createdAt, folderId)) }
                ok++; done += u
            } catch (e: Exception) { target.delete(); thumbFile?.delete(); failed++ }
        }
        onProgress(uris.size, uris.size)
        ImportResult(ok, failed, done)
    }

    /** Only works where the picker gave write access. Returns how many were deleted. */
    suspend fun deleteOriginals(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        uris.count { runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, it) }.getOrDefault(false) }
    }

    suspend fun export(item: VaultItem, dst: Uri) = withContext(Dispatchers.IO) {
        val f = fileOf(item.file) ?: throw FileFormatException("The file is missing.")
        try {
            BufferedInputStream(f.inputStream()).use { i ->
                ctx.contentResolver.openOutputStream(dst, "wt").orFail().use { o -> FileCrypto.decrypt(i, BufferedOutputStream(o), unwrap) }
            }
        } catch (e: Exception) { runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, dst) }; throw e }
    }

    // ------------------------------------------------------------------ secure preview

    fun openHandle(item: VaultItem): VaultHandle {
        val f = fileOf(item.file) ?: throw FileFormatException("The file is missing.")
        val raf = RandomAccessFile(f, "r")
        return try {
            val src = object : FileCrypto.RandomSource {
                override val length: Long get() = raf.length()
                @Synchronized override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int { raf.seek(pos); return raf.read(buf, off, len) }
            }
            VaultHandle(FileCrypto.openRandomAccess(src, unwrap), raf)
        } catch (e: Exception) { runCatching { raf.close() }; throw e }
    }

    /** A seekable file descriptor whose bytes are decrypted on demand; no plaintext is ever written to disk. */
    fun openProxyFd(item: VaultItem): ParcelFileDescriptor = VaultProxyFd(ctx, openHandle(item)).open()

    suspend fun readBytes(item: VaultItem, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        openHandle(item).use { h ->
            val size = h.reader.plainSize
            if (size > maxBytes) throw FileFormatException("This file is too large to preview. Export it instead.")
            ByteArray(size.toInt()).also { out ->
                var p = 0
                while (p < out.size) { val n = h.reader.readAt(p.toLong(), out, p, out.size - p); if (n <= 0) break; p += n }
            }
        }
    }

    suspend fun thumbnail(item: VaultItem): Bitmap? {
        thumbs.get(item.id)?.let { return it }
        val name = item.thumb ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val f = fileOf(name) ?: return@runCatching null
                val out = java.io.ByteArrayOutputStream()
                BufferedInputStream(f.inputStream()).use { FileCrypto.decrypt(it, out, unwrap) }
                ImageDecode.decode(out.toByteArray(), 256)
            }.getOrNull()?.also { thumbs.put(item.id, it) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun fileOf(name: String): File? = if (FILE_NAME.matches(name)) File(dir, name).takeIf { it.exists() } else null // no path tricks
    private fun randomName() = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } + ".v"

    private fun nameAndSize(u: Uri): Pair<String, Long> =
        runCatching {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) (it.getString(0) ?: "file") to (if (it.isNull(1)) -1L else it.getLong(1)) else null
            }
        }.getOrNull() ?: ("file" to -1L)

    private fun <T> T?.orFail(): T = this ?: throw FileFormatException("The file could not be opened.")

    private object NullOut : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private companion object {
        val KEY_AAD = "SWEF-vault-key-v1".toByteArray()
        val INDEX_AAD = "SWVI-v1".toByteArray()
        val FILE_NAME = Regex("[0-9a-f]{32}\\.v")
        const val IDLE_MS = 2 * 60_000L
    }
}
