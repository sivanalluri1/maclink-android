package com.sivanalluri.maclink.companion.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureSessionCryptoTest {
    @Test
    fun hkdfMatchesTheCrossPlatformVector() {
        val result = SecureSessionCrypto.hkdf(
            inputKeyMaterial = ByteArray(32) { it.toByte() },
            salt = ByteArray(32) { (it + 32).toByte() },
            info = "maclink-client-to-mac-key-v1",
            outputLength = 32,
        )

        assertEquals(
            "542fb5dd0756a26b7478320c830c9609278e20465b9ea9a0ef1e9db96a1f10a5",
            result.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun sequenceProducesUniqueTwelveByteNonces() {
        val prefix = byteArrayOf(1, 2, 3, 4)
        val first = SecureSessionCrypto.nonce(prefix, 0)
        val second = SecureSessionCrypto.nonce(prefix, 1)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0), first)
        assertEquals(12, second.size)
        assertFalse(first.contentEquals(second))
    }

    @Test(expected = AEADBadTagException::class)
    fun authenticatedEncryptionRejectsTampering() {
        val key = SecureSessionCrypto.hkdf(
            ByteArray(32) { it.toByte() },
            ByteArray(32) { (it + 32).toByte() },
            "maclink-client-to-mac-key-v1",
            32,
        )
        val nonce = SecureSessionCrypto.nonce(byteArrayOf(1, 2, 3, 4), 0)
        val aad = SecureSessionCrypto.frameAad(UUID(0, 1), 0, "client_to_mac", 13)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal("session.hello".toByteArray())
        }
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()

        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(sealed)
        }
    }
}
