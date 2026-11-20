package com.safewe.vault

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants

/**
 * Exposes a decrypted, seekable, read-only file descriptor backed by [VaultHandle] (StorageManager proxy fd, API 26+).
 * MediaPlayer and PdfRenderer read through it, so the plaintext only ever exists in memory.
 * Releasing the descriptor closes the handle and wipes its key copy.
 */
class VaultProxyFd(private val ctx: Context, private val handle: VaultHandle) {
    fun open(): ParcelFileDescriptor {
        val thread = HandlerThread("safewe-vault-fd").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = handle.reader.plainSize
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                val n = handle.reader.readAt(offset, data, 0, size)
                if (n < 0) 0 else n
            } catch (e: Exception) { throw ErrnoException("onRead", OsConstants.EIO) }
            override fun onRelease() { handle.close(); thread.quitSafely() }
        }
        return try {
            ctx.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
        } catch (e: Exception) { handle.close(); thread.quitSafely(); throw e }
    }
}
