package com.safewe.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.safewe.ui.SectionCard
import com.safewe.ui.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSessionScreen(onBack: () -> Unit, vm: CameraSessionViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var front by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.setActive(granted) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.setActive(false) }
    LaunchedEffect(granted) { vm.setActive(granted) }
    DisposableEffect(Unit) { onDispose { vm.setCapture(null); vm.setActive(false) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Camera session") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "End session") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!granted) {
                SectionCard {
                    Text("Camera access needed", style = MaterialTheme.typography.titleMedium)
                    Text("Allow the camera so you can answer your parent's photo requests while this screen is open.", color = MaterialTheme.colorScheme.secondary)
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Allow camera") }
                }
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(MaterialTheme.shapes.medium).background(Color.Black)) {
                    CameraPreview(front, onCapture = vm::setCapture, modifier = Modifier.fillMaxSize())

                    Row(
                        Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0x99000000), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).background(Color(0xFFFF5252), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("Session active", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    FilledTonalIconButton(onClick = { front = !front }, Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        Icon(Icons.Filled.FlipCameraAndroid, "Switch camera")
                    }
                    if (st.countdown > 0) {
                        Column(Modifier.fillMaxSize().background(Color(0x99000000)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${st.countdown}", color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Bold)
                            Text("Your parent asked for a photo", color = Color.White)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = vm::decline, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Cancel") }
                        }
                    }
                }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusPill(if (st.active) "Open" else "Paused", st.active)
                    }
                    Text(st.message, style = MaterialTheme.typography.bodyMedium)
                    st.lastResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                    Text(
                        "While this screen is open, your parent can ask for a photo. You always get a 3-second countdown and can cancel. " +
                            "Leave or lock the screen and the session ends. Android also shows its camera indicator whenever the camera is on.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(front: Boolean, onCapture: (ImageCapture?) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    LaunchedEffect(front) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview, capture)
                onCapture(capture)
            }.onFailure { onCapture(null) }
        }, ContextCompat.getMainExecutor(ctx))
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } }
    }
    AndroidView({ previewView }, modifier)
}
