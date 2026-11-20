package com.safewe.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Fingerprint / face, or the screen-lock PIN / pattern / password. */
object BiometricGate {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun isAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(AUTHENTICATORS).build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    fun strongBiometricAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Prompt bound to a Keystore cipher: the key can only be used after a STRONG biometric succeeds.
     * Returns the authorised cipher, or null if cancelled / failed.
     */
    suspend fun authenticateWithCipher(
        activity: FragmentActivity, cipher: javax.crypto.Cipher, title: String, subtitle: String, negative: String
    ): javax.crypto.Cipher? = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(result.cryptoObject?.cipher) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(null) }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle(subtitle).setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG).build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
