package com.safewe.camera

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CameraSessionViewModel @Inject constructor(coordinator: CaptureCoordinator) : ViewModel() {
    val active = coordinator.sessionActive
}
