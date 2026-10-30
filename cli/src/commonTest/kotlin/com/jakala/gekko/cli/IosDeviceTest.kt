package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosDeviceTest {
    private val iphone = "00008110-001A2C3E0E92801E"

    @Test
    fun aUdidIsRecognisedWithAndWithoutDashAndInAnyCase() {
        assertTrue(PhysicalIosDevice.looksLikeUdid(iphone))
        assertTrue(PhysicalIosDevice.looksLikeUdid("00008110001a2c3e0e92801e"))
        assertTrue(PhysicalIosDevice.looksLikeUdid("a".repeat(40)))
    }

    @Test
    fun anAdbSerialOrASimulatorNameIsNoPhoneUdid() {
        assertFalse(PhysicalIosDevice.looksLikeUdid("emulator-5554"))
        assertFalse(PhysicalIosDevice.looksLikeUdid("R58M12ABCDE"))
        assertFalse(PhysicalIosDevice.looksLikeUdid("192.168.1.20:5555"))
    }

    @Test
    fun usbmuxdWritesTheUdidWithoutDashes() {
        assertEquals("00008110001A2C3E0E92801E", Usbmux.normalize(iphone))
        assertEquals(Usbmux.normalize(iphone), Usbmux.normalize("00008110001a2c3e0e92801e"))
    }

    @Test
    fun allIncludesThePhonesAfterTheSimulators() {
        val refs = DeviceResolver.resolve(listOf("all"), listOf("R5CT"), listOf("AAAA-1111"), listOf(iphone))

        assertEquals(listOf(Platform.Android, Platform.Ios, Platform.Ios), refs.map { it.platform })
        assertEquals(listOf(false, false, true), refs.map { it.physical })
        assertEquals(iphone, refs.last().id)
    }

    @Test
    fun aPhoneOnlyIsEnoughForAll() {
        val refs = DeviceResolver.resolve(listOf("all"), emptyList(), emptyList(), listOf(iphone))

        assertEquals(listOf(iphone), refs.map { it.id })
    }

    @Test
    fun aPhoneIsFoundByItsUdidInAnyWriting() {
        val refs = DeviceResolver.resolve(listOf("00008110001a2c3e0e92801e"), emptyList(), emptyList(), listOf(iphone))

        assertEquals(iphone, refs.single().id)
        assertTrue(refs.single().physical)
        assertEquals(Platform.Ios, refs.single().platform)
    }

    @Test
    fun anUnknownDeviceNamesThePhonesToo() {
        val error =
            assertFailsWith<IllegalStateException> {
                DeviceResolver.resolve(listOf("nope"), emptyList(), emptyList(), listOf(iphone))
            }

        assertTrue(error.message.orEmpty().contains(iphone))
    }

    @Test
    fun theDeviceKeysOfGekkoYamlAreRead() {
        val config =
            Yamls.parse(
                GekkoConfig.serializer(),
                """
                app: { ios: com.example.app }
                ios:
                  deviceApp: builds/App.ipa
                  deviceRunnerDir: installed
                """
                    .trimIndent(),
            )

        assertEquals("builds/App.ipa", config.ios.deviceApp)
        assertEquals("installed", config.ios.deviceRunnerDir)
    }

    @Test
    fun deviceBuildsAreFoundRelativeToGekkoYaml() {
        val config = GekkoConfig(ios = IosConfig(deviceApp = "builds/App.ipa", deviceRunnerDir = "installed"))

        val moved = config.withPathsRelativeTo("/work/tests")

        assertEquals("/work/tests/builds/App.ipa", moved.ios.deviceApp)
        assertEquals("installed", moved.ios.deviceRunnerDir)
    }

    @Test
    fun languageAndTimeFormatReachTheAppAsLaunchArguments() {
        val arguments = AppleBuilds.settingsArguments(DeviceSettings(language = "de-DE", timeFormat = "24h"))

        assertEquals(
            listOf("-AppleLanguages", "(de-DE)", "-AppleLocale", "de_DE", "-AppleICUForce24HourTime", "YES"),
            arguments,
        )
        assertEquals(emptyList(), AppleBuilds.settingsArguments(DeviceSettings()))
    }

    @Test
    fun withoutAnXctestrunTheRunnerBundlesAreTheOnesOfARelease() {
        assertEquals(
            listOf(AppleBuilds.RUNNER_HOST_BUNDLE, AppleBuilds.RUNNER_TESTS_BUNDLE),
            AppleBuilds.runnerBundleIds("/tmp/gekko-no-such-runner-dir"),
        )
    }

    @Test
    fun theRunnerBundlesOfATeamBuildAreReadFromTheFlatXctestrun() {
        // The layout xcodebuild wrote for the device runner (format version 1)
        val json =
            """
            {"__xctestrun_metadata__":{"FormatVersion":1},
             "GekkoRunnerUITests":{
               "TestHostBundleIdentifier":"gekko.abc123.runneruitests.xctrunner",
               "UITargetAppPath":"__TESTROOT__/Debug-iphoneos/GekkoRunnerHost.app",
               "TestHostPath":"__TESTROOT__/Debug-iphoneos/GekkoRunnerUITests-Runner.app"}}
            """
                .trimIndent()

        assertEquals(
            listOf("gekko.abc123.runneruitests.xctrunner"),
            AppleBuilds.bundleIdsIn(json, "/tmp/gekko-no-such-runner-dir"),
        )
    }

    @Test
    fun theRunnerBundlesAreReadFromTheNestedXctestrunToo() {
        val json =
            """
            {"TestConfigurations":[{"TestTargets":[{"TestHostBundleIdentifier":"x.y.runneruitests.xctrunner"}]}]}
            """
                .trimIndent()

        assertEquals(listOf("x.y.runneruitests.xctrunner"), AppleBuilds.bundleIdsIn(json, "/tmp/none"))
        assertEquals(emptyList(), AppleBuilds.bundleIdsIn("not json", "/tmp/none"))
    }

    @Test
    fun aPhoneHasNoRecordingNoPermissionsAndNoKeys() {
        val phone = PhysicalIosDevice(iphone)

        assertTrue(
            assertFailsWith<IllegalStateException> { phone.startRecording() }.message.orEmpty().contains("recording")
        )
        assertTrue(
            assertFailsWith<IllegalStateException> { phone.setPermissions(GekkoConfig(), mapOf("location" to true)) }
                .message
                .orEmpty()
                .contains("system: true")
        )
        assertTrue(assertFailsWith<IllegalStateException> { phone.pressKey("back") }.message.orEmpty().contains("iOS"))
    }
}
