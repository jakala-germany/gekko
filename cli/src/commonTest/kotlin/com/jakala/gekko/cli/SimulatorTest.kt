package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatorTest {
    private val listing =
        """
        {
          "devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-18-0": [
              { "state": "Booted", "udid": "11111111-1111-1111-1111-111111111111", "name": "iPhone 16" },
              { "state": "Shutdown", "udid": "22222222-2222-2222-2222-222222222222", "name": "iPhone SE" }
            ],
            "com.apple.CoreSimulator.SimRuntime.tvOS-18-0": [
              { "state": "Booted", "udid": "33333333-3333-3333-3333-333333333333", "name": "Apple TV 4K" }
            ],
            "com.apple.CoreSimulator.SimRuntime.watchOS-11-0": []
          }
        }
        """

    @Test
    fun theBootedSimulatorsComeWithTheRuntimeTheyRun() {
        val booted = BootedSimulator.parse(listing)

        assertEquals(
            listOf(
                BootedSimulator("11111111-1111-1111-1111-111111111111", "com.apple.CoreSimulator.SimRuntime.iOS-18-0"),
                BootedSimulator("33333333-3333-3333-3333-333333333333", "com.apple.CoreSimulator.SimRuntime.tvOS-18-0"),
            ),
            booted,
        )
    }

    @Test
    fun anAppleTvIsTheSimulatorOfATvosRuntime() {
        val booted = BootedSimulator.parse(listing)

        assertEquals(listOf(false, true), booted.map { it.isTv })
    }

    @Test
    fun outputThatIsNoJsonIsNoSimulator() {
        assertTrue(BootedSimulator.parse("").isEmpty())
        assertTrue(BootedSimulator.parse("CoreSimulator is out of date. Current version (1) is older").isEmpty())
        assertTrue(BootedSimulator.parse("{}").isEmpty())
        assertTrue(BootedSimulator.parse("""{ "devices": [] }""").isEmpty())
        assertTrue(BootedSimulator.parse("[]").isEmpty())
        assertTrue(BootedSimulator.parse("""{ "devices": { "r": [ 1, { "udid": {} } ] } }""").isEmpty())
    }
}
