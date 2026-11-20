package com.safewe.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.data.remote.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthState(val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class AuthViewModel @Inject constructor(private val repo: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state

    fun submit(register: Boolean, email: String, pass: String, onDone: () -> Unit) {
        if (email.isBlank() || pass.length < 6) {
            _state.value = AuthState(error = "Enter a valid email and a password of 6+ characters."); return
        }
        viewModelScope.launch {
            _state.value = AuthState(loading = true)
            runCatching { if (register) repo.register(email, pass) else repo.signIn(email, pass) }
                .onSuccess { _state.value = AuthState(); onDone() }
                .onFailure { _state.value = AuthState(error = it.message ?: "Authentication failed") }
        }
    }
}

@Composable
fun AuthScreen(onAuthed: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Shield, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("SafeWe", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Transparent family safety, with everyone's consent.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small
        )
        st.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { vm.submit(register, email, pass, onAuthed) },
            enabled = !st.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (st.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (register) "Create account" else "Sign in")
        }
        TextButton(onClick = { register = !register }) {
            Text(if (register) "Have an account? Sign in" else "New here? Create account")
        }
    }
}
