package com.sivanalluri.maclink.companion.connection

import android.content.Context
import com.sivanalluri.maclink.companion.BuildConfig
import com.sivanalluri.maclink.companion.discovery.DiscoveredMac
import com.sivanalluri.maclink.companion.pairing.PairedMacRecord
import com.sivanalluri.maclink.companion.pairing.PairedMacStore
import com.sivanalluri.maclink.companion.pairing.PairingIdentityStore
import com.sivanalluri.maclink.companion.pairing.PairingProtocol
import com.sivanalluri.maclink.companion.pairing.PairingQrPayload
import com.sivanalluri.maclink.companion.pairing.PairingRequestData
import com.sivanalluri.maclink.companion.pairing.base64Url
import com.sivanalluri.maclink.companion.pairing.constantTimeEquals
import com.sivanalluri.maclink.companion.pairing.decodeBase64Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class PresenceConnectionStatus {
    IDLE,
    CONNECTING,
    DETECTED,
    PAIRING,
    AWAITING_APPROVAL,
    PAIRED,
    ERROR,
}

data class PresenceConnectionState(
    val status: PresenceConnectionStatus = PresenceConnectionStatus.IDLE,
    val selectedMac: DiscoveredMac? = null,
    val phoneName: String? = null,
    val verificationCode: String? = null,
    val errorMessage: String? = null,
)

