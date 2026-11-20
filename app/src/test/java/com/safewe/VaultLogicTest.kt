package com.safewe

import com.safewe.vault.*
import org.junit.Assert.*
import org.junit.Test

class VaultLogicTest {
    // ---- lockout
    @Test fun noDelayForTheFirstFourFailures() { for (f in 0..4) assertEquals(0L, LockoutPolicy.delayMs(f)) }
    @Test fun delaysEscalateAndCap() {
        val d = (5..12).map { LockoutPolicy.delayMs(it) }
        assertEquals(d, d.sorted()); assertEquals(30_000L, d.first()); assertEquals(3_600_000L, d.last())
    }
    @Test fun lockedUntilIsNowPlusDelay() {
        assertEquals(0L, LockoutPolicy.lockedUntil(3, 1000)); assertEquals(31_000L, LockoutPolicy.lockedUntil(5, 1000))
    }

    // ---- credential rules
    @Test fun pinRules() {
        assertNull(CredentialRules.validate(SecretKind.PIN, "493021"))
        assertNotNull(CredentialRules.validate(SecretKind.PIN, "12345"))        // too short
        assertNotNull(CredentialRules.validate(SecretKind.PIN, "1234567890123")) // too long
        assertNotNull(CredentialRules.validate(SecretKind.PIN, "12a456"))
        assertNotNull(CredentialRules.validate(SecretKind.PIN, "111111"))
        assertNotNull(CredentialRules.validate(SecretKind.PIN, "123456"))
        assertNotNull(CredentialRules.validate(SecretKind.PIN, "987654"))
    }
    @Test fun passwordRules() {
        assertNull(CredentialRules.validate(SecretKind.PASSWORD, "correct horse"))
        assertNotNull(CredentialRules.validate(SecretKind.PASSWORD, "short"))
        assertNotNull(CredentialRules.validate(SecretKind.PASSWORD, "aaaaaaaaaa"))
        assertNotNull(CredentialRules.validate(SecretKind.PASSWORD, "        "))
    }

    // ---- key wrapping (low iteration count keeps the test fast)
    private val salt = ByteArray(16) { it.toByte() }
    private val pepper = ByteArray(32) { (it * 3).toByte() }
    private fun kek(s: String, p: ByteArray = pepper, salt: ByteArray = this.salt) = VaultCrypto.kek(s.toCharArray(), salt, 1000, p)

    @Test fun rightSecretUnwraps() {
        val vmk = VaultCrypto.newVmk()
        assertArrayEquals(vmk, VaultCrypto.unwrap(VaultCrypto.wrap(vmk, kek("493021")), kek("493021")))
    }
    @Test fun wrongSecretFails() = assertNull(VaultCrypto.unwrap(VaultCrypto.wrap(VaultCrypto.newVmk(), kek("493021")), kek("493022")))
    @Test fun wrongDevicePepperFails() {
        val blob = VaultCrypto.wrap(VaultCrypto.newVmk(), kek("493021"))
        assertNull(VaultCrypto.unwrap(blob, kek("493021", ByteArray(32))))
    }
    @Test fun differentSaltGivesDifferentKek() = assertFalse(kek("493021").contentEquals(kek("493021", salt = ByteArray(16))))
    @Test fun tamperedBlobFails() {
        val blob = VaultCrypto.wrap(VaultCrypto.newVmk(), kek("493021")); blob[blob.size - 1] = (blob.last() + 1).toByte()
        assertNull(VaultCrypto.unwrap(blob, kek("493021")))
    }

    // ---- index
    private fun item(id: String, mime: String = "image/jpeg", folder: String? = null) =
        VaultItem(id, "f$id.v", if (mime.startsWith("image")) "t$id.v" else null, "name $id.ext", mime, 1234, 99L, folder)

    @Test fun indexRoundTrips() {
        val idx = VaultIndex().addFolder("a", "Trips").addItem(item("1", folder = "a")).addItem(item("2", "video/mp4")).addItem(item("3", "application/pdf"))
        val back = VaultIndex.parse(idx.serialize())
        assertEquals(idx.folders, back.folders); assertEquals(idx.items, back.items)
        assertEquals(listOf(ItemKind.PHOTO, ItemKind.VIDEO, ItemKind.DOCUMENT), back.items.map { it.kind })
    }
    @Test fun folderNamesAreValidatedAndUnique() {
        val idx = VaultIndex().addFolder("a", "  School ")
        assertEquals("School", idx.folders.single().name)
        assertThrows(IllegalArgumentException::class.java) { idx.addFolder("b", "school") }
        assertThrows(IllegalArgumentException::class.java) { idx.addFolder("b", "   ") }
        assertThrows(IllegalArgumentException::class.java) { idx.addFolder("b", "x".repeat(41)) }
        assertThrows(IllegalArgumentException::class.java) { idx.addFolder("b", "x").renameFolder("b", "School") }
        assertEquals("Renamed", idx.renameFolder("a", "Renamed").folders.single().name)
    }
    @Test fun deletingAFolderKeepsItsFiles() {
        val idx = VaultIndex().addFolder("a", "A").addItem(item("1", folder = "a")).removeFolder("a")
        assertTrue(idx.folders.isEmpty()); assertNull(idx.items.single().folderId)
    }
    @Test fun movingAndRemovingItems() {
        val idx = VaultIndex().addFolder("a", "A").addItem(item("1")).moveItem("1", "a")
        assertEquals("a", idx.items.single().folderId)
        assertThrows(IllegalArgumentException::class.java) { idx.moveItem("1", "nope") }
        assertThrows(IllegalArgumentException::class.java) { VaultIndex().addItem(item("9", folder = "nope")) }
        assertTrue(idx.removeItem("1").items.isEmpty())
    }
    @Test fun garbageIndexIsRejected() {
        assertThrows(Exception::class.java) { VaultIndex.parse(ByteArray(10)) }
        val ok = VaultIndex().serialize()
        assertThrows(Exception::class.java) { VaultIndex.parse(ok.copyOf(ok.size - 2)) }
    }
}
