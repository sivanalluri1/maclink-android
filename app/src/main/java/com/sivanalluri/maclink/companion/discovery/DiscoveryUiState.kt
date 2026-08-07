package com.sivanalluri.maclink.companion.discovery

enum class DiscoveryStatus {
    IDLE,
    STARTING,
    SEARCHING,
    PERMISSION_DENIED,
    ERROR,
}

data class DiscoveryUiState(
    val status: DiscoveryStatus = DiscoveryStatus.IDLE,
    val services: List<DiscoveredMac> = emptyList(),
    val errorMessage: String? = null,
) {
    val isRunning: Boolean
        get() = status == DiscoveryStatus.STARTING || status == DiscoveryStatus.SEARCHING
}

