package com.sivanalluri.maclink.companion.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class PairingIdentityStore {
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    fun publicKeyDer(): ByteArray = loadOrCreate().public.encoded

    fun sign(data: ByteArray): ByteArray = Signature.getInstance(SIGNATURE_ALGORITHM).run {
        initSign(loadOrCreate().private)
        update(data)
        sign()
    }

    private fun loadOrCreate(): KeyPair {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            return KeyPair(existing.certificate.publicKey, existing.privateKey as PrivateKey)
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair()
    }

    companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "maclink-pairing-signing-v1"
    }
}
