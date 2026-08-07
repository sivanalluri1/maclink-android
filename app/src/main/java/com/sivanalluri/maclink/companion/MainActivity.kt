package com.sivanalluri.maclink.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sivanalluri.maclink.companion.discovery.DiscoveredMac
import com.sivanalluri.maclink.companion.discovery.DiscoveryStatus
import com.sivanalluri.maclink.companion.discovery.DiscoveryUiState
import com.sivanalluri.maclink.companion.discovery.MacDiscoveryManager
import com.sivanalluri.maclink.companion.connection.MacConnectionManager
import com.sivanalluri.maclink.companion.connection.PresenceConnectionState
import com.sivanalluri.maclink.companion.connection.PresenceConnectionStatus
import com.sivanalluri.maclink.companion.ui.theme.MacLinkTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var discoveryManager: MacDiscoveryManager
    private lateinit var connectionManager: MacConnectionManager
    private val pairingScanner by lazy {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discoveryManager = MacDiscoveryManager(this)
        connectionManager = MacConnectionManager(this)
        enableEdgeToEdge()

        setContent {
            val state by discoveryManager.state.collectAsStateWithLifecycle()
            val connectionState by connectionManager.state.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) discoveryManager.start() else discoveryManager.markPermissionDenied()
            }

            MacLinkTheme {
                DiscoveryScreen(
                    state = state,
                    connectionState = connectionState,
                    onStart = {
                        if (requiresLocalNetworkPermission() && !hasLocalNetworkPermission()) {
                            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        } else {
                            discoveryManager.start()
                        }
                    },
                    onStop = discoveryManager::stop,
                    onConnect = connectionManager::connect,
                    onDisconnect = connectionManager::disconnect,
                    onScanPairing = ::scanPairingCode,
                )
            }
        }
    }

    override fun onStop() {
        discoveryManager.stop()
        super.onStop()
    }

    override fun onDestroy() {
        connectionManager.close()
        super.onDestroy()
    }

    private fun requiresLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT >= 37

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED

    private fun scanPairingCode() {
        pairingScanner.startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue
                if (value == null) {
                    connectionManager.reportPairingError("The QR code did not contain pairing data.")
                } else {
                    connectionManager.beginPairing(value)
                }
            }
            .addOnFailureListener { error ->
                connectionManager.reportPairingError(
                    error.message ?: "Unable to open the QR scanner.",
                )
            }
    }
}

