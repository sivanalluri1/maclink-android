package com.sivanalluri.maclink.companion.connection

import com.sivanalluri.maclink.companion.pairing.PairedMacRecord
import com.sivanalluri.maclink.companion.pairing.PairingIdentityStore
import com.sivanalluri.maclink.companion.pairing.PairingProtocol
import com.sivanalluri.maclink.companion.pairing.base64Url
import com.sivanalluri.maclink.companion.pairing.decodeBase64Url
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class SecureSessionClient(
    private val identityStore: PairingIdentityStore,
    private val send: (String) -> Unit,
    private val onConnected: (UUID) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private var context: Context? = null
    private var receiveSequence = 0L
    private var sendSequence = 0L

    private data class Context(
        val mac: PairedMacRecord,
        val phoneDeviceId: UUID,
        val phoneName: String,
        val appVersion: String,
        val ephemeralKeyPair: KeyPair,
        val clientNonce: String,
        var sessionId: UUID? = null,
        var clientToMacKey: ByteArray? = null,
        var macToClientKey: ByteArray? = null,
        var clientNoncePrefix: ByteArray? = null,
        var macNoncePrefix: ByteArray? = null,
    )

    fun start(mac: PairedMacRecord, phoneDeviceId: UUID, phoneName: String, appVersion: String) {
        try {
            reset()
            val ephemeral = KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
            val nonce = ByteArray(NONCE_LENGTH).also(SecureRandom()::nextBytes).base64Url()
            val ephemeralPublicKey = ephemeral.public.encoded.base64Url()
            val authenticationData = PairingProtocol.canonicalData(
                listOf(
                    CLIENT_AUTH_LABEL,
                    VERSION.toString(),
                    phoneDeviceId.toString().lowercase(),
                    mac.deviceId.toString().lowercase(),
                    ephemeralPublicKey,
                    nonce,
                    VERSION.toString(),
                    VERSION.toString(),
                ),
            )
            context = Context(mac, phoneDeviceId, phoneName, appVersion, ephemeral, nonce)
            send(
                JSONObject()
                    .put("kind", "session_client_hello")
                    .put("version", VERSION)
                    .put("phoneDeviceId", phoneDeviceId.toString())
                    .put("macDeviceId", mac.deviceId.toString())
                    .put("ephemeralPublicKey", ephemeralPublicKey)
                    .put("clientNonce", nonce)
                    .put("protocolMin", VERSION)
                    .put("protocolMax", VERSION)
                    .put("signature", identityStore.sign(authenticationData).base64Url())
                    .toString(),
            )
        } catch (exception: Exception) {
            fail(exception)
        }
    }

    fun handle(json: JSONObject) {
        try {
            when (json.getString("kind")) {
                "session_server_hello" -> handleServerHello(json)
                "secure_frame" -> handleSecureFrame(json)
                else -> error("Unsupported secure-session message.")
            }
        } catch (exception: Exception) {
            fail(exception)
        }
    }

    fun reset() {
        context?.clientToMacKey?.fill(0)
        context?.macToClientKey?.fill(0)
        context = null
        receiveSequence = 0
        sendSequence = 0
    }

    private fun handleServerHello(json: JSONObject) {
        val current = context ?: error("No secure session is active.")
        require(json.getInt("version") == VERSION)
        require(UUID.fromString(json.getString("macDeviceId")) == current.mac.deviceId)
        require(UUID.fromString(json.getString("phoneDeviceId")) == current.phoneDeviceId)
        require(json.getInt("selectedProtocol") == VERSION)
        val sessionId = UUID.fromString(json.getString("sessionId"))
        val serverEphemeralValue = json.getString("ephemeralPublicKey")
        val serverNonce = json.getString("serverNonce")
        require(serverNonce.decodeBase64Url().size == NONCE_LENGTH)
        val transcript = PairingProtocol.canonicalData(
            listOf(
                TRANSCRIPT_LABEL,
                VERSION.toString(),
                current.phoneDeviceId.toString().lowercase(),
                current.mac.deviceId.toString().lowercase(),
                current.ephemeralKeyPair.public.encoded.base64Url(),
                serverEphemeralValue,
                current.clientNonce,
                serverNonce,
                sessionId.toString().lowercase(),
                VERSION.toString(),
            ),
        )
        val macPublicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(current.mac.publicKeyDer),
        )
        require(Signature.getInstance(PairingIdentityStore.SIGNATURE_ALGORITHM).run {
            initVerify(macPublicKey)
            update(transcript)
            verify(json.getString("signature").decodeBase64Url())
        }) { "The Mac secure-session signature is invalid." }

        val serverEphemeral = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(serverEphemeralValue.decodeBase64Url()),
        )
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(current.ephemeralKeyPair.private)
            doPhase(serverEphemeral, true)
            generateSecret()
        }
        val salt = MessageDigest.getInstance("SHA-256").digest(transcript)
        current.sessionId = sessionId
        current.clientToMacKey = SecureSessionCrypto.hkdf(
            sharedSecret, salt, "maclink-client-to-mac-key-v1", 32,
        )
        current.macToClientKey = SecureSessionCrypto.hkdf(
            sharedSecret, salt, "maclink-mac-to-client-key-v1", 32,
        )
        current.clientNoncePrefix = SecureSessionCrypto.hkdf(
            sharedSecret, salt, "maclink-client-nonce-v1", 4,
        )
        current.macNoncePrefix = SecureSessionCrypto.hkdf(
            sharedSecret, salt, "maclink-mac-nonce-v1", 4,
        )
        sharedSecret.fill(0)

        val hello = JSONObject()
            .put("type", "session.hello")
            .put("deviceId", current.phoneDeviceId.toString())
            .put("deviceName", current.phoneName)
            .put("platform", "android")
            .put("appVersion", current.appVersion)
            .put("protocolVersion", VERSION)
            .put("capabilities", JSONArray())
        sendSecure(hello.toString().toByteArray(Charsets.UTF_8))
    }

    private fun handleSecureFrame(json: JSONObject) {
        val current = context ?: error("No secure session is active.")
        val sessionId = current.sessionId ?: error("The secure session is not authenticated.")
        require(json.getInt("version") == VERSION)
        require(UUID.fromString(json.getString("sessionId")) == sessionId)
        require(json.getString("direction") == "mac_to_client")
        require(json.getString("contentType") == "control")
        val sequence = json.getString("sequence").toLong()
        require(sequence == receiveSequence) { "A repeated or out-of-order frame was rejected." }
        val ciphertext = json.getString("ciphertext").decodeBase64Url()
        val tag = json.getString("tag").decodeBase64Url()
        require(ciphertext.size <= MAXIMUM_PLAINTEXT_SIZE && tag.size == 16)
        val aad = SecureSessionCrypto.frameAad(
            sessionId, sequence, "mac_to_client", ciphertext.size,
        )
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(current.macToClientKey, "AES"),
                GCMParameterSpec(128, SecureSessionCrypto.nonce(current.macNoncePrefix, sequence)),
            )
            updateAAD(aad)
            doFinal(ciphertext + tag)
        }
        receiveSequence += 1
        val message = JSONObject(plaintext.toString(Charsets.UTF_8))
        require(message.getString("type") == "session.welcome")
        require(UUID.fromString(message.getString("deviceId")) == current.mac.deviceId)
        require(message.getInt("protocolVersion") == VERSION)
        onConnected(sessionId)
    }

    private fun sendSecure(plaintext: ByteArray) {
        val current = context ?: error("No secure session is active.")
        val sessionId = current.sessionId ?: error("The secure session is not authenticated.")
        require(plaintext.size <= MAXIMUM_PLAINTEXT_SIZE)
        val sequence = sendSequence
        val aad = SecureSessionCrypto.frameAad(
            sessionId, sequence, "client_to_mac", plaintext.size,
        )
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(current.clientToMacKey, "AES"),
                GCMParameterSpec(128, SecureSessionCrypto.nonce(current.clientNoncePrefix, sequence)),
            )
            updateAAD(aad)
            doFinal(plaintext)
        }
        val ciphertext = sealed.copyOfRange(0, sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
        sendSequence += 1
        send(
            JSONObject()
                .put("kind", "secure_frame")
                .put("version", VERSION)
                .put("sessionId", sessionId.toString())
                .put("sequence", sequence.toString())
                .put("direction", "client_to_mac")
                .put("contentType", "control")
                .put("ciphertext", ciphertext.base64Url())
                .put("tag", tag.base64Url())
                .toString(),
        )
    }

    private fun fail(exception: Exception) {
        reset()
        onFailure(exception.message ?: "Secure-session authentication failed.")
    }

    private companion object {
        const val VERSION = 1
        const val NONCE_LENGTH = 32
        const val MAXIMUM_PLAINTEXT_SIZE = 1024 * 1024
        const val CLIENT_AUTH_LABEL = "maclink-session-client-auth-v1"
        const val TRANSCRIPT_LABEL = "maclink-session-transcript-v1"
    }
}

internal object SecureSessionCrypto {
    private const val FRAME_LABEL = "maclink-secure-frame-v1"

    fun frameAad(
        sessionId: UUID,
        sequence: Long,
        direction: String,
        plaintextSize: Int,
    ): ByteArray = PairingProtocol.canonicalData(
        listOf(
            FRAME_LABEL,
            "1",
            sessionId.toString().lowercase(),
            direction,
            sequence.toString(),
            "control",
            plaintextSize.toString(),
        ),
    )

    fun nonce(prefix: ByteArray?, sequence: Long): ByteArray {
        require(prefix?.size == 4)
        return ByteBuffer.allocate(12).put(prefix).putLong(sequence).array()
    }

    fun hkdf(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: String,
        outputLength: Int,
    ): ByteArray {
        require(outputLength in 1..32)
        val extracted = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(inputKeyMaterial)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(extracted, "HmacSHA256"))
            update(info.toByteArray(Charsets.UTF_8))
            update(1)
            doFinal().copyOf(outputLength)
        }.also { extracted.fill(0) }
    }
}
