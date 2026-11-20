package com.safewe.ui.parent

import android.graphics.BitmapFactory
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.data.remote.Pairing
import com.safewe.transfer.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class CaptureUi(
    val busy: Boolean = false,
    val status: String? = null,
    val image: ImageBitmap? = null,   // plaintext exists only in memory and is dropped on dismiss
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
                    _s.value = CaptureUi(image = bmp.asImageBitmap())
                }
                is Outcome.Stopped -> _s.value = CaptureUi(error = CaptureLabels.parent(outcome.status, type))
                null -> _s.value = CaptureUi(error = if (type == CaptureType.SCREENSHOT) "No response. Screen sharing may not be on." else "No response. The child may not have the camera screen open.")
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
                "Your child sees a notice and can cancel each request. Photos need their camera screen open; screenshots need " +
                    "screen sharing on. Protected content (like banking apps) appears black.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
            )
        }
        st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        st.image?.let {
            Image(it, "Image from your child's device", Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small), contentScale = ContentScale.FillWidth)
            TextButton(onClick = vm::dismiss) { Text("Dismiss and delete") }
        }
    }
}

/** Shows the outcome of a photo/screenshot request: an error, or the decrypted image with a delete button. */
@Composable
fun CaptureResult(st: CaptureUi, onDismiss: () -> Unit) {
    st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    st.image?.let {
        com.safewe.ui.SectionCard {
            Image(it, "Image from your child's device", Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small), contentScale = ContentScale.FillWidth)
            TextButton(onClick = onDismiss) { Text("Dismiss and delete") }
        }
    }
}
