package com.necroware.terminusplayer.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors network transport capabilities without active polling.
 * Emits whether the active network connection is currently cellular data.
 */
@Singleton
class SystemNetworkState @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun checkIsCellular(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    val isCellular: StateFlow<Boolean> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(false)
            return@callbackFlow
        }

        trySend(checkIsCellular())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            }

            override fun onLost(network: Network) {
                trySend(checkIsCellular())
            }

            override fun onAvailable(network: Network) {
                trySend(checkIsCellular())
            }
        }

        cm.registerDefaultNetworkCallback(callback)
        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged().stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = checkIsCellular()
    )
}
