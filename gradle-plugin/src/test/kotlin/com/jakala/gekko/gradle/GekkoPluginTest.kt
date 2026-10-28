package com.jakala.gekko.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GekkoPluginTest {
    @Test
    fun theAgentComesFromTheSameGroupAndTheVersionOfThePlugin() {
        assertEquals(
            "io.github.jakala-germany:gekko-agent-android:${PluginVersion.NAME}",
            GekkoPlugin.agentNotation(PluginVersion.NAME),
        )
    }

    @Test
    fun theVersionOfThePluginIsABuildVersion() {
        assertTrue(Regex("\\d+\\.\\d+\\.\\d+(-.+)?").matches(PluginVersion.NAME), PluginVersion.NAME)
    }
}
