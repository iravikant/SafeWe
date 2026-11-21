package com.safewe.ui.vault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safewe.security.BiometricGate
import com.safewe.security.findActivity
import com.safewe.ui.EmptyState
import com.safewe.ui.GradientButton
import com.safewe.ui.GradientProgressIndeterminate
import com.safewe.ui.IconBadge
import com.safewe.ui.fieldColors
import com.safewe.ui.theme.Brand
import com.safewe.ui.SectionCard
import com.safewe.vault.*
import kotlinx.coroutines.delay

@Composable
fun VaultScreen(onBack: () -> Unit, onOpenItem: (String) -> Unit, vm: VaultViewModel = hiltViewModel()) {
    SecureWindowEffect()
    val state by vm.state.collectAsState()
    when (val s = state) {
        VaultState.NotSetUp -> SetupContent(vm, onBack)
        is VaultState.Locked -> UnlockContent(vm, s, onBack)
        VaultState.Unlocked -> HomeContent(vm, onBack, onOpenItem)
    }
}

// ============================================================ setup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupContent(vm: VaultViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var kind by remember { mutableStateOf(SecretKind.PIN) }
    var secret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var local by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { TopAppBar(title = { Text("Create private space") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)) }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard {
                Text("Choose how to lock it", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(SecretKind.PIN to "PIN", SecretKind.PASSWORD to "Password").forEachIndexed { i, (k, l) ->
                        SegmentedButton(kind == k, { kind = k; secret = ""; confirm = ""; local = null }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                    }
                }
                Text(if (kind == SecretKind.PIN) "6 to 12 digits." else "8 or more characters.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                SecretField(secret, { secret = it }, if (kind == SecretKind.PIN) "New PIN" else "New password", kind)
                SecretField(confirm, { confirm = it }, "Repeat it", kind)
                (local ?: ui.unlockError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                GradientButton(
                    "Create private space",
                    onClick = {
                        local = CredentialRules.validate(kind, secret) ?: if (secret != confirm) "The two entries do not match." else null
                        if (local == null) vm.setup(secret, kind)
                    },
                    modifier = Modifier.fillMaxWidth(), loading = ui.busy
                )
            }
            SectionCard {
                Text("Please read", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If you forget this PIN or password, nobody can open your private space, not even SafeWe. There is no reset. " +
                        "The files stay on this phone only: they are lost if you uninstall SafeWe, clear its data or reset the phone. " +
                        "Your parent's monitoring never gives access to this space. Screenshots of it are blocked.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, kind: SecretKind, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value, { onChange(if (kind == SecretKind.PIN) it.filter(Char::isDigit).take(CredentialRules.PIN_MAX) else it.take(CredentialRules.PW_MAX)) },
        label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (kind == SecretKind.PIN) KeyboardType.NumberPassword else KeyboardType.Password),
        modifier = modifier, shape = MaterialTheme.shapes.small, colors = fieldColors()
    )
}

// ============================================================ unlock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockContent(vm: VaultViewModel, s: VaultState.Locked, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val kind = remember { vm.secretKind() }
    var secret by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.lockedUntil) { while (s.lockedUntil > System.currentTimeMillis()) { now = System.currentTimeMillis(); delay(1000) }; now = System.currentTimeMillis() }
    val waitSeconds = ((s.lockedUntil - now) / 1000).coerceAtLeast(0)

    // Offer biometrics straight away if it is set up.
    LaunchedEffect(Unit) { if (s.biometric && waitSeconds == 0L) ctx.findActivity()?.let { vm.unlockWithBiometric(it) } }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { TopAppBar(title = { Text("Private space") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(Icons.Filled.Lock); Spacer(Modifier.width(12.dp)); Text("Locked", style = MaterialTheme.typography.titleMedium) }
                SecretField(secret, { secret = it }, if (kind == SecretKind.PIN) "PIN" else "Password", kind)
                if (waitSeconds > 0) Text("Too many tries. Try again in ${waitSeconds}s.", color = MaterialTheme.colorScheme.error)
                else ui.unlockError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                GradientButton(
                    "Unlock", { vm.unlock(secret); secret = "" },
                    Modifier.fillMaxWidth(), enabled = secret.isNotEmpty() && waitSeconds == 0L, loading = ui.busy
                )
                if (s.biometric) OutlinedButton({ ctx.findActivity()?.let { vm.unlockWithBiometric(it) } }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Use fingerprint or face")
                }
            }
        }
    }
}

