package com.sivanalluri.maclink.companion.connection

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sivanalluri.maclink.companion.discovery.MacDiscoveryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One connection owner across Activity recreation. Not a background service. */
class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    val discovery = MacDiscoveryManager(application)
    val connection = MacConnectionManager(application)
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private var lastNetwork: Network? = null
    private var lastProperties: LinkProperties? = null
    private var hasObservedNetwork = false
    private var restartDiscovery = false
    private var networkJob: Job? = null
    private var cleared = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (cleared) return
            val changed = hasObservedNetwork && (lastNetwork != network || lastProperties != properties)
            hasObservedNetwork = true
            lastNetwork = network
            lastProperties = properties
            if (!changed) return
            networkJob?.cancel()
            networkJob = viewModelScope.launch {
                delay(500)
                if (discovery.state.value.isRunning) {
                    discovery.stop()
                    discovery.start()
                }
                connection.networkChanged()
            }
        }

        override fun onLost(network: Network) {
            if (cleared || network != lastNetwork) return
            lastNetwork = null
            lastProperties = null
            networkJob?.cancel()
            // Existing transport failure/backoff handles the outage. A new
            // onLinkPropertiesChanged event triggers recovery when a route returns.
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        viewModelScope.launch {
            discovery.state.collect { connection.updateDiscoveredMacs(it.services) }
        }
    }

    fun foreground() {
        if (restartDiscovery) {
            restartDiscovery = false
            discovery.start()
        }
    }

    fun background() {
        restartDiscovery = discovery.state.value.isRunning
        discovery.stop()
    }

    override fun onCleared() {
        cleared = true
        networkJob?.cancel()
        connectivity.unregisterNetworkCallback(callback)
        discovery.stop()
        connection.close()
    }
}
