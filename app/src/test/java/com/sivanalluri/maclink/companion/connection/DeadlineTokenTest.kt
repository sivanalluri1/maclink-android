package com.sivanalluri.maclink.companion.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineTokenTest {
    @Test fun canceledCallbackCannotExpireAConnectedSession() {
        val gate = DeadlineToken()
        val callback = gate.invalidate()
        assertTrue(gate.isCurrent(callback))
        gate.invalidate()
        assertFalse(gate.isCurrent(callback))
    }

    @Test fun replacementDeadlineRejectsOldCallback() {
        val gate = DeadlineToken()
        val old = gate.invalidate()
        val replacement = gate.invalidate()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(replacement))
    }
}
