package com.sivanalluri.maclink.companion.connection

/** Accessed under the connection owner's lock, including by timer callbacks. */
internal class DeadlineToken {
    private var revision = 0L
    fun invalidate(): Long = ++revision
    fun isCurrent(token: Long): Boolean = token == revision
}
