package com.jakala.gekko.device

import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HandoffTest {
    @Test
    fun returnsWhatTheOtherThreadComputed() {
        val handoff = Handoff { 21 * 2 }

        thread { handoff.execute() }

        assertEquals(42, handoff.await(5_000, "no answer"))
    }

    @Test
    fun passesOnTheFailureOfTheWork() {
        val handoff = Handoff<Int> { error("boom") }

        thread { handoff.execute() }

        assertEquals("boom", assertFailsWith<IllegalStateException> { handoff.await(5_000, "no answer") }.message)
    }

    @Test
    fun doesNotRunWorkThatCameTooLate() {
        val runs = AtomicInteger()
        val handoff = Handoff { runs.incrementAndGet() }

        val failure = assertFailsWith<IllegalStateException> { handoff.await(50, "Main thread did not respond") }
        handoff.execute()

        assertEquals("Main thread did not respond", failure.message)
        assertEquals(0, runs.get())
    }

    @Test
    fun waitsForWorkThatStartedInTime() {
        val handoff = Handoff {
            Thread.sleep(150)
            "done"
        }

        thread { handoff.execute() }

        assertEquals("done", handoff.await(100, "Main thread did not respond"))
    }
}
