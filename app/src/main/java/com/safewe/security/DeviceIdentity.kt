package com.safewe.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class IdentityKeys(val agreement: KeyPair, val signing: KeyPair) {
    val agreementPublicB64: String get() = B64.enc(agreement.public.encoded)
    val signingPublicB64: String get() = B64.enc(signing.public.encoded)
}

/**
 * Long-term EC identity per account: one key pair for ECDH, one for ECDSA.
 * Android Keystore supports ECDH only from API 31 (minSdk is 26), so private keys are generated in
 * software and stored wrapped by a hardware-backed Keystore AES-256-GCM key. They never leave the device.
 */
@Singleton
class DeviceIdentity @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val cache = ConcurrentHashMap<String, IdentityKeys>()
    private val aad = "SafeWe-identity-v1".toByteArray()

    fun load(uid: String): IdentityKeys = cache.getOrPut(uid) { read(uid) ?: create(uid) }

    private fun file(uid: String) = File(ctx.filesDir, "identity_" + uid.filter { it.isLetterOrDigit() } + ".bin")

    private fun create(uid: String): IdentityKeys {
        val keys = IdentityKeys(Ecc.newKeyPair(), Ecc.newKeyPair())
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            listOf(keys.agreement.private.encoded, keys.agreement.public.encoded,
                keys.signing.private.encoded, keys.signing.public.encoded).forEach { o.writeInt(it.size); o.write(it) }
        }
        file(uid).writeBytes(AesGcm.encrypt(KeystoreKeys().key(), bos.toByteArray(), aad))
        return keys
    }

    private fun read(uid: String): IdentityKeys? = runCatching {
        val f = file(uid); if (!f.exists()) return null
        val plain = AesGcm.decrypt(KeystoreKeys().key(), f.readBytes(), aad)
        DataInputStream(ByteArrayInputStream(plain)).use { i ->
            fun next() = ByteArray(i.readInt()).also { i.readFully(it) }
            val ap = next(); val au = next(); val sp = next(); val su = next()
            IdentityKeys(
                KeyPair(Ecc.publicFrom(au), Ecc.privateFrom(ap)),
                KeyPair(Ecc.publicFrom(su), Ecc.privateFrom(sp))
            )
        }
    }.getOrNull()

    /** Called on unpair / sign-out to destroy this device's keys for the account. */
    fun wipe(uid: String) { cache.remove(uid); file(uid).delete() }
}
