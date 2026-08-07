package com.sivanalluri.maclink.companion.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MacDiscoveryManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val nsdManager = applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = ContextCompat.getMainExecutor(applicationContext)

    private val mutableState = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = mutableState.asStateFlow()

    private var running = false
    private var modernCallback: NsdManager.ServiceInfoCallback? = null
    private var legacyListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return

        running = true
        mutableState.value = DiscoveryUiState(status = DiscoveryStatus.STARTING)
        acquireMulticastLock()

        try {
            if (Build.VERSION.SDK_INT >= 37) {
                startModernDiscovery()
            } else {
                startLegacyDiscovery()
            }
        } catch (exception: SecurityException) {
            fail("Local network permission is required.")
        } catch (exception: RuntimeException) {
            fail(exception.message ?: "Unable to start local discovery.")
        }
    }

    fun stop() {
        if (!running) return
        running = false

        if (Build.VERSION.SDK_INT >= 37) {
            modernCallback?.let { callback ->
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            }
        }
        modernCallback = null

        legacyListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        legacyListener = null

        releaseMulticastLock()
        mutableState.value = DiscoveryUiState()
    }

    fun markPermissionDenied() {
        stop()
        mutableState.value = DiscoveryUiState(
            status = DiscoveryStatus.PERMISSION_DENIED,
            errorMessage = "Allow Nearby devices access so MacLink can find your Mac.",
        )
    }

    @RequiresApi(37)
    private fun startModernDiscovery() {
        val request = DiscoveryRequest.Builder(MacLinkServiceContract.SERVICE_TYPE).build()
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                fail("Discovery registration failed ($errorCode).")
            }

            override fun onServiceInfoCallbackRegistered() {
                if (running) updateStatus(DiscoveryStatus.SEARCHING)
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                serviceInfo.toDiscoveredMac()?.let(::upsert)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                remove(serviceInfo.serviceName)
            }

            override fun onServiceLost() = Unit

            override fun onServiceInfoCallbackUnregistered() = Unit
        }

        modernCallback = callback
        nsdManager.registerServiceInfoCallback(request, callbackExecutor, callback)
    }

    @Suppress("DEPRECATION")
    private fun startLegacyDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (running) updateStatus(DiscoveryStatus.SEARCHING)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.startsWith("_maclink._tcp")) return
                resolveLegacy(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                remove(serviceInfo.serviceName)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                fail("Discovery failed to start ($errorCode).")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                fail("Discovery failed to stop ($errorCode).")
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
        }

        legacyListener = listener
        nsdManager.discoverServices(
            MacLinkServiceContract.SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(serviceInfo: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                callbackExecutor.execute {
                    serviceInfo.toDiscoveredMac()?.let(::upsert)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            nsdManager.resolveService(serviceInfo, callbackExecutor, listener)
        } else {
            nsdManager.resolveService(serviceInfo, listener)
        }
    }

    private fun NsdServiceInfo.toDiscoveredMac(): DiscoveredMac? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) {
            hostAddresses.mapNotNull { it.hostAddress }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(host?.hostAddress)
        }

        return MacLinkServiceContract.parse(
            serviceName = serviceName,
            port = port,
            addresses = addresses,
            attributes = attributes,
        )
    }

    private fun upsert(service: DiscoveredMac) {
        if (!running) return
        val services = mutableState.value.services
            .filterNot { it.deviceId == service.deviceId }
            .plus(service)
            .sortedBy { it.displayName.lowercase() }
        mutableState.value = mutableState.value.copy(services = services, errorMessage = null)
    }

    private fun remove(serviceName: String) {
        if (!running) return
        mutableState.value = mutableState.value.copy(
            services = mutableState.value.services.filterNot { it.serviceName == serviceName },
        )
    }

    private fun updateStatus(status: DiscoveryStatus) {
        mutableState.value = mutableState.value.copy(status = status, errorMessage = null)
    }

    private fun fail(message: String) {
        running = false
        modernCallback = null
        legacyListener = null
        releaseMulticastLock()
        mutableState.value = DiscoveryUiState(
            status = DiscoveryStatus.ERROR,
            errorMessage = message,
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireMulticastLock() {
        multicastLock = wifiManager.createMulticastLock("maclink-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
    }
}
