package com.sivanalluri.maclink.companion.discovery

import java.util.UUID

data class DiscoveredMac(
    val serviceName: String,
    val deviceId: UUID,
    val displayName: String,
    val protocolVersion: Int,
    val addresses: List<String>,
    val port: Int,
    val pairingAllowed: Boolean,
)

object MacLinkServiceContract {
    const val SERVICE_TYPE = "_maclink._tcp."
    const val PROTOCOL_VERSION = 1

    fun parse(
        serviceName: String,
        port: Int,
        addresses: List<String>,
        attributes: Map<String, ByteArray>,
    ): DiscoveredMac? {
        if (port !in 1..65535 || addresses.isEmpty()) return null

        val protocolVersion = attributes.utf8("pv")?.toIntOrNull() ?: return null
        if (protocolVersion != PROTOCOL_VERSION) return null

        val deviceId = attributes.utf8("id")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val displayName = attributes.utf8("name")
            ?.takeIf(String::isNotBlank)
            ?: serviceName.removePrefix("MacLink – ").ifBlank { "Mac" }

        return DiscoveredMac(
            serviceName = serviceName,
            deviceId = deviceId,
            displayName = displayName,
            protocolVersion = protocolVersion,
            addresses = addresses.distinct(),
            port = port,
            pairingAllowed = attributes.utf8("pairing") == "1",
        )
    }

    private fun Map<String, ByteArray>.utf8(key: String): String? =
        get(key)?.toString(Charsets.UTF_8)
}

