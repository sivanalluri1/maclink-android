package com.sivanalluri.maclink.companion.connection

import android.content.Context
import android.util.Log
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class PresenceConnectionStatus {
    IDLE,
    CONNECTING,
    DETECTED,
    PAIRING,
    AWAITING_APPROVAL,
    PAIRED,
    AUTHENTICATING,
    CONNECTED,
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
    private val pairingExecutor = Executors.newSingleThreadExecutor()
    private val webSocketClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val mutableState = MutableStateFlow(PresenceConnectionState())
    val state: StateFlow<PresenceConnectionState> = mutableState.asStateFlow()
    private val secureSessionClient by lazy {
        SecureSessionClient(
            identityStore = pairingIdentityStore,
            send = ::sendLine,
            onConnected = { sessionId ->
                Log.i(TAG, "Authenticated encrypted session established: $sessionId")
                mutableState.value = mutableState.value.copy(
                    status = PresenceConnectionStatus.CONNECTED,
                    errorMessage = null,
                )
            },
            onFailure = { message ->
                failSecureSession(message)
            },
        )
    }

    @Volatile
    private var generation = 0L

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var pairingContext: ActivePairing? = null

    @Volatile
    private var pendingPairingValue: String? = null

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
        pendingPairingValue = null
        connectInternal(mac)
    }

    private fun connectInternal(mac: DiscoveredMac) {
        val currentGeneration: Long
        synchronized(this) {
            generation += 1
            currentGeneration = generation
            webSocket?.cancel()
            webSocket = null
            pairingContext = null
            secureSessionClient.reset()
        }

        val phoneName = phoneIdentityStore.deviceName()
        mutableState.value = PresenceConnectionState(
            status = PresenceConnectionStatus.CONNECTING,
            selectedMac = mac,
            phoneName = phoneName,
        )

        connect(currentGeneration, mac, phoneName)
    }

    fun beginPairing(scannedValue: String) {
        val currentState = mutableState.value
        val mac = currentState.selectedMac
        if (mac != null && currentState.status == PresenceConnectionStatus.ERROR) {
            Log.i(TAG, "Pairing QR scanned after connection loss; reconnecting")
            pendingPairingValue = scannedValue
            connectInternal(mac)
            return
        }
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
            webSocket?.close(1000, "User disconnected")
            webSocket = null
            pairingContext = null
            pendingPairingValue = null
            secureSessionClient.reset()
        }
        mutableState.value = PresenceConnectionState()
    }

    override fun close() {
        disconnect()
        pairingExecutor.shutdownNow()
        webSocketClient.dispatcher.executorService.shutdown()
        webSocketClient.connectionPool.evictAll()
    }

    private fun connect(currentGeneration: Long, mac: DiscoveredMac, phoneName: String) {
        val request = Request.Builder()
            .url(webSocketUrl(mac))
            .header("Sec-WebSocket-Protocol", "maclink.v1")
            .build()
        val candidate = webSocketClient.newWebSocket(request, object : WebSocketListener() {
            private var acknowledged = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!adopt(currentGeneration, webSocket)) return
                val presence = DevicePresence(
                    deviceId = phoneIdentityStore.deviceId(),
                    deviceName = phoneName,
                    appVersion = BuildConfig.VERSION_NAME,
                )
                webSocket.send(presence.toJsonLine().trimEnd())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(currentGeneration, webSocket)) return
                try {
                    if (!acknowledged) {
                        validateAcknowledgement(text, mac.deviceId)
                        acknowledged = true
                        val storedPairing = pairedMacStore.load(mac.deviceId)
                        val nextStatus = if (storedPairing == null) {
                                PresenceConnectionStatus.DETECTED
                            } else {
                                PresenceConnectionStatus.AUTHENTICATING
                            }
                        mutableState.value = PresenceConnectionState(
                            status = nextStatus,
                            selectedMac = mac,
                            phoneName = phoneName,
                        )
                        if (storedPairing != null) {
                            secureSessionClient.start(
                                storedPairing,
                                phoneIdentityStore.deviceId(),
                                phoneName,
                                BuildConfig.VERSION_NAME,
                            )
                        }
                        pendingPairingValue?.let { scannedValue ->
                            pendingPairingValue = null
                            Log.i(TAG, "Connection restored; continuing secure pairing")
                            beginPairing(scannedValue)
                        }
                    } else {
                        handlePairingMessage(text, mac)
                    }
                } catch (exception: Exception) {
                    failConnection(currentGeneration, webSocket, mac, phoneName, exception)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failConnection(currentGeneration, webSocket, mac, phoneName, t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                failConnection(
                    currentGeneration,
                    webSocket,
                    mac,
                    phoneName,
                    IllegalStateException("The Mac closed the connection."),
                )
            }
        })
        synchronized(this) {
            if (generation == currentGeneration) webSocket = candidate else candidate.cancel()
        }
    }

    private fun failConnection(
        currentGeneration: Long,
        candidate: WebSocket,
        mac: DiscoveredMac,
        phoneName: String,
        failure: Throwable,
    ) {
        if (!isCurrent(currentGeneration, candidate)) return
        Log.w(TAG, "Mac connection ended: ${failure.javaClass.simpleName}")
        synchronized(this) {
            if (webSocket === candidate) webSocket = null
            secureSessionClient.reset()
        }
        mutableState.value = PresenceConnectionState(
            status = PresenceConnectionStatus.ERROR,
            selectedMac = mac,
            phoneName = phoneName,
            errorMessage = failure.message ?: "Unable to reach this Mac.",
        )
    }

    private fun failSecureSession(message: String) {
        synchronized(this) {
            generation += 1
            webSocket?.cancel()
            webSocket = null
            secureSessionClient.reset()
        }
        mutableState.value = mutableState.value.copy(
            status = PresenceConnectionStatus.ERROR,
            errorMessage = message,
        )
    }

    private fun handlePairingMessage(message: String, mac: DiscoveredMac) {
        val json = JSONObject(message)
        when (json.getString("kind")) {
            "pairing_challenge" -> handleChallenge(json, mac)
            "pairing_result" -> handleResult(json, mac)
            "session_server_hello", "secure_frame" -> secureSessionClient.handle(json)
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
            val pairedMac = PairedMacRecord(
                deviceId = mac.deviceId,
                deviceName = context.payload.macName,
                publicKeyDer = macPublicKeyDer,
                pairedAt = System.currentTimeMillis(),
            )
            pairedMacStore.save(pairedMac)
            pairingContext = null
            mutableState.value = mutableState.value.copy(
                status = PresenceConnectionStatus.AUTHENTICATING,
                verificationCode = null,
                errorMessage = null,
            )
            secureSessionClient.start(
                pairedMac,
                context.phoneDeviceId,
                context.phoneName,
                BuildConfig.VERSION_NAME,
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
        val activeSocket = webSocket ?: error("The Mac connection is not open.")
        val bytes = "$message\n".toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAXIMUM_MESSAGE_SIZE) { "The pairing message is too large." }
        check(activeSocket.send(message)) { "The Mac connection cannot accept a message." }
    }

    @Synchronized
    private fun adopt(currentGeneration: Long, candidate: WebSocket): Boolean {
        if (generation != currentGeneration) {
            candidate.cancel()
            return false
        }
        webSocket = candidate
        return true
    }

    @Synchronized
    private fun isCurrent(currentGeneration: Long, candidate: WebSocket?): Boolean =
        generation == currentGeneration && webSocket === candidate

    private fun webSocketUrl(mac: DiscoveredMac): String {
        val address = mac.addresses.firstOrNull()
            ?: error("The Mac did not provide a reachable address.")
        val host = if (':' in address && !address.startsWith("[")) "[$address]" else address
        return "ws://$host:${mac.port}/maclink"
    }

    private fun validateAcknowledgement(line: String, expectedMacId: UUID) {
        val json = JSONObject(line)
        require(json.optString("kind") == "presence_ack") { "Invalid response from MacLink." }
        require(json.optInt("version") == 1) { "The Mac uses an unsupported protocol version." }
        require(
            runCatching { UUID.fromString(json.optString("macDeviceId")) }.getOrNull() == expectedMacId,
        ) { "The Mac identity changed during discovery." }
        require(json.optString("macName").isNotBlank()) { "The Mac did not provide its name." }
    }

    private companion object {
        const val TAG = "MacLinkConnection"
        const val MAXIMUM_MESSAGE_SIZE = 1024 * 1024
    }
}
