package com.anyplayer.android.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

interface SyncNetwork {
    val available: Flow<Boolean>
}

class AndroidSyncNetwork @Inject constructor(@ApplicationContext private val context: Context) : SyncNetwork {
    override val available: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun publish() {
            val active = manager.activeNetwork
            trySend(active != null && manager.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        }
        manager.registerDefaultNetworkCallback(callback)
        publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