class MacConnectionManager(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val phoneIdentityStore = PhoneIdentityStore(applicationContext)
    private val pairingIdentityStore by lazy { PairingIdentityStore() }
    private val pairedMacStore = PairedMacStore(applicationContext)
    private val connectionExecutor = Executors.newSingleThreadExecutor()
    private val pairingExecutor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(PresenceConnectionState())
    val state: StateFlow<PresenceConnectionState> = mutableState.asStateFlow()

    @Volatile
    private var generation = 0L

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var pairingContext: ActivePairing? = null

    private data class ActivePairing(
        val payload: PairingQrPayload,
        val phoneDeviceId: UUID,
        val phoneName: String,
        val phonePublicKey: String,
        val phoneNonce: String,
        val macPublicKeyDer: ByteArray? = null,
        val transcript: ByteArray? = null,
    )

    fun connect(mac: DiscoveredMac) {
        val currentGeneration: Long
        synchronized(this) {
            generation += 1
            currentGeneration = generation
            socket?.closeQuietly()
            socket = null
            pairingContext = null
        }

        val phoneName = phoneIdentityStore.deviceName()
        mutableState.value = PresenceConnectionState(
            status = PresenceConnectionStatus.CONNECTING,
            selectedMac = mac,
            phoneName = phoneName,
        )

        connectionExecutor.execute {
            connect(currentGeneration, mac, phoneName)
        }
    }

    fun beginPairing(scannedValue: String) {
        val currentState = mutableState.value
        val mac = currentState.selectedMac
        if (currentState.status != PresenceConnectionStatus.DETECTED || mac == null) {
            reportPairingError("Connect to the Mac before scanning its pairing code.")
            return
        }

        pairingExecutor.execute {
            try {
                val payload = PairingQrPayload.parse(scannedValue)
                require(payload.macDeviceId == mac.deviceId) {
                    "This pairing code belongs to a different Mac."
                }
                require(System.currentTimeMillis() <= payload.expiresAt) {
                    "The pairing code expired. Start pairing again on the Mac."
                }

                val phoneDeviceId = phoneIdentityStore.deviceId()
                val phoneName = currentState.phoneName ?: phoneIdentityStore.deviceName()
                val phonePublicKey = pairingIdentityStore.publicKeyDer().base64Url()
                val phoneNonce = ByteArray(PairingProtocol.NONCE_LENGTH).also(SecureRandom()::nextBytes)
                    .base64Url()
                val request = PairingRequestData(
                    pairingId = payload.pairingId,
                    macDeviceId = payload.macDeviceId,
                    phoneDeviceId = phoneDeviceId,
                    phoneName = phoneName,
                    phonePublicKey = phonePublicKey,
                    phoneNonce = phoneNonce,
                )
                val secretProof = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(payload.secret, "HmacSHA256"))
                    doFinal(request.proofData())
                }
                pairingContext = ActivePairing(
                    payload = payload,
                    phoneDeviceId = phoneDeviceId,
                    phoneName = phoneName,
                    phonePublicKey = phonePublicKey,
                    phoneNonce = phoneNonce,
                )
                payload.secret.fill(0)
                mutableState.value = currentState.copy(
                    status = PresenceConnectionStatus.PAIRING,
                    verificationCode = null,
                    errorMessage = null,
                )
                sendLine(request.toJson(secretProof))
            } catch (exception: Exception) {
                reportPairingError(exception.message ?: "Unable to start secure pairing.")
            }
        }
    }

    fun reportPairingError(message: String) {
        val current = mutableState.value
        mutableState.value = current.copy(errorMessage = message)
    }

    fun disconnect() {
        synchronized(this) {
            generation += 1
            socket?.closeQuietly()
            socket = null
            pairingContext = null
        }
        mutableState.value = PresenceConnectionState()
    }

    override fun close() {
        disconnect()
        connectionExecutor.shutdownNow()
        pairingExecutor.shutdownNow()
    }

    private fun connect(currentGeneration: Long, mac: DiscoveredMac, phoneName: String) {
        var activeSocket: Socket? = null
        try {
            activeSocket = connectToFirstAddress(mac)
            if (!adopt(currentGeneration, activeSocket)) return

            val presence = DevicePresence(
                deviceId = phoneIdentityStore.deviceId(),
                deviceName = phoneName,
                appVersion = BuildConfig.VERSION_NAME,
            )
            activeSocket.soTimeout = HANDSHAKE_TIMEOUT_MILLISECONDS
            sendLine(presence.toJsonLine().trimEnd())

            val acknowledgement = readBoundedUtf8Line(activeSocket, MAXIMUM_MESSAGE_SIZE)
            validateAcknowledgement(acknowledgement, mac.deviceId)
            activeSocket.soTimeout = 0

            if (!isCurrent(currentGeneration, activeSocket)) return
            val storedPairing = pairedMacStore.load(mac.deviceId)
            mutableState.value = PresenceConnectionState(
                status = if (storedPairing == null) {
                    PresenceConnectionStatus.DETECTED
                } else {
                    PresenceConnectionStatus.PAIRED
                },
                selectedMac = mac,
                phoneName = phoneName,
            )

            while (isCurrent(currentGeneration, activeSocket)) {
                val message = readBoundedUtf8Line(activeSocket, MAXIMUM_MESSAGE_SIZE)
                handlePairingMessage(message, mac)
            }
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

    private fun handlePairingMessage(message: String, mac: DiscoveredMac) {
        val json = JSONObject(message)
        when (json.getString("kind")) {
            "pairing_challenge" -> handleChallenge(json, mac)
            "pairing_result" -> handleResult(json, mac)
            "pairing_error" -> {
                pairingContext = null
                mutableState.value = mutableState.value.copy(
                    status = PresenceConnectionStatus.DETECTED,
                    verificationCode = null,
                    errorMessage = "The Mac rejected this pairing attempt. Start a new pairing window.",
                )
            }
            else -> error("The Mac sent an unsupported pre-session message.")
        }
    }

    private fun handleChallenge(json: JSONObject, mac: DiscoveredMac) {
        val context = pairingContext ?: error("No pairing request is active.")
        require(json.getInt("version") == PairingProtocol.VERSION)
        require(UUID.fromString(json.getString("pairingId")) == context.payload.pairingId)

        val macPublicKeyDer = json.getString("macPublicKey").decodeBase64Url()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(macPublicKeyDer)
        require(fingerprint.constantTimeEquals(context.payload.macPublicKeyFingerprint)) {
            "The Mac public key does not match the QR code."
        }
        val macNonceValue = json.getString("macNonce")
        require(macNonceValue.decodeBase64Url().size == PairingProtocol.NONCE_LENGTH)

        val transcript = PairingProtocol.canonicalData(
            listOf(
                "maclink-pairing-transcript-v1",
                context.payload.pairingId.toString().lowercase(),
                context.payload.macDeviceId.toString().lowercase(),
                context.phoneDeviceId.toString().lowercase(),
                macPublicKeyDer.base64Url(),
                context.phonePublicKey,
                macNonceValue,
                context.phoneNonce,
            ),
        )
        val macPublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(macPublicKeyDer))
        val macSignature = json.getString("macSignature").decodeBase64Url()
        require(Signature.getInstance(PairingIdentityStore.SIGNATURE_ALGORITHM).run {
            initVerify(macPublicKey)
            update(transcript)
            verify(macSignature)
        }) { "The Mac pairing signature is invalid." }

        val phoneSignature = pairingIdentityStore.sign(transcript)
        val proof = JSONObject()
            .put("kind", "pairing_proof")
            .put("version", PairingProtocol.VERSION)
            .put("pairingId", context.payload.pairingId.toString())
            .put("phoneDeviceId", context.phoneDeviceId.toString())
            .put("phoneSignature", phoneSignature.base64Url())
        pairingContext = context.copy(macPublicKeyDer = macPublicKeyDer, transcript = transcript)
        mutableState.value = mutableState.value.copy(
            status = PresenceConnectionStatus.AWAITING_APPROVAL,
            verificationCode = PairingProtocol.verificationCode(transcript),
            errorMessage = null,
        )
        sendLine(proof.toString())
    }

    private fun handleResult(json: JSONObject, mac: DiscoveredMac) {
        val context = pairingContext ?: error("No pairing request is active.")
        val transcript = context.transcript ?: error("The pairing transcript is missing.")
        val macPublicKeyDer = context.macPublicKeyDer ?: error("The Mac key is missing.")
        require(json.getInt("version") == PairingProtocol.VERSION)
        require(UUID.fromString(json.getString("pairingId")) == context.payload.pairingId)
        val approved = json.getBoolean("approved")
        val transcriptHash = MessageDigest.getInstance("SHA-256").digest(transcript).base64Url()
        val signedData = PairingProtocol.canonicalData(
            listOf(
                "maclink-pairing-result-v1",
                context.payload.pairingId.toString().lowercase(),
                transcriptHash,
                if (approved) "approved" else "rejected",
            ),
        )
        val macPublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(macPublicKeyDer))
        require(Signature.getInstance(PairingIdentityStore.SIGNATURE_ALGORITHM).run {
            initVerify(macPublicKey)
            update(signedData)
            verify(json.getString("macSignature").decodeBase64Url())
        }) { "The Mac pairing decision signature is invalid." }

        if (approved) {
            pairedMacStore.save(PairedMacRecord(
                deviceId = mac.deviceId,
                deviceName = context.payload.macName,
                publicKeyDer = macPublicKeyDer,
                pairedAt = System.currentTimeMillis(),
            ))
            pairingContext = null
            mutableState.value = mutableState.value.copy(
                status = PresenceConnectionStatus.PAIRED,
                verificationCode = null,
                errorMessage = null,
            )
        } else {
            pairingContext = null
            mutableState.value = mutableState.value.copy(
                status = PresenceConnectionStatus.DETECTED,
                verificationCode = null,
                errorMessage = "Pairing was rejected on the Mac.",
            )
        }
    }

    @Synchronized
    private fun sendLine(message: String) {
        val activeSocket = socket ?: error("The Mac connection is not open.")
        val bytes = "$message\n".toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAXIMUM_MESSAGE_SIZE) { "The pairing message is too large." }
        activeSocket.getOutputStream().apply {
            write(bytes)
            flush()
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
        const val MAXIMUM_MESSAGE_SIZE = 16 * 1024
    }
}