// ============================================================ home

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(vm: VaultViewModel, onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val idx by vm.index.collectAsState()
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { while (true) { vm.tick(); delay(10_000) } }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.import(pickedUris(r.data))
    }

    val items = idx.items
        .filter { (ui.kind == null || it.kind == ui.kind) && (ui.folderId == null || it.folderId == ui.folderId) }
        .sortedByDescending { it.addedAt }
    val selectedFolder = idx.folders.firstOrNull { it.id == ui.folderId }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Private space") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton({ vm.lock() }) { Icon(Icons.Filled.Lock, "Lock now") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            Text("Lock automatically", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                            listOf(0 to "When I leave the app", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes").forEach { (sec, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) }, onClick = { vm.setAutoLock(sec); menu = false },
                                    trailingIcon = { if (ui.autoLock == sec) Icon(Icons.Filled.Check, null) }
                                )
                            }
                            HorizontalDivider()
                            if (vm.biometricEnabled() || BiometricGate.strongBiometricAvailable(ctx)) {
                                DropdownMenuItem(
                                    text = { Text(if (vm.biometricEnabled()) "Turn off biometric unlock" else "Turn on biometric unlock") },
                                    onClick = { menu = false; ctx.findActivity()?.let { vm.toggleBiometric(it) } }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    vm.expectExternalUi()
                    importer.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    )
                },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add files") },
                containerColor = Brand.Purple, contentColor = androidx.compose.ui.graphics.Color.White
            )
        }
    ) { pad ->
        // Any touch counts as activity, which postpones the idle auto-lock.
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp).pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false); vm.touch() }
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf<Pair<ItemKind?, String>>(null to "All", ItemKind.PHOTO to "Photos", ItemKind.VIDEO to "Videos", ItemKind.DOCUMENT to "Docs").forEachIndexed { i, (k, l) ->
                    SegmentedButton(ui.kind == k, { vm.setKind(k) }, SegmentedButtonDefaults.itemShape(i, 4)) { Text(l, maxLines = 1) }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(ui.folderId == null, { vm.setFolder(null) }, { Text("All folders") }) }
                items(idx.folders, key = { it.id }) { f ->
                    FilterChip(ui.folderId == f.id, { vm.setFolder(f.id) }, { Text(f.name) }, leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) })
                }
                item { AssistChip({ newFolder = true }, { Text("New folder") }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(18.dp)) }) }
            }
            selectedFolder?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ renameFolder = true }) { Text("Rename “${it.name}”") }
                    TextButton({ deleteFolder = true }) { Text("Delete folder") }
                }
            }
            if (ui.busy) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ui.progress ?: "Working…", style = MaterialTheme.typography.bodyMedium)
                GradientProgressIndeterminate()
            }
            if (items.isEmpty() && !ui.busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Filled.Folder, "Nothing here yet", "Tap “Add files” to move photos, videos or documents into your private space.", Modifier.padding(horizontal = 24.dp))
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(items, key = { it.id }) { item -> ItemCell(item, vm) { onOpenItem(item.id) } }
                }
            }
        }
    }

    if (newFolder) NameDialog("New folder", "", "Create", { newFolder = false }) { vm.createFolder(it); newFolder = false }
    if (renameFolder && selectedFolder != null) NameDialog("Rename folder", selectedFolder.name, "Save", { renameFolder = false }) { vm.renameFolder(selectedFolder.id, it); renameFolder = false }
    if (deleteFolder && selectedFolder != null) AlertDialog(
        onDismissRequest = { deleteFolder = false }, title = { Text("Delete “${selectedFolder.name}”?") },
        text = { Text("Your files are not deleted. They move back to the main list.") },
        confirmButton = { TextButton({ vm.deleteFolder(selectedFolder.id); deleteFolder = false }) { Text("Delete folder") } },
        dismissButton = { TextButton({ deleteFolder = false }) { Text("Cancel") } }
    )
    if (ui.offerDelete.isNotEmpty()) AlertDialog(
        onDismissRequest = vm::dismissOffer, title = { Text("Delete the originals?") },
        text = { Text("The files are now encrypted in your private space. The originals are still where they were, readable by other apps. Delete them? If Android does not allow it, you can delete them yourself.") },
        confirmButton = { TextButton({ vm.deleteOriginals(ui.offerDelete) }) { Text("Delete originals") } },
        dismissButton = { TextButton(vm::dismissOffer) { Text("Keep them") } }
    )
}

private fun pickedUris(data: Intent?): List<Uri> {
    if (data == null) return emptyList()
    val clip = data.clipData
    return if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(data.data)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCell(item: VaultItem, vm: VaultViewModel, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, item.id) { value = vm.thumbnail(item)?.asImageBitmap() }
    Column(
        Modifier.clip(MaterialTheme.shapes.small).combinedClickable(onClick = onClick).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(kindIcon(item.kind), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.secondary)
            if (item.kind == ItemKind.VIDEO && thumb != null) Icon(Icons.Filled.PlayCircle, null, tint = androidx.compose.ui.graphics.Color.White)
        }
        Text(item.name, Modifier.padding(6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun kindIcon(k: ItemKind): ImageVector = when (k) { ItemKind.PHOTO -> Icons.Filled.Image; ItemKind.VIDEO -> Icons.Filled.Videocam; ItemKind.DOCUMENT -> Icons.Filled.Description }

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ onOk(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
