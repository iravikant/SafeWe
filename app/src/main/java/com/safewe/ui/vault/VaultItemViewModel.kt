package com.safewe.ui.vault

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.security.FileCryptoException
import com.safewe.vault.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface Viewer {
    data object Loading : Viewer
    class Photo(val bitmap: ImageBitmap) : Viewer
    class Text(val text: String) : Viewer
    class Video(val pfd: ParcelFileDescriptor) : Viewer
    class Pdf(val pfd: ParcelFileDescriptor) : Viewer
    data object Unsupported : Viewer
    class Error(val message: String) : Viewer
}

@HiltViewModel
class VaultItemViewModel @Inject constructor(saved: SavedStateHandle, private val repo: VaultRepository) : ViewModel() {
    private val id: String = checkNotNull(saved["id"])
    val item: StateFlow<VaultItem?> = repo.index.map { i -> i.items.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.index.value.items.firstOrNull { it.id == id })
    val folders: StateFlow<List<VaultFolder>> = repo.index.map { it.folders }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.index.value.folders)
    val state: StateFlow<VaultState> = repo.state

    private val _viewer = MutableStateFlow<Viewer>(Viewer.Loading)
    val viewer: StateFlow<Viewer> = _viewer
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private var pfd: ParcelFileDescriptor? = null

    init {
        load()
        // Locking the vault must immediately drop every open decrypted reader.
        viewModelScope.launch { repo.state.collect { if (it !is VaultState.Unlocked) release() } }
    }

    fun touch() = repo.touch()
    fun expectExternalUi() = repo.expectExternalUi()
    fun clearMessage() { _message.value = null }

    private fun load() = viewModelScope.launch(Dispatchers.IO) {
        val target = item.value ?: return@launch
        _viewer.value = try {
            when {
                target.kind == ItemKind.PHOTO ->
                    ImageDecode.decode(repo.readBytes(target, 48L shl 20), 2048)?.let { Viewer.Photo(it.asImageBitmap()) } ?: Viewer.Error("This image could not be shown.")
                target.kind == ItemKind.VIDEO -> Viewer.Video(repo.openProxyFd(target).also { pfd = it })
                target.mime == "application/pdf" || target.name.endsWith(".pdf", ignoreCase = true) -> Viewer.Pdf(repo.openProxyFd(target).also { pfd = it })
                target.mime.startsWith("text/") -> Viewer.Text(String(repo.readBytes(target, 256L shl 10), Charsets.UTF_8))
                else -> Viewer.Unsupported
            }
        } catch (e: Exception) {
            Viewer.Error(if (e is FileCryptoException) e.message ?: "Could not open this file." else "Could not open this file.")
        }
    }

    fun export(dst: Uri) = viewModelScope.launch {
        val target = item.value ?: return@launch
        _message.value = runCatching { repo.export(target, dst) }
            .fold({ "Saved. That copy is NOT encrypted." }, { if (it is FileCryptoException) it.message else "Could not export the file." })
    }

    fun move(folderId: String?) = viewModelScope.launch {
        val target = item.value ?: return@launch
        runCatching { repo.moveItem(target.id, folderId) }.onFailure { _message.value = it.message }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val target = item.value ?: return@launch
        release()
        runCatching { repo.deleteItem(target) }.onSuccess { onDone() }.onFailure { _message.value = "Could not delete the file." }
    }

    private fun release() { runCatching { pfd?.close() }; pfd = null }
    override fun onCleared() { release() }
}
