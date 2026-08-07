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
import com.sivanalluri.maclink.companion.ui.theme.MacLinkTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var discoveryManager: MacDiscoveryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discoveryManager = MacDiscoveryManager(this)
        enableEdgeToEdge()

        setContent {
            val state by discoveryManager.state.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) discoveryManager.start() else discoveryManager.markPermissionDenied()
            }

            MacLinkTheme {
                DiscoveryScreen(
                    state = state,
                    onStart = {
                        if (requiresLocalNetworkPermission() && !hasLocalNetworkPermission()) {
                            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        } else {
                            discoveryManager.start()
                        }
                    },
                    onStop = discoveryManager::stop,
                )
            }
        }
    }

    override fun onStop() {
        discoveryManager.stop()
        super.onStop()
    }

    private fun requiresLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT >= 37

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun DiscoveryScreen(
    state: DiscoveryUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DiscoveryIcon(state.status)
            Spacer(Modifier.height(24.dp))

            Text(
                text = discoveryTitle(state),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = discoveryMessage(state),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            if (state.services.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                state.services.take(3).forEach { service ->
                    MacCard(service)
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
            if (state.isRunning) {
                OutlinedButton(onClick = onStop) { Text("Stop searching") }
            } else {
                Button(onClick = onStart) { Text("Find my Mac") }
            }
        }
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
private fun MacCard(service: DiscoveredMac) {
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
        }
    }
}

private fun discoveryTitle(state: DiscoveryUiState): String = when {
    state.services.isNotEmpty() -> "Mac found"
    state.status == DiscoveryStatus.STARTING -> "Starting discovery"
    state.status == DiscoveryStatus.SEARCHING -> "Looking for MacLink"
    state.status == DiscoveryStatus.PERMISSION_DENIED -> "Nearby access needed"
    state.status == DiscoveryStatus.ERROR -> "Discovery unavailable"
    else -> "Connect your Mac"
}

private fun discoveryMessage(state: DiscoveryUiState): String = when {
    state.errorMessage != null -> state.errorMessage
    state.services.isNotEmpty() -> "Your Mac is visible and ready for the pairing phase."
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
            onStart = {},
            onStop = {},
        )
    }
}

