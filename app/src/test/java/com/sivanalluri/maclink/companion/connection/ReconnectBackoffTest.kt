package com.sivanalluri.maclink.companion.connection

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test fun delaysGrowAndRemainBounded() {
        val backoff = ReconnectBackoff()
        val random = Random(42)
        val ceilings = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)
        repeat(100) { index ->
            val ceiling = ceilings[index.coerceAtMost(5)]
            assertTrue(backoff.nextDelayMillis(random) in ceiling / 2..ceiling)
        }
    }

    @Test fun stableSessionResetsDelay() {
        val backoff = ReconnectBackoff()
        repeat(10) { backoff.nextDelayMillis() }
        backoff.reset()
        assertTrue(backoff.nextDelayMillis() in 500L..1000L)
    }
}
