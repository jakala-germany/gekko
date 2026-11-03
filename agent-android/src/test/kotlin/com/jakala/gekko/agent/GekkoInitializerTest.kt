package com.jakala.gekko.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GekkoInitializerTest {
    @Test
    fun theProcessOfTheAppIsTheMainProcess() {
        assertTrue(GekkoInitializer.isMainProcess("com.example.app", "com.example.app"))
    }

    @Test
    fun aServiceInItsOwnProcessIsNot() {
        assertFalse(GekkoInitializer.isMainProcess("com.example.app", "com.example.app:push"))
    }

    @Test
    fun aProcessWhoseNameIsNotKnownCountsAsTheApp() {
        assertTrue(GekkoInitializer.isMainProcess(null, "com.example.app:push"))
        assertTrue(GekkoInitializer.isMainProcess("com.example.app", null))
    }
}
