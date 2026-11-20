package com.safewe.vault

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class ItemKind {
    PHOTO, VIDEO, DOCUMENT;
    companion object {
        fun fromMime(m: String) = when { m.startsWith("image/") -> PHOTO; m.startsWith("video/") -> VIDEO; else -> DOCUMENT }
    }
}

data class VaultFolder(val id: String, val name: String)

data class VaultItem(
    val id: String, val file: String, val thumb: String?, val name: String, val mime: String,
    val size: Long, val addedAt: Long, val folderId: String?
) { val kind: ItemKind get() = ItemKind.fromMime(mime) }

/**
 * Folder tree + item list. Persisted ENCRYPTED (AES-GCM under the vault key), so folder names, original
 * file names and which random internal file is which never appear in plaintext on disk.
 */
class VaultIndex(val folders: List<VaultFolder> = emptyList(), val items: List<VaultItem> = emptyList()) {

    fun addFolder(id: String, name: String): VaultIndex {
        val n = cleanFolderName(name)
        require(folders.none { it.name.equals(n, ignoreCase = true) }) { "A folder with that name already exists." }
        return VaultIndex(folders + VaultFolder(id, n), items)
    }

    fun renameFolder(id: String, name: String): VaultIndex {
        val n = cleanFolderName(name)
        require(folders.none { it.id != id && it.name.equals(n, ignoreCase = true) }) { "A folder with that name already exists." }
        return VaultIndex(folders.map { if (it.id == id) it.copy(name = n) else it }, items)
    }

    /** Deleting a folder never deletes files: its items move back to the top level. */
    fun removeFolder(id: String) = VaultIndex(folders.filterNot { it.id == id }, items.map { if (it.folderId == id) it.copy(folderId = null) else it })

    fun addItem(item: VaultItem): VaultIndex {
        require(item.folderId == null || folders.any { it.id == item.folderId }) { "Unknown folder." }
        return VaultIndex(folders, items + item)
    }
    fun removeItem(id: String) = VaultIndex(folders, items.filterNot { it.id == id })

    fun moveItem(id: String, folderId: String?): VaultIndex {
        require(folderId == null || folders.any { it.id == folderId }) { "Unknown folder." }
        return VaultIndex(folders, items.map { if (it.id == id) it.copy(folderId = folderId) else it })
    }

    fun serialize(): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).run {
            write(MAGIC); writeInt(VERSION)
            writeInt(folders.size); folders.forEach { writeUTF(it.id); writeUTF(it.name) }
            writeInt(items.size)
            items.forEach {
                writeUTF(it.id); writeUTF(it.file); optional(it.thumb); writeUTF(it.name); writeUTF(it.mime)
                writeLong(it.size); writeLong(it.addedAt); optional(it.folderId)
            }
        }
        return bos.toByteArray()
    }

    private fun DataOutputStream.optional(s: String?) { writeBoolean(s != null); if (s != null) writeUTF(s) }

    companion object {
        private val MAGIC = byteArrayOf(0x53, 0x57, 0x56, 0x49) // "SWVI"
        private const val VERSION = 1
        private const val MAX_ENTRIES = 200_000

        fun cleanFolderName(raw: String): String {
            val n = raw.trim()
            require(n.isNotEmpty()) { "Give the folder a name." }
            require(n.length <= 40) { "Folder names can be up to 40 characters." }
            return n
        }

        fun parse(bytes: ByteArray): VaultIndex {
            val d = DataInputStream(bytes.inputStream())
            val magic = ByteArray(4); d.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Not a vault index." }
            require(d.readInt() == VERSION) { "Unsupported vault index version." }
            fun opt(): String? = if (d.readBoolean()) d.readUTF() else null
            val fc = d.readInt(); require(fc in 0..MAX_ENTRIES)
            val folders = List(fc) { VaultFolder(d.readUTF(), d.readUTF()) }
            val ic = d.readInt(); require(ic in 0..MAX_ENTRIES)
            val items = List(ic) { VaultItem(d.readUTF(), d.readUTF(), opt(), d.readUTF(), d.readUTF(), d.readLong(), d.readLong(), opt()) }
            return VaultIndex(folders, items)
        }
    }
}
