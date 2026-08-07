package com.sivanalluri.maclink.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PresenceProtocolTest {
    @Test
    fun `presence message follows the shared contract`() {
        val id = UUID.randomUUID()
        val message = DevicePresence(id, "Siva's Phone", "0.1.0").toJsonLine()

        assertTrue(message.endsWith("\n"))
        assertTrue(message.contains("\"kind\":\"device_presence\""))
        assertTrue(message.contains("\"version\":1"))
        assertTrue(message.contains("\"deviceId\":\"$id\""))
        assertTrue(message.contains("\"platform\":\"android\""))
    }

    @Test
    fun `presence message escapes untrusted display text`() {
        val message = DevicePresence(UUID.randomUUID(), "Phone\n\"One\"", "0.1.0").toJsonLine()

        assertTrue(message.contains("Phone\\n\\\"One\\\""))
        assertEquals(1, message.count { it == '\n' })
    }

    @Test
    fun `presence rejects an oversized device name`() {
        assertThrows(IllegalArgumentException::class.java) {
            DevicePresence(
                UUID.randomUUID(),
                "p".repeat(DevicePresence.MAXIMUM_DEVICE_NAME_LENGTH + 1),
                "0.1.0",
            )
        }
    }
}
