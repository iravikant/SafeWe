package com.safewe.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** UI-visible state of the child's screen-sharing session. */
@Singleton
class ScreenShareState @Inject constructor() {
    private val _active = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    val active: StateFlow<Boolean> = _active
    val message: StateFlow<String?> = _message
    fun set(active: Boolean, message: String? = null) { _active.value = active; _message.value = message }
}
