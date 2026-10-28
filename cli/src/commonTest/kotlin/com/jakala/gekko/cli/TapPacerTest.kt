package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class TapPacerTest {
    private var now = 0L
    private val pauses = mutableListOf<Long>()

    private fun pacer(intervalMs: Long): TapPacer {
        return TapPacer(
            intervalMs,
            clock = { now },
            pause = {
                pauses += it
                now += it
            },
        )
    }

    @Test
    fun defaultDoesNotPause() = runTest {
        val pacer = pacer(0)

        pacer.beforeTap()
        pacer.beforeTap()

        assertEquals(emptyList(), pauses)
    }

    @Test
    fun firstTapIsNeverDelayed() = runTest {
        pacer(650).beforeTap()

        assertEquals(emptyList(), pauses)
    }

    @Test
    fun secondTapWaitsForTheRestOfTheInterval() = runTest {
        val pacer = pacer(650)
        pacer.beforeTap()
        now += 200

        pacer.beforeTap()

        assertEquals(listOf(450L), pauses)
    }

    @Test
    fun noPauseWhenEnoughTimePassed() = runTest {
        val pacer = pacer(650)
        pacer.beforeTap()
        now += 1_000

        pacer.beforeTap()

        assertEquals(emptyList(), pauses)
    }

    @Test
    fun configDefaultsToNoPacing() {
        val config = Yamls.parse(GekkoConfig.serializer(), "app: { android: x }")
        val paced = Yamls.parse(GekkoConfig.serializer(), "tapIntervalMs: 650")

        assertEquals(0L, config.tapIntervalMs)
        assertEquals(650L, paced.tapIntervalMs)
        assertNull(config.app.ios)
    }
}
