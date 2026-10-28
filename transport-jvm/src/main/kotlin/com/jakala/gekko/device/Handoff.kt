package com.jakala.gekko.device

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Work that one thread hands to another (the main thread of an app) and waits for. When the waiting side gives up, the
 * work is cancelled: if the other thread gets to it later it does nothing, so a step that was reported as failed leaves
 * no tap or text behind.
 */
class Handoff<T>(private val block: () -> T) {
    private val state = AtomicInteger(PENDING)
    private val done = CountDownLatch(1)

    @Volatile private var result: Result<T>? = null

    /** Called on the thread that does the work. */
    fun execute() {
        if (!state.compareAndSet(PENDING, RUNNING)) {
            return
        }
        result = runCatching(block)
        done.countDown()
    }

    /** Called on the waiting thread; throws when the work did not start within [timeoutMs]. */
    fun await(timeoutMs: Long, what: String): T {
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (state.compareAndSet(PENDING, CANCELLED)) {
                throw IllegalStateException(what)
            }
            // The work started in time and is under way; it cannot be taken back, so its end is awaited
            check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { what }
        }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val CANCELLED = 2
    }
}
