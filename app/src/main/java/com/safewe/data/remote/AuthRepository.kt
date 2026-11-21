package com.safewe.data.remote

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(private val auth: FirebaseAuth) {
    val uid: String? get() = auth.currentUser?.uid
    val isSignedIn: Boolean get() = auth.currentUser != null
    val email: String? get() = auth.currentUser?.email

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }
    suspend fun register(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
    }
    fun signOut() = auth.signOut()
}
