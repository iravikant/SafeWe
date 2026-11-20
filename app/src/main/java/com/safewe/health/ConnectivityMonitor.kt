package com.safewe.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityMonitor @Inject constructor(@ApplicationContext ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(compute())
    val online: StateFlow<Boolean> = _online
    private var started = false

    private fun compute(): Boolean {
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { _online.value = false }
                override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) {
                    _online.value = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            })
        }
    }
}
