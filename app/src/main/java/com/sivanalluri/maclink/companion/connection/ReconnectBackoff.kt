package com.sivanalluri.maclink.companion.connection

import kotlin.random.Random

/** Bounded jitter prevents peers from repeatedly reconnecting in lockstep. */
class ReconnectBackoff {
    private var attempt = 0

    fun nextDelayMillis(random: Random = Random.Default): Long {
        val ceiling = minOf(30_000L, 1_000L shl attempt.coerceAtMost(5))
        attempt = (attempt + 1).coerceAtMost(5)
        return random.nextLong(ceiling / 2, ceiling + 1)
    }

    fun reset() { attempt = 0 }
}
