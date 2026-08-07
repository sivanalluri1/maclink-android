package com.sivanalluri.maclink.companion.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MacLinkServiceContractTest {
    private val deviceId = UUID.fromString("ba751927-df6c-4ec3-84a0-c4d9d7b707b4")

    @Test
    fun `parses the shared Bonjour contract`() {
        val service = MacLinkServiceContract.parse(
            serviceName = "MacLink – Test Mac",
            port = 49152,
            addresses = listOf("192.168.1.10", "192.168.1.10", "fe80::1"),
            attributes = attributes(),
        )

        requireNotNull(service)
        assertEquals(deviceId, service.deviceId)
        assertEquals("Test Mac", service.displayName)
        assertEquals(1, service.protocolVersion)
        assertEquals(listOf("192.168.1.10", "fe80::1"), service.addresses)
        assertEquals(49152, service.port)
        assertTrue(service.pairingAllowed)
    }

    @Test
    fun `rejects an unsupported protocol`() {
        assertNull(
            MacLinkServiceContract.parse(
                "MacLink",
                49152,
                listOf("192.168.1.10"),
                attributes(protocolVersion = "2"),
            ),
        )
    }

    @Test
    fun `rejects missing network coordinates`() {
        assertNull(MacLinkServiceContract.parse("MacLink", 0, emptyList(), attributes()))
    }

    @Test
    fun `reads pairing availability`() {
        val service = MacLinkServiceContract.parse(
            "MacLink",
            49152,
            listOf("192.168.1.10"),
            attributes(pairing = "0"),
        )

        requireNotNull(service)
        assertFalse(service.pairingAllowed)
    }

    private fun attributes(
        protocolVersion: String = "1",
        pairing: String = "1",
    ): Map<String, ByteArray> = mapOf(
        "pv" to protocolVersion.toByteArray(),
        "id" to deviceId.toString().toByteArray(),
        "name" to "Test Mac".toByteArray(),
        "pairing" to pairing.toByteArray(),
    )
}

