package com.jakala.gekko.agent

import android.os.Handler
import android.os.Looper
import com.jakala.gekko.device.Handoff

internal object MainThread {
    private val handler = Handler(Looper.getMainLooper())

    /** Work that the main thread did not start within the timeout is cancelled, so it cannot act later on its own. */
    fun <T> run(block: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            return block()
        }
        val handoff = Handoff(block)
        handler.post { handoff.execute() }
        return handoff.await(MAIN_TIMEOUT_MS, "Main thread did not respond")
    }

    private const val MAIN_TIMEOUT_MS = 10_000L
}
