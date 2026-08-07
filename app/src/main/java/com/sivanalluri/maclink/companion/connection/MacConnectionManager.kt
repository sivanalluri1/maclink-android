package com.sivanalluri.maclink.companion.connection

import android.content.Context
import com.sivanalluri.maclink.companion.BuildConfig
import com.sivanalluri.maclink.companion.discovery.DiscoveredMac
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.Executors

enum class PresenceConnectionStatus {
    IDLE,
    CONNECTING,
    DETECTED,
    ERROR,
}

data class PresenceConnectionState(
    val status: PresenceConnectionStatus = PresenceConnectionStatus.IDLE,
    val selectedMac: DiscoveredMac? = null,
    val phoneName: String? = null,
    val errorMessage: String? = null,
)

class MacConnectionManager(context: Context) : AutoCloseable {
    private val identityStore = PhoneIdentityStore(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(PresenceConnectionState())
    val state: StateFlow<PresenceConnectionState> = mutableState.asStateFlow()

    @Volatile
    private var generation = 0L

    @Volatile
    private var socket: Socket? = null

    fun connect(mac: DiscoveredMac) {
        val currentGeneration: Long
        synchronized(this) {
            generation += 1
            currentGeneration = generation
            socket?.closeQuietly()
            socket = null
        }

        val phoneName = identityStore.deviceName()
        mutableState.value = PresenceConnectionState(
            status = PresenceConnectionStatus.CONNECTING,
            selectedMac = mac,
            phoneName = phoneName,
        )

        executor.execute {
            connect(currentGeneration, mac, phoneName)
        }
    }

    fun disconnect() {
        synchronized(this) {
            generation += 1
            socket?.closeQuietly()
            socket = null
        }
        mutableState.value = PresenceConnectionState()
    }

    override fun close() {
        disconnect()
        executor.shutdownNow()
    }

    private fun connect(currentGeneration: Long, mac: DiscoveredMac, phoneName: String) {
        var activeSocket: Socket? = null
        try {
            activeSocket = connectToFirstAddress(mac)
            if (!adopt(currentGeneration, activeSocket)) return

            val presence = DevicePresence(
                deviceId = identityStore.deviceId(),
                deviceName = phoneName,
                appVersion = BuildConfig.VERSION_NAME,
            )
            activeSocket.soTimeout = HANDSHAKE_TIMEOUT_MILLISECONDS
            activeSocket.getOutputStream().apply {
                write(presence.toJsonLine().toByteArray(Charsets.UTF_8))
                flush()
            }

            val acknowledgement = readBoundedUtf8Line(activeSocket, MAXIMUM_ACKNOWLEDGEMENT_SIZE)
            validateAcknowledgement(acknowledgement, mac.deviceId)
            activeSocket.soTimeout = 0

            if (!isCurrent(currentGeneration, activeSocket)) return
            mutableState.value = PresenceConnectionState(
                status = PresenceConnectionStatus.DETECTED,
                selectedMac = mac,
                phoneName = phoneName,
            )

            // The open socket represents presence only. Feature traffic is forbidden until
            // secure pairing and authenticated transport are implemented.
            while (activeSocket.getInputStream().read() != -1) {
                throw IllegalStateException("Unexpected data before secure pairing.")
            }
            throw IllegalStateException("The Mac closed the presence connection.")
        } catch (exception: Exception) {
            activeSocket?.closeQuietly()
            if (isCurrent(currentGeneration, activeSocket)) {
                synchronized(this) {
                    if (socket === activeSocket) socket = null
                }
                mutableState.value = PresenceConnectionState(
                    status = PresenceConnectionStatus.ERROR,
                    selectedMac = mac,
                    phoneName = phoneName,
                    errorMessage = exception.message ?: "Unable to reach this Mac.",
                )
            }
        }
    }

    private fun connectToFirstAddress(mac: DiscoveredMac): Socket {
        var lastFailure: Exception? = null
        for (address in mac.addresses) {
            val candidate = Socket()
            try {
                candidate.tcpNoDelay = true
                candidate.connect(InetSocketAddress(address, mac.port), CONNECT_TIMEOUT_MILLISECONDS)
                return candidate
            } catch (exception: Exception) {
                candidate.closeQuietly()
                lastFailure = exception
            }
        }
        throw lastFailure ?: IllegalStateException("The Mac did not provide a reachable address.")
    }

    @Synchronized
    private fun adopt(currentGeneration: Long, candidate: Socket): Boolean {
        if (generation != currentGeneration) {
            candidate.closeQuietly()
            return false
        }
        socket = candidate
        return true
    }

    @Synchronized
    private fun isCurrent(currentGeneration: Long, candidate: Socket?): Boolean =
        generation == currentGeneration && socket === candidate

    private fun validateAcknowledgement(line: String, expectedMacId: UUID) {
        val json = JSONObject(line)
        require(json.optString("kind") == "presence_ack") { "Invalid response from MacLink." }
        require(json.optInt("version") == 1) { "The Mac uses an unsupported protocol version." }
        require(
            runCatching { UUID.fromString(json.optString("macDeviceId")) }.getOrNull() == expectedMacId,
        ) { "The Mac identity changed during discovery." }
        require(json.optString("macName").isNotBlank()) { "The Mac did not provide its name." }
    }

    private fun readBoundedUtf8Line(socket: Socket, maximumBytes: Int): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = socket.getInputStream().read()
            require(value != -1) { "The Mac closed the connection before responding." }
            if (value == '\n'.code) break
            require(bytes.size() < maximumBytes) { "The Mac response was too large." }
            bytes.write(value)
        }

        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLISECONDS = 5_000
        const val HANDSHAKE_TIMEOUT_MILLISECONDS = 10_000
        const val MAXIMUM_ACKNOWLEDGEMENT_SIZE = 8 * 1024
    }
}
