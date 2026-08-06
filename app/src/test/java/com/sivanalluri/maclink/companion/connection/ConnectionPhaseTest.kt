package com.sivanalluri.maclink.companion.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPhaseTest {
    @Test
    fun `happy path transitions are valid`() {
        val path = listOf(
            ConnectionPhase.STOPPED,
            ConnectionPhase.DISCOVERING,
            ConnectionPhase.CONNECTING,
            ConnectionPhase.AUTHENTICATING,
            ConnectionPhase.SYNCING,
            ConnectionPhase.CONNECTED,
        )

        path.zipWithNext().forEach { (current, next) ->
            assertTrue(current.canTransitionTo(next))
        }
    }

    @Test
    fun `connection cannot skip authentication`() {
        assertFalse(ConnectionPhase.CONNECTING.canTransitionTo(ConnectionPhase.CONNECTED))
    }

    @Test
    fun `every phase can stop`() {
        ConnectionPhase.entries.forEach { phase ->
            assertTrue(phase.canTransitionTo(ConnectionPhase.STOPPED))
        }
    }

    @Test
    fun `recovery returns to discovery`() {
        assertTrue(ConnectionPhase.CONNECTED.canTransitionTo(ConnectionPhase.RECOVERING))
        assertTrue(ConnectionPhase.RECOVERING.canTransitionTo(ConnectionPhase.DISCOVERING))
    }
}

