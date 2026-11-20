package com.safewe.ui.vault

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.vault.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VaultUi(
    val kind: ItemKind? = null,
    val folderId: String? = null,
    val busy: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val unlockError: String? = null,
    val offerDelete: List<Uri> = emptyList(),
    val autoLock: Int = 30
)

@HiltViewModel
class VaultViewModel @Inject constructor(private val repo: VaultRepository) : ViewModel() {
    val state: StateFlow<VaultState> = repo.state
    val index: StateFlow<VaultIndex> = repo.index
    private val _ui = MutableStateFlow(VaultUi(autoLock = repo.autoLockSeconds))
    val ui: StateFlow<VaultUi> = _ui

    fun secretKind() = repo.secretKind()
    fun biometricEnabled() = repo.biometricEnabled()
    fun touch() = repo.touch()
    fun tick() = repo.tick()
    fun lock() = repo.lock()
    fun expectExternalUi() = repo.expectExternalUi()
    fun clearMessage() = _ui.update { it.copy(message = null) }
    suspend fun thumbnail(item: VaultItem) = repo.thumbnail(item)

    fun setup(secret: String, kind: SecretKind) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, unlockError = null) }
        runCatching { repo.setup(secret, kind) }.onFailure { e -> _ui.update { it.copy(unlockError = e.message) } }
        _ui.update { it.copy(busy = false) }
    }

    fun unlock(secret: String) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, unlockError = null) }
        val kind = if (repo.secretKind() == SecretKind.PIN) "PIN" else "password"
        val err = when (val r = repo.unlock(secret)) {
            UnlockResult.Ok, UnlockResult.NotSetUp -> null
            is UnlockResult.LockedOut -> "Too many tries. Please wait before trying again."
            is UnlockResult.Wrong ->
                if (r.lockedUntil > 0) "Wrong $kind. Too many tries, so there is a short wait now."
                else "Wrong $kind. ${(5 - r.failures).coerceAtLeast(0)} tries left before a wait."
        }
        _ui.update { it.copy(busy = false, unlockError = err) }
    }

    fun unlockWithBiometric(activity: FragmentActivity) = viewModelScope.launch { repo.unlockWithBiometric(activity) }

    fun toggleBiometric(activity: FragmentActivity) = viewModelScope.launch {
        if (repo.biometricEnabled()) { repo.disableBiometric(); say("Biometric unlock is off.") }
        else say(if (repo.enableBiometric(activity)) "Biometric unlock is on. Your PIN or password still works." else "Biometric unlock was not turned on.")
    }

    fun setAutoLock(seconds: Int) { repo.autoLockSeconds = seconds; _ui.update { it.copy(autoLock = seconds) } }
    fun setKind(k: ItemKind?) = _ui.update { it.copy(kind = k) }
    fun setFolder(id: String?) = _ui.update { it.copy(folderId = id) }
    fun dismissOffer() = _ui.update { it.copy(offerDelete = emptyList()) }

    fun import(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _ui.update { it.copy(busy = true, progress = "Encrypting 1 of ${uris.size}…") }
        val r = repo.importFiles(uris, _ui.value.folderId) { done, total -> _ui.update { u -> u.copy(progress = "Encrypting ${minOf(done + 1, total)} of $total…") } }
        _ui.update {
            it.copy(
                busy = false, progress = null, offerDelete = r.sources,
                message = buildString { append("${r.imported} added to your private space."); if (r.failed > 0) append(" ${r.failed} could not be added.") }
            )
        }
    }

    fun deleteOriginals(uris: List<Uri>) = viewModelScope.launch {
        val n = repo.deleteOriginals(uris)
        _ui.update { it.copy(offerDelete = emptyList(), message = if (n == uris.size) "Originals deleted." else "$n of ${uris.size} originals deleted. Android would not allow the rest. You can delete them in your Files or Photos app.") }
    }

    fun createFolder(name: String) = viewModelScope.launch { runCatching { repo.createFolder(name) }.onFailure { say(it.message) } }
    fun renameFolder(id: String, name: String) = viewModelScope.launch { runCatching { repo.renameFolder(id, name) }.onFailure { say(it.message) } }
    fun deleteFolder(id: String) = viewModelScope.launch {
        runCatching { repo.deleteFolder(id) }.onFailure { say(it.message) }
        if (_ui.value.folderId == id) setFolder(null)
    }

    private fun say(m: String?) = _ui.update { it.copy(message = m) }
}
