package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PermissionNamesTest {
    @Test
    fun groupsMapToTheirAndroidPermissions() {
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            PermissionNames.android("location"),
        )
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), PermissionNames.android("Notifications"))
    }

    @Test
    fun fullPermissionNamesPassThrough() {
        assertEquals(listOf("com.example.CUSTOM"), PermissionNames.android("com.example.CUSTOM"))
    }

    @Test
    fun unknownAndroidNamesAreRejected() {
        assertFailsWith<StepFailure> { PermissionNames.android("teleport") }
    }

    @Test
    fun simulatorServicesCoverWhatSimctlCanSet() {
        assertEquals("location", PermissionNames.ios("location"))
        assertEquals("photos", PermissionNames.ios("photos"))
        assertTrue(
            assertFailsWith<StepFailure> { PermissionNames.ios("notifications") }.message.orEmpty().contains("iOS")
        )
    }

    @Test
    fun browserPermissionsAreTheNamesOfPlaywright() {
        assertEquals(listOf("geolocation"), PermissionNames.web("location"))
        assertEquals(listOf("clipboard-read", "clipboard-write"), PermissionNames.web("clipboard"))
        assertEquals(listOf("midi"), PermissionNames.web("midi"))
        assertTrue(assertFailsWith<StepFailure> { PermissionNames.web("teleport") }.message.orEmpty().contains("web"))
    }

    @Test
    fun valuesAreAllowOrDeny() {
        assertEquals(true, PermissionNames.isGranted("allow"))
        assertEquals(false, PermissionNames.isGranted("DENY"))
        assertFailsWith<StepFailure> { PermissionNames.isGranted("maybe") }
    }
}
