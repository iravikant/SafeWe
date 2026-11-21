package com.safewe.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.safewe.camera.CameraSessionScreen
import com.safewe.data.AppRole
import com.safewe.data.RoleStore
import com.safewe.data.remote.AuthRepository
import com.safewe.ui.ScreenBackground
import com.safewe.ui.auth.AuthScreen
import com.safewe.ui.child.ChildShell
import com.safewe.ui.files.FilesScreen
import com.safewe.ui.parent.GeofenceScreen
import com.safewe.ui.parent.ParentShell
import com.safewe.ui.parent.UsageScreen
import com.safewe.ui.role.RoleScreen
import com.safewe.ui.vault.VaultItemScreen
import com.safewe.ui.vault.VaultScreen
import com.safewe.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NavViewModel @Inject constructor(val auth: AuthRepository, val vault: VaultRepository, val roles: RoleStore) : ViewModel()

private fun routeFor(r: AppRole?) = when (r) { AppRole.PARENT -> "parent"; AppRole.CHILD -> "child"; null -> "role" }

@Composable
fun AppNav(openCamera: Boolean = false, onHandled: () -> Unit = {}, vm: NavViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    // Signed in and a mode already chosen: open straight into it.
    val start = remember { if (vm.auth.isSignedIn) routeFor(vm.roles.get()) else "auth" }
    val signOut = { vm.vault.lock(); vm.roles.clear(); vm.auth.signOut(); nav.navigate("auth") { popUpTo(0) } }
    val switchRole = { vm.vault.lock(); vm.roles.clear(); nav.navigate("role") { popUpTo(0) } }

    // Tapping the "parent asked for a photo" notification lands here.
    LaunchedEffect(openCamera) {
        if (openCamera) {
            if (vm.auth.isSignedIn) { nav.navigate("child"); nav.navigate("camera") }
            onHandled()
        }
    }

    ScreenBackground {
    NavHost(nav, startDestination = start) {
        composable("auth") { AuthScreen(onAuthed = { nav.navigate(routeFor(vm.roles.get())) { popUpTo("auth") { inclusive = true } } }) }
        composable("role") {
            RoleScreen(
                onParent = { vm.roles.set(AppRole.PARENT); nav.navigate("parent") { popUpTo("role") { inclusive = true } } },
                onChild = { vm.roles.set(AppRole.CHILD); nav.navigate("child") { popUpTo("role") { inclusive = true } } }
            )
        }
        composable("parent") {
            ParentShell(
                onOpenZones = { nav.navigate("zones/$it") }, onOpenUsage = { nav.navigate("usage/$it") },
                onSwitchRole = switchRole, onSignOut = signOut
            )
        }
        composable("zones/{childUid}", arguments = listOf(navArgument("childUid") { type = NavType.StringType })) { GeofenceScreen(onBack = { nav.popBackStack() }) }
        composable("usage/{childUid}", arguments = listOf(navArgument("childUid") { type = NavType.StringType })) { UsageScreen(onBack = { nav.popBackStack() }) }
        composable("child") {
            ChildShell(
                onSignOut = signOut, onSwitchRole = switchRole, onOpenCamera = { nav.navigate("camera") },
                onOpenFiles = { nav.navigate("files") }, onOpenVault = { nav.navigate("vault") }
            )
        }
        composable("camera") { CameraSessionScreen(onBack = { nav.popBackStack() }) }
        composable("files") { FilesScreen(onBack = { nav.popBackStack() }) }
        composable("vault") { VaultScreen(onBack = { nav.popBackStack() }, onOpenItem = { nav.navigate("vault/item/$it") }) }
        composable("vault/item/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { VaultItemScreen(onBack = { nav.popBackStack() }) }
    }
    }
}
