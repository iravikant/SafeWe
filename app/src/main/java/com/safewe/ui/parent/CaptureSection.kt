package com.safewe.ui.parent

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.data.remote.Pairing
import com.safewe.transfer.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class CaptureUi(
    val busy: Boolean = false,
    val status: String? = null,
    val image: ImageBitmap? = null,
    val preview: ReceivedPreview? = null,
    val error: String? = null
)

@HiltViewModel
class CaptureViewModel @Inject constructor(private val transfers: FirestorePreviewTransferManager) : ViewModel() {
    private val _s = MutableStateFlow(CaptureUi())
    val state: StateFlow<CaptureUi> = _s

    private sealed interface Outcome {
        data class Ready(val meta: TransferMeta) : Outcome
        data class Stopped(val status: String) : Outcome
    }

    fun dismiss() { _s.value = CaptureUi() }

    fun request(child: Pairing, type: String) = viewModelScope.launch {
        _s.value = CaptureUi(busy = true, status = CaptureLabels.parent(TransferStatus.REQUESTED))
        val uid = child.childUid
        var requestId: String? = null
        try {
            requestId = transfers.requestCapture(uid, type)
            val outcome = withTimeoutOrNull(WAIT_MS) {
                merge(
                    transfers.observeTransfer(uid, requestId).filterNotNull()
                        .filter { it.status == TransferStatus.READY }.map<TransferMeta, Outcome> { Outcome.Ready(it) },
                    transfers.observeRequest(uid, requestId).filterNotNull()
                        .onEach { r -> _s.update { it.copy(status = CaptureLabels.parent(r.status, type)) } }
                        .filter { it.status in CaptureLabels.TERMINAL }.map<CaptureRequest, Outcome> { Outcome.Stopped(it.status) }
                ).first()
            }
            when (outcome) {
                is Outcome.Ready -> {
                    _s.update { it.copy(status = "Decrypting…") }
                    val received = transfers.receivePreview(uid, outcome.meta, child)
                    val bmp = BitmapFactory.decodeByteArray(received.bytes, 0, received.bytes.size)
                        ?: error("The image could not be read")
                    // (Black areas are normal for protected content such as banking apps.)
                    _s.value = CaptureUi(image = bmp.asImageBitmap(), preview = received)
                }
                is Outcome.Stopped -> _s.value = CaptureUi(error = CaptureLabels.parent(outcome.status, type))
                null -> _s.value = CaptureUi(error = if (type == CaptureType.SCREENSHOT) "No response. Screen sharing may not be on." else "No response. Camera sharing may be off.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            _s.value = CaptureUi(error = e.message ?: "Request failed")
        } finally {
            requestId?.let { transfers.deleteRequest(uid, it) }
        }
    }

    private companion object { const val WAIT_MS = 4 * 60_000L }
}

@Composable
fun CaptureSection(child: Pairing, vm: CaptureViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val keysReady = child.parentAgreementKey.isNotBlank() && child.childSigningKey.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { vm.request(child, CaptureType.CAMERA) }, enabled = !st.busy && keysReady,
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Photo") }
            FilledTonalButton(
                onClick = { vm.request(child, CaptureType.SCREENSHOT) }, enabled = !st.busy && keysReady,
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Icon(Icons.Filled.Smartphone, null); Spacer(Modifier.width(6.dp)); Text("Screenshot") }
        }
        if (st.busy) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                Text(st.status ?: "Working…", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (st.image == null && st.error == null) {
            Text(
                "Photos require camera sharing. Screenshots require " +
                    "screen sharing on. Protected content (like banking apps) appears black.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
            )
        }
        st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        CaptureResult(st.copy(error = null), vm::dismiss)
    }
}

@Composable
fun CaptureResult(st: CaptureUi, onDismiss: () -> Unit) {
    st.error?.let { com.safewe.ui.ErrorBanner(it) }
    val image = st.image ?: return
    val preview = st.preview ?: return
    key(preview.requestId) {
        val resolver = LocalContext.current.contentResolver
        val scope = rememberCoroutineScope()
        val extension = if (preview.mime == "image/webp") "webp" else "jpg"
        val label = if (preview.type == CaptureType.SCREENSHOT) "screenshot" else "photo"
        val contract = remember(preview.mime) { ActivityResultContracts.CreateDocument(preview.mime) }
        var saving by remember { mutableStateOf(false) }
        var savedMessage by remember { mutableStateOf<String?>(null) }
        var saveFailed by remember { mutableStateOf(false) }
        val download = rememberLauncherForActivityResult(contract) { uri ->
            if (uri != null) {
                saving = true
                savedMessage = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val output = resolver.openOutputStream(uri, "wt")
                                ?: error("Could not open the destination")
                            output.use { it.write(preview.bytes) }
                        }
                        saveFailed = false
                        savedMessage = "Saved to the selected location."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        saveFailed = true
                        savedMessage = "Could not save the image."
                    } finally {
                        saving = false
                    }
                }
            }
        }
        com.safewe.ui.SectionCard {
            Image(image, "Received $label", Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small), contentScale = ContentScale.FillWidth)
            com.safewe.ui.GradientButton(
                if (saving) "Saving…" else "Download $label",
                onClick = { download.launch("SafeWe-$label-${System.currentTimeMillis()}.$extension") },
                modifier = Modifier.fillMaxWidth(), enabled = !saving
            )
            savedMessage?.let {
                Text(it, color = if (saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Dismiss preview") }
        }
    }
}
