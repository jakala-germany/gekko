package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbDeviceTest {
    @Test
    fun aDeviceBelowTheMinimumApiIsRejectedWithItsLevelAndSerial() {
        val message = AdbDevice.unsupportedApi("emulator-5554", "23\n")

        assertTrue(message != null && "emulator-5554" in message && "API 23" in message && "API 24" in message)
    }

    @Test
    fun theMinimumApiAndNewerAreAccepted() {
        assertNull(AdbDevice.unsupportedApi("emulator-5554", "24"))
        assertNull(AdbDevice.unsupportedApi("emulator-5554", "36\n"))
    }

    @Test
    fun anUnreadableApiLevelDoesNotBlockTheDevice() {
        assertNull(AdbDevice.unsupportedApi("emulator-5554", ""))
        assertNull(AdbDevice.unsupportedApi("emulator-5554", "error: device offline"))
    }

    @Test
    fun aCmdServiceThatTheDeviceLacksNamesTheApiItNeedsEvenWithExitCodeZero() {
        val noCommand = ProcessResult(0, "No shell command implementation.".encodeToByteArray())
        val noService = ProcessResult(20, "Can't find service: locale".encodeToByteArray())

        val dark = assertFailsWith<IllegalStateException> { AdbDevice.checkCmd(noCommand, "Switching dark mode", 29) }
        val locale =
            assertFailsWith<IllegalStateException> { AdbDevice.checkCmd(noService, "Setting the language", 33) }

        assertEquals("Switching dark mode: this needs Android API 29 or newer on the device", dark.message)
        assertEquals("Setting the language: this needs Android API 33 or newer on the device", locale.message)
    }

    @Test
    fun aFailingCmdKeepsItsOutputAndASuccessfulOneIsAccepted() {
        val failed =
            assertFailsWith<IllegalStateException> {
                AdbDevice.checkCmd(ProcessResult(1, "boom".encodeToByteArray()), "Switching dark mode", 29)
            }

        assertEquals("Switching dark mode: boom", failed.message)
        AdbDevice.checkCmd(ProcessResult(0, "Night mode: yes".encodeToByteArray()), "Switching dark mode", 29)
    }

    @Test
    fun aMissingSerialIsNamedGenerically() {
        assertEquals(
            "The device runs Android API 21; gekko needs an emulator or device with API 24 (Android 7.0) or newer",
            AdbDevice.unsupportedApi(null, "21"),
        )
    }
}
