package com.safewe.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safewe.vault.VaultState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultItemScreen(onBack: () -> Unit, vm: VaultItemViewModel = hiltViewModel()) {
    SecureWindowEffect()
    val item by vm.item.collectAsState()
    val viewer by vm.viewer.collectAsState()
    val state by vm.state.collectAsState()
    val folders by vm.folders.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }

    // Locked (auto-lock, "Lock now") or the item vanished: leave. The viewer already dropped its decrypted readers.
    LaunchedEffect(state, item) { if (state !is VaultState.Unlocked || item == null) onBack() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) vm.export(u) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(item?.name ?: "", maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Move to folder") }, { menu = false; moving = true }, leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) })
                            DropdownMenuItem({ Text("Export a copy") }, { menu = false; confirmExport = true }, leadingIcon = { Icon(Icons.Filled.FileUpload, null) })
                            DropdownMenuItem({ Text("Delete") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp).pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false); vm.touch() }
            }
        ) {
            when (val v = viewer) {
                Viewer.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is Viewer.Photo -> Image(v.bitmap, item?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                is Viewer.Video -> Column(Modifier.verticalScroll(rememberScrollState())) { SecureVideo(v.pfd, onActivity = vm::touch) }
                is Viewer.Pdf -> SecurePdf(v.pfd, onActivity = vm::touch)
                is Viewer.Text -> Column(Modifier.verticalScroll(rememberScrollState())) { Text(v.text) }
                Viewer.Unsupported -> Info("There is no preview for this kind of file. Use “Export a copy” to open it in another app.")
                is Viewer.Error -> Info(v.message, error = true)
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("Delete this file?") },
        text = { Text("It will be removed from your private space for good.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } }
    )
    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false }, title = { Text("Export a copy?") },
        text = { Text("The copy is saved without encryption where you choose, and other apps can read it.") },
        confirmButton = { TextButton({ confirmExport = false; vm.expectExternalUi(); exporter.launch(item?.name ?: "file") }) { Text("Choose where") } },
        dismissButton = { TextButton({ confirmExport = false }) { Text("Cancel") } }
    )
    if (moving) AlertDialog(
        onDismissRequest = { moving = false }, title = { Text("Move to…") },
        text = {
            Column {
                TextButton({ vm.move(null); moving = false }) { Text("No folder (main list)") }
                folders.forEach { f -> TextButton({ vm.move(f.id); moving = false }) { Text(f.name) } }
                if (folders.isEmpty()) Text("Create a folder from the main screen first.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {}, dismissButton = { TextButton({ moving = false }) { Text("Close") } }
    )
}

@Composable
private fun Info(text: String, error: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
    }
}
