package com.sivanalluri.maclink.companion.connection

data class ConnectionUiState(
    val phase: ConnectionPhase = ConnectionPhase.STOPPED,
    val pairedMacName: String? = null,
) {
    val isSearching: Boolean
        get() = phase != ConnectionPhase.STOPPED && phase != ConnectionPhase.CONNECTED
}

