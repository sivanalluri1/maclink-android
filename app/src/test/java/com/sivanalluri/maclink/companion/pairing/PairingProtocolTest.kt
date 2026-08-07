package com.sivanalluri.maclink.companion.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingProtocolTest {
    @Test
    fun `canonical encoding matches the cross platform vector`() {
        val data = PairingProtocol.canonicalData(listOf("alpha", "β", ""))

        assertEquals("00000005616c70686100000002ceb200000000", data.toHex())
        assertEquals("511293", PairingProtocol.verificationCode(data))
    }

    @Test
    fun `base64 URL encoding round trips without padding`() {
        val value = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0x00, 0x10)
        val encoded = value.base64Url()

        assertEquals("-_8AEA", encoded)
        assertEquals(value.toList(), encoded.decodeBase64Url().toList())
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
