package com.safewe.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.data.remote.AuthRepository
import com.safewe.ui.ErrorBanner
import com.safewe.ui.GradientButton
import com.safewe.ui.GradientCard
import com.safewe.ui.fieldColors
import com.safewe.ui.theme.Brand
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
    var showPass by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---- brand mark: gradient tile with a soft purple glow behind it
        Box(
            Modifier.size(120.dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(Brand.Purple.copy(alpha = 0.55f), Color.Transparent)), radius = size.minDimension * 0.62f)
            },
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(28.dp)
            Box(
                Modifier.size(88.dp).clip(shape).background(Brand.hero)
                    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.05f))), shape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Shield, null, Modifier.size(44.dp), tint = Color.White) }
        }
        Spacer(Modifier.height(16.dp))
        Text("SafeWe", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Transparent family safety, with everyone's consent.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        GradientCard(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ModeToggle(register) { register = it }
            Text(
                if (register) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.titleLarge
            )
            OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Email, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, colors = fieldColors()
            )
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                trailingIcon = {
                    IconButton({ showPass = !showPass }) {
                        Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showPass) "Hide password" else "Show password")
                    }
                },
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, colors = fieldColors()
            )
            st.error?.let { ErrorBanner(it) }
            GradientButton(
                if (register) "Create account" else "Sign in",
                onClick = { vm.submit(register, email, pass, onAuthed) },
                modifier = Modifier.fillMaxWidth(), loading = st.loading
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
            Text(
                "Photos and screenshots are end-to-end encrypted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/** Pill switch between "Sign in" and "Create account". */
@Composable
private fun ModeToggle(register: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(4.dp)
    ) {
        listOf(false to "Sign in", true to "Create account").forEach { (isRegister, label) ->
            val selected = register == isRegister
            Box(
                Modifier.weight(1f).height(40.dp).clip(CircleShape)
                    .then(if (selected) Modifier.background(Brand.button) else Modifier)
                    .clickable { onChange(isRegister) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
