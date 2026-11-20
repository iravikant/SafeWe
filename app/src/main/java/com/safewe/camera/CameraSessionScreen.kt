package com.safewe.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.safewe.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSessionScreen(onBack: () -> Unit, vm: CameraSessionViewModel = hiltViewModel()) {
    val active by vm.active.collectAsState()
    val context = LocalContext.current
    fun cameraAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(android.app.NotificationManager::class.java)
            .getNotificationChannel(CameraSessionService.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE

    var cameraGranted by remember { mutableStateOf(cameraAllowed()) }
    var notificationsEnabled by remember { mutableStateOf(notificationsAllowed()) }
    var front by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = notificationsAllowed()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        cameraGranted = cameraAllowed()
        notificationsEnabled = notificationsAllowed()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Camera sharing") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp)) {
            SectionCard {
                Text(if (active) "Camera sharing is on" else "Camera sharing is off",
                    style = MaterialTheme.typography.titleMedium)
                Text("While sharing is on, your paired parent can take a photo even when you leave this screen. " +
                    "SafeWe shows an ongoing notification with a Stop sharing action.")
                if (active) {
                    Button(onClick = { CameraSessionService.stop(context) }) { Text("Stop sharing") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !front, onClick = { front = false }, label = { Text("Rear camera") })
                        FilterChip(selected = front, onClick = { front = true }, label = { Text("Front camera") })
                    }
                    when {
                        !cameraGranted -> Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                            Text("Allow camera")
                        }
                        !notificationsEnabled -> {
                            Text("Enable SafeWe notifications in Android settings to start sharing.")
                            if (Build.VERSION.SDK_INT >= 33) {
                                Button(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                    Text("Allow notifications")
                                }
                            }
                        }
                        else -> Button(onClick = {
                            error = runCatching { CameraSessionService.start(context, front) }.isFailure
                        }) { Text("Start sharing") }
                    }
                    if (error) Text("Camera sharing could not start.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