@Composable
private fun DiscoveryScreen(
    state: DiscoveryUiState,
    connectionState: PresenceConnectionState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConnect: (DiscoveredMac) -> Unit,
    onDisconnect: () -> Unit,
    onScanPairing: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DiscoveryIcon(state.status)
            Spacer(Modifier.height(24.dp))

            Text(
                text = discoveryTitle(state, connectionState),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = discoveryMessage(state, connectionState),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            if (state.services.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                state.services.take(3).forEach { service ->
                    MacCard(
                        service = service,
                        connectionState = connectionState,
                        onConnect = { onConnect(service) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
            PairingControls(connectionState, onScanPairing)
            if (connectionState.status != PresenceConnectionStatus.IDLE &&
                connectionState.status != PresenceConnectionStatus.ERROR
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                Spacer(Modifier.height(8.dp))
            }
            if (state.isRunning) {
                OutlinedButton(onClick = onStop) { Text("Stop searching") }
            } else {
                Button(onClick = onStart) { Text("Find my Mac") }
            }
        }
    }
}

@Composable
private fun PairingControls(
    connectionState: PresenceConnectionState,
    onScanPairing: () -> Unit,
) {
    when (connectionState.status) {
        PresenceConnectionStatus.DETECTED -> Button(onClick = onScanPairing) {
            Text("Scan Pairing QR")
        }
        PresenceConnectionStatus.PAIRING -> CircularProgressIndicator()
        PresenceConnectionStatus.AWAITING_APPROVAL -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Confirm this code matches your Mac", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                connectionState.verificationCode ?: "------",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                "Approve the phone on your Mac to finish pairing.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        PresenceConnectionStatus.PAIRED -> Text(
            "Secure pairing saved. Encrypted sessions are the next phase.",
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        else -> Unit
    }
}

@Composable
private fun DiscoveryIcon(status: DiscoveryStatus) {
    if (status == DiscoveryStatus.STARTING || status == DiscoveryStatus.SEARCHING) {
        CircularProgressIndicator()
    } else {
        Icon(
            imageVector = if (status == DiscoveryStatus.PERMISSION_DENIED) {
                Icons.Outlined.Devices
            } else {
                Icons.Outlined.Search
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MacCard(
    service: DiscoveredMac,
    connectionState: PresenceConnectionState,
    onConnect: () -> Unit,
) {
    val isSelected = connectionState.selectedMac?.deviceId == service.deviceId
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.Computer, contentDescription = null)
            Spacer(Modifier.height(8.dp))
            Text(service.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${service.addresses.first()}:${service.port}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                enabled = !isSelected || connectionState.status == PresenceConnectionStatus.ERROR,
            ) {
                Text(
                    when {
                        isSelected && connectionState.status == PresenceConnectionStatus.CONNECTING ->
                            "Connecting…"
                        isSelected && connectionState.status == PresenceConnectionStatus.DETECTED ->
                            "Detected by Mac"
                        isSelected && connectionState.status == PresenceConnectionStatus.PAIRING ->
                            "Pairing…"
                        isSelected && connectionState.status == PresenceConnectionStatus.AWAITING_APPROVAL ->
                            "Confirm code"
                        isSelected && connectionState.status == PresenceConnectionStatus.PAIRED ->
                            "Paired"
                        isSelected && connectionState.status == PresenceConnectionStatus.ERROR ->
                            "Try again"
                        else -> "Connect"
                    },
                )
            }
            if (isSelected && connectionState.errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    connectionState.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun discoveryTitle(
    state: DiscoveryUiState,
    connectionState: PresenceConnectionState,
): String = when {
    connectionState.status == PresenceConnectionStatus.DETECTED -> "Phone detected by Mac"
    connectionState.status == PresenceConnectionStatus.PAIRING -> "Verifying Mac"
    connectionState.status == PresenceConnectionStatus.AWAITING_APPROVAL -> "Confirm pairing code"
    connectionState.status == PresenceConnectionStatus.PAIRED -> "Devices paired"
    connectionState.status == PresenceConnectionStatus.CONNECTING -> "Connecting to Mac"
    state.services.isNotEmpty() -> "Mac found"
    state.status == DiscoveryStatus.STARTING -> "Starting discovery"
    state.status == DiscoveryStatus.SEARCHING -> "Looking for MacLink"
    state.status == DiscoveryStatus.PERMISSION_DENIED -> "Nearby access needed"
    state.status == DiscoveryStatus.ERROR -> "Discovery unavailable"
    else -> "Connect your Mac"
}

private fun discoveryMessage(
    state: DiscoveryUiState,
    connectionState: PresenceConnectionState,
): String = when {
    connectionState.status == PresenceConnectionStatus.DETECTED ->
        "${connectionState.selectedMac?.displayName ?: "Your Mac"} can now see " +
            "${connectionState.phoneName ?: "this phone"}. The devices remain unpaired."
    connectionState.status == PresenceConnectionStatus.CONNECTING ->
        "Sending this phone's public device identity to the selected Mac."
    connectionState.status == PresenceConnectionStatus.PAIRING ->
        "Checking the one-time QR secret and both device identities."
    connectionState.status == PresenceConnectionStatus.AWAITING_APPROVAL ->
        "Only approve if the same six-digit code appears on both devices."
    connectionState.status == PresenceConnectionStatus.PAIRED ->
        "The Mac identity is stored and the phone's private key remains in Android Keystore."
    state.errorMessage != null -> state.errorMessage
    state.services.isNotEmpty() -> "Select your Mac so it can detect this phone. Secure pairing comes next."
    state.status == DiscoveryStatus.SEARCHING ->
        "Make sure MacLink is open on your Mac and both devices use the same local network."
    else -> "Find MacLink securely on your local network."
}

@Preview(showBackground = true)
@Composable
private fun DiscoveryScreenPreview() {
    MacLinkTheme {
        DiscoveryScreen(
            state = DiscoveryUiState(
                status = DiscoveryStatus.SEARCHING,
                services = listOf(
                    DiscoveredMac(
                        serviceName = "MacLink – Test Mac",
                        deviceId = UUID.randomUUID(),
                        displayName = "Test Mac",
                        protocolVersion = 1,
                        addresses = listOf("192.168.1.10"),
                        port = 8765,
                        pairingAllowed = true,
                    ),
                ),
            ),
            connectionState = PresenceConnectionState(),
            onStart = {},
            onStop = {},
            onConnect = {},
            onDisconnect = {},
            onScanPairing = {},
        )
    }
}
