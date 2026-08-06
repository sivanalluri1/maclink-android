package com.sivanalluri.maclink.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sivanalluri.maclink.companion.connection.ConnectionPhase
import com.sivanalluri.maclink.companion.connection.ConnectionUiState
import com.sivanalluri.maclink.companion.ui.theme.MacLinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MacLinkTheme {
                MacLinkHome()
            }
        }
    }
}

@Composable
private fun MacLinkHome() {
    var state by remember { mutableStateOf(ConnectionUiState()) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (state.phase == ConnectionPhase.STOPPED) {
                    Icons.Outlined.Devices
                } else {
                    Icons.Outlined.Security
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = phaseTitle(state.phase),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = phaseMessage(state.phase),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    state = if (state.phase == ConnectionPhase.STOPPED) {
                        state.copy(phase = ConnectionPhase.DISCOVERING)
                    } else {
                        state.copy(phase = ConnectionPhase.STOPPED)
                    }
                },
            ) {
                Text(if (state.phase == ConnectionPhase.STOPPED) "Pair with a Mac" else "Stop")
            }
        }
    }
}

private fun phaseTitle(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.STOPPED -> "Connect your Mac"
    ConnectionPhase.DISCOVERING -> "Looking for MacLink"
    ConnectionPhase.CONNECTING -> "Connecting"
    ConnectionPhase.AUTHENTICATING -> "Verifying your Mac"
    ConnectionPhase.SYNCING -> "Syncing"
    ConnectionPhase.CONNECTED -> "Connected"
    ConnectionPhase.RECOVERING -> "Reconnecting"
}

private fun phaseMessage(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.STOPPED ->
        "Pair securely over your local network to share notifications, clipboard content, and files."
    ConnectionPhase.DISCOVERING -> "Searching for a Mac that is ready to pair."
    ConnectionPhase.CONNECTING -> "Opening a secure local connection."
    ConnectionPhase.AUTHENTICATING -> "Confirming the paired Mac's identity."
    ConnectionPhase.SYNCING -> "Bringing your devices up to date."
    ConnectionPhase.CONNECTED -> "Your phone is connected securely to MacLink."
    ConnectionPhase.RECOVERING -> "The connection was interrupted. Trying again."
}

@Preview(showBackground = true)
@Composable
private fun MacLinkHomePreview() {
    MacLinkTheme {
        MacLinkHome()
    }
}

