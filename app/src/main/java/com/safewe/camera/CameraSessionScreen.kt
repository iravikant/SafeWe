package com.safewe.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.safewe.ui.ErrorBanner
import com.safewe.ui.GradientButton
import com.safewe.ui.HeroCard
import com.safewe.ui.SectionCard
import com.safewe.ui.StatusPill

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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Camera sharing") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HeroCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Filled.PhotoCamera, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (active) "Camera sharing is on" else "Camera sharing is off",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "While sharing is on, your paired parent can take a photo even when you leave this screen. " +
                        "SafeWe shows an ongoing notification with a Stop sharing action.",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f)
                )
            }
            SectionCard {
                if (active) {
                    StatusPill("Sharing", true)
                    GradientButton("Stop sharing", onClick = { CameraSessionService.stop(context) }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Choose a camera", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !front, onClick = { front = false }, label = { Text("Rear camera") })
                        FilterChip(selected = front, onClick = { front = true }, label = { Text("Front camera") })
                    }
                    when {
                        !cameraGranted -> GradientButton("Allow camera", onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth())
                        !notificationsEnabled -> {
                            Text("Enable SafeWe notifications in Android settings to start sharing.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                            if (Build.VERSION.SDK_INT >= 33) {
                                GradientButton("Allow notifications", onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        else -> GradientButton("Start sharing", onClick = {
                            error = runCatching { CameraSessionService.start(context, front) }.isFailure
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    if (error) ErrorBanner("Camera sharing could not start.")
                }
            }
        }
    }
}
