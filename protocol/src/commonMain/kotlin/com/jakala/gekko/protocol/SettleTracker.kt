package com.jakala.gekko.protocol

/**
 * Tells when a screen is "done": nothing changed for [settleMs] and no progress indicator is shown. The wait for an
 * element then ends early instead of running into the full timeout, which only has to cover a screen that is still
 * loading. [settleMs] of 0 turns this off.
 */
class SettleTracker(private val settleMs: Long, startedAt: Long) {
    private var lastSignature: Int? = null
    private var lastActivityAt = startedAt

    fun observe(signature: Int, busy: Boolean, now: Long) {
        if (busy || signature != lastSignature) {
            lastSignature = signature
            lastActivityAt = now
        }
    }

    fun isSettled(now: Long): Boolean {
        return settleMs > 0 && now - lastActivityAt >= settleMs
    }
}
