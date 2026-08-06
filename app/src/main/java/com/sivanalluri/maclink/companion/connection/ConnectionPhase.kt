package com.sivanalluri.maclink.companion.connection

enum class ConnectionPhase {
    STOPPED,
    DISCOVERING,
    CONNECTING,
    AUTHENTICATING,
    SYNCING,
    CONNECTED,
    RECOVERING;

    fun canTransitionTo(next: ConnectionPhase): Boolean = when {
        next == STOPPED -> true
        this == STOPPED && next == DISCOVERING -> true
        this == DISCOVERING && next == CONNECTING -> true
        this == CONNECTING && next == AUTHENTICATING -> true
        this == AUTHENTICATING && next == SYNCING -> true
        this == SYNCING && next == CONNECTED -> true
        this == CONNECTED && next == RECOVERING -> true
        this == RECOVERING && next == DISCOVERING -> true
        else -> false
    }
}

