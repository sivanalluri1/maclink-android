package com.sivanalluri.maclink.companion.pairing

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

object PairingProtocol {
    const val VERSION = 1
    const val QR_PREFIX = "maclink-pairing-v1:"
    const val SECRET_LENGTH = 32
    const val NONCE_LENGTH = 32

    fun canonicalData(parts: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        return output.toByteArray()
    }

    fun verificationCode(transcript: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(transcript)
        val value = ((digest[0].toLong() and 0xff) shl 24) or
            ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or
            (digest[3].toLong() and 0xff)
        return (value % 1_000_000).toString().padStart(6, '0')
    }
}

data class PairingQrPayload(
    val pairingId: UUID,
    val macDeviceId: UUID,
    val macName: String,
    val macPublicKeyFingerprint: ByteArray,
    val secret: ByteArray,
    val expiresAt: Long,
) {
    companion object {
        fun parse(value: String): PairingQrPayload {
            require(value.startsWith(PairingProtocol.QR_PREFIX)) { "This is not a MacLink pairing code." }
            val data = value.removePrefix(PairingProtocol.QR_PREFIX).decodeBase64Url()
            val json = JSONObject(data.toString(Charsets.UTF_8))
            require(json.getString("kind") == "pairing_qr") { "Invalid MacLink pairing code." }
            require(json.getInt("version") == PairingProtocol.VERSION) {
                "The Mac uses an unsupported pairing version."
            }
            val fingerprint = json.getString("macPublicKeyFingerprint").decodeBase64Url()
            val secret = json.getString("secret").decodeBase64Url()
            require(fingerprint.size == 32 && secret.size == PairingProtocol.SECRET_LENGTH) {
                "Invalid MacLink pairing code."
            }
            return PairingQrPayload(
                pairingId = UUID.fromString(json.getString("pairingId")),
                macDeviceId = UUID.fromString(json.getString("macDeviceId")),
                macName = json.getString("macName").take(80),
                macPublicKeyFingerprint = fingerprint,
                secret = secret,
                expiresAt = json.getLong("expiresAt"),
            )
        }
    }
}

data class PairingRequestData(
    val pairingId: UUID,
    val macDeviceId: UUID,
    val phoneDeviceId: UUID,
    val phoneName: String,
    val phonePublicKey: String,
    val phoneNonce: String,
) {
    fun proofData(): ByteArray = PairingProtocol.canonicalData(
        listOf(
            "maclink-pairing-request-v1",
            pairingId.toString().lowercase(),
            macDeviceId.toString().lowercase(),
            phoneDeviceId.toString().lowercase(),
            phoneName,
            phonePublicKey,
            phoneNonce,
        ),
    )

    fun toJson(secretProof: ByteArray): String = JSONObject()
        .put("kind", "pairing_request")
        .put("version", PairingProtocol.VERSION)
        .put("pairingId", pairingId.toString())
        .put("macDeviceId", macDeviceId.toString())
        .put("phoneDeviceId", phoneDeviceId.toString())
        .put("phoneName", phoneName)
        .put("phonePublicKey", phonePublicKey)
        .put("phoneNonce", phoneNonce)
        .put("secretProof", secretProof.base64Url())
        .toString()
}

fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

fun String.decodeBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

fun ByteArray.constantTimeEquals(other: ByteArray): Boolean = MessageDigest.isEqual(this, other)
