package com.safewe.ui.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.security.*
import com.safewe.ui.ErrorBanner
import com.safewe.ui.GradientButton
import com.safewe.ui.GradientProgressIndeterminate
import com.safewe.ui.IconBadge
import com.safewe.ui.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DoneInfo(val title: String, val lines: List<String>, val deletableOriginal: Uri? = null)

data class FilesUi(
    val busy: Boolean = false,
    val error: String? = null,
    val encryptSuggestion: String? = null,
    val decryptSuggestion: String? = null,
    val done: DoneInfo? = null,
    val info: String? = null
)

@HiltViewModel
class FilesViewModel @Inject constructor(private val enc: FileEncryptor) : ViewModel() {
    private val _s = MutableStateFlow(FilesUi())
    val state: StateFlow<FilesUi> = _s
    private var pendingEncrypt: Uri? = null
    private var pendingDecrypt: Uri? = null

    fun fail(msg: String) = _s.update { it.copy(busy = false, error = msg) }

    fun onEncryptPicked(uri: Uri) {
        pendingEncrypt = uri
        _s.value = FilesUi(encryptSuggestion = enc.displayName(uri) + ".swenc")
    }
    fun consumeEncryptSuggestion() = _s.update { it.copy(encryptSuggestion = null) }
    fun cancelPick() { pendingEncrypt = null; pendingDecrypt = null; _s.update { it.copy(encryptSuggestion = null, decryptSuggestion = null) } }

    fun encrypt(dst: Uri) = viewModelScope.launch {
        val src = pendingEncrypt ?: return@launch
        _s.value = FilesUi(busy = true)
        runCatching { enc.encrypt(src, dst) }
            .onSuccess { r ->
                _s.value = FilesUi(done = DoneInfo(
                    "Encrypted and verified",
                    listOf(r.name, "${fmt(r.plainBytes)} → ${fmt(r.cipherBytes)}", "The encrypted copy was fully re-read and matches the original."),
                    deletableOriginal = src
                ))
            }
            .onFailure { _s.value = FilesUi(error = message(it)) }
        pendingEncrypt = null
    }

    /** Called AFTER the user authenticated. */
    fun onDecryptPicked(uri: Uri) = viewModelScope.launch {
        pendingDecrypt = uri
        _s.value = FilesUi(busy = true)
        runCatching { enc.readMeta(uri) }
            .onSuccess { _s.value = FilesUi(decryptSuggestion = it.name.removeSuffix(".swenc")) }
            .onFailure { pendingDecrypt = null; _s.value = FilesUi(error = message(it)) }
    }
    fun consumeDecryptSuggestion() = _s.update { it.copy(decryptSuggestion = null) }

    fun decrypt(dst: Uri) = viewModelScope.launch {
        val src = pendingDecrypt ?: return@launch
        _s.value = FilesUi(busy = true)
        runCatching { enc.decrypt(src, dst) }
            .onSuccess { _s.value = FilesUi(done = DoneInfo("Decrypted", listOf(it.name, fmt(it.plainBytes), "Saved where you chose. That copy is NOT encrypted."))) }
            .onFailure { _s.value = FilesUi(error = message(it)) }
        pendingDecrypt = null
    }

    fun deleteOriginal(uri: Uri) = viewModelScope.launch {
        val ok = enc.deleteOriginal(uri)
        _s.update {
            it.copy(
                done = it.done?.copy(deletableOriginal = null),
                info = if (ok) "The original was deleted." else "Android would not let SafeWe delete it. You can delete it yourself in your Files app."
            )
        }
    }

    private fun message(e: Throwable) = if (e is FileCryptoException) e.message ?: "Could not process the file." else "Something went wrong. Please try again."
    private fun fmt(b: Long) = when {
        b >= 1 shl 30 -> "%.1f GB".format(b / (1 shl 30).toDouble())
        b >= 1 shl 20 -> "%.1f MB".format(b / (1 shl 20).toDouble())
        b >= 1 shl 10 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }
}

private fun openDocument(write: Boolean) = Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit, vm: FilesViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<Uri?>(null) }

    val pickToEncrypt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && u != null) vm.onEncryptPicked(u)
    }
    val saveEncrypted = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        if (u != null) vm.encrypt(u) else vm.cancelPick()
    }
    val pickToDecrypt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = r.data?.data
        if (r.resultCode != Activity.RESULT_OK || u == null) return@rememberLauncherForActivityResult
        val activity = ctx.findActivity()
        scope.launch {
            when {
                activity == null || !BiometricGate.isAvailable(ctx) ->
                    vm.fail("Set up a screen lock (PIN, pattern, password or biometrics) on this phone to decrypt files.")
                BiometricGate.authenticate(activity, "Unlock to decrypt", "Confirm it's you before opening an encrypted file") -> vm.onDecryptPicked(u)
                else -> vm.fail("Authentication was cancelled.")
            }
        }
    }
    val saveDecrypted = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        if (u != null) vm.decrypt(u) else vm.cancelPick()
    }

    LaunchedEffect(st.encryptSuggestion) { st.encryptSuggestion?.let { vm.consumeEncryptSuggestion(); saveEncrypted.launch(it) } }
    LaunchedEffect(st.decryptSuggestion) { st.decryptSuggestion?.let { vm.consumeDecryptSuggestion(); saveDecrypted.launch(it) } }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Encrypt files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard {
                Text("Read this first", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Encrypted files can only be opened by SafeWe on THIS phone. The key lives in the phone's secure hardware and is deleted if you " +
                        "uninstall SafeWe, clear its data or reset the phone. Those files cannot be recovered by anyone, including us. " +
                        "Keep the original somewhere safe if it matters.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
                )
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(Icons.Filled.Lock); Spacer(Modifier.width(12.dp)); Text("Encrypt a file", style = MaterialTheme.typography.titleMedium) }
                Text("Works with photos, videos, audio, PDFs, documents and archives. You choose where the encrypted copy is saved.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                GradientButton("Choose file", { pickToEncrypt.launch(openDocument(write = true)) }, Modifier.fillMaxWidth(), enabled = !st.busy)
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(Icons.Filled.LockOpen); Spacer(Modifier.width(12.dp)); Text("Decrypt a file", style = MaterialTheme.typography.titleMedium) }
                Text("You'll confirm with your fingerprint, face or screen lock first.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                OutlinedButton({ pickToDecrypt.launch(openDocument(write = false)) }, enabled = !st.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Choose encrypted file") }
            }

            if (st.busy) SectionCard {
                Text("Working… large files can take a while.", style = MaterialTheme.typography.bodyMedium)
                GradientProgressIndeterminate()
            }
            st.error?.let { ErrorBanner(it) }
            st.info?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            st.done?.let { d ->
                SectionCard {
                    Text(d.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    d.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    d.deletableOriginal?.let { u -> OutlinedButton({ confirmDelete = u }) { Text("Delete the original…") } }
                }
            }
        }
    }

    confirmDelete?.let { u ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete the original?") },
            text = { Text("The encrypted copy was verified, but it can only be opened on this phone with SafeWe. If you lose it, the file is gone for good.") },
            confirmButton = { TextButton({ confirmDelete = null; vm.deleteOriginal(u) }) { Text("Delete original") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Keep it") } }
        )
    }
}
