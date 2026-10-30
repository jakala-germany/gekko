package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicectlJsonTest {
    /** The shape of `devicectl list devices` (Xcode 27): `properties` next to the deprecated per-area fields. */
    private val devices =
        """
        {"info":{"jsonVersion":5,"outcome":"success"},
         "result":{"devices":[
          {"identifier":"053A0353-D3FC-45B2-8BDB-FAB364B8CA5A",
           "hardwareProperties":{"platform":"iOS","reality":"simulated","udid":"053A0353-D3FC-45B2-8BDB-FAB364B8CA5A"},
           "deviceProperties":{"name":"iPhone 17"},
           "properties":{"hardware":{"platform":"iOS","reality":"simulated","udid":"053A0353-D3FC-45B2-8BDB-FAB364B8CA5A"},
                         "device":{"name":"iPhone 17"}}},
          {"identifier":"6F1E0C2A-0000-4000-8000-000000000001",
           "properties":{"hardware":{"platform":"iOS","reality":"physical","udid":"00008110-001A2C3E0E92801E","marketingName":"iPhone 15"},
                         "device":{"name":"Ada's iPhone"},
                         "connection":{"state":"disconnected","pairingState":"paired"}}},
          {"identifier":"6F1E0C2A-0000-4000-8000-000000000002",
           "hardwareProperties":{"platform":"iOS","reality":"physical","udid":"00008030-000A1B2C3D4E802E","marketingName":"iPad"},
           "deviceProperties":{"name":"Old iPad"}},
          {"identifier":"6F1E0C2A-0000-4000-8000-000000000003",
           "properties":{"hardware":{"platform":"watchOS","reality":"physical","udid":"00008006-0000AAAA"},"device":{"name":"Watch"}}}
         ]}}
        """
            .trimIndent()

    @Test
    fun listsPhysicalIosDevicesOfBothJsonShapesAndNoSimulatorsOrWatches() {
        val found = DevicectlJson.physicalDevices(devices)

        assertEquals(listOf("00008110-001A2C3E0E92801E", "00008030-000A1B2C3D4E802E"), found.map { it.udid })
        assertEquals(listOf("Ada's iPhone", "Old iPad"), found.map { it.name })
    }

    @Test
    fun aDeviceListWithoutDevicesIsEmpty() {
        assertEquals(emptyList(), DevicectlJson.physicalDevices("""{"result":{"devices":[]}}"""))
    }

    private val apps =
        """
        {"result":{"apps":[
          {"bundleIdentifier":"com.example.app","name":"Example","url":"file:///private/var/containers/Bundle/Application/ABC-1/Example.app/","version":"1.0"},
          {"bundleIdentifier":"com.example.other","name":"Other","installationURL":"file:///private/var/containers/Bundle/Application/DEF-2/Other.app/"},
          {"bundleIdentifier":"com.example.odd","name":"Odd","location":"file:///private/var/containers/Bundle/Application/GHI-3/Odd.app/"}
        ]}}
        """
            .trimIndent()

    @Test
    fun findsWhereAnInstalledAppLivesWhateverTheKeyIsCalled() {
        assertEquals(
            "/var/containers/Bundle/Application/ABC-1/Example.app",
            DevicectlJson.appRoot(apps, "com.example.app"),
        )
        assertEquals(
            "/var/containers/Bundle/Application/DEF-2/Other.app",
            DevicectlJson.appRoot(apps, "com.example.other"),
        )
        assertEquals(
            "/var/containers/Bundle/Application/GHI-3/Odd.app",
            DevicectlJson.appRoot(apps, "com.example.odd"),
        )
    }

    @Test
    fun anAppThatIsNotInstalledHasNoRoot() {
        assertNull(DevicectlJson.appRoot(apps, "com.example.missing"))
        assertFalse(DevicectlJson.isInstalled(apps, "com.example.missing"))
        assertTrue(DevicectlJson.isInstalled(apps, "com.example.app"))
    }

    private val processes =
        """
        {"result":{"runningProcesses":[
          {"executable":"file:///usr/libexec/backboardd","processIdentifier":55},
          {"executable":"file:///private/var/containers/Bundle/Application/ABC-1/Example.app/Example","processIdentifier":4321},
          {"executable":"file:///private/var/containers/Bundle/Application/DEF-2/Other.app/Other","processIdentifier":777}
        ]}}
        """
            .trimIndent()

    @Test
    fun findsTheProcessOfAnAppByItsExecutableInsideTheAppFolder() {
        assertEquals(4321L, DevicectlJson.processId(processes, "/var/containers/Bundle/Application/ABC-1/Example.app"))
        assertEquals(
            777L,
            DevicectlJson.processId(processes, "file:///private/var/containers/Bundle/Application/DEF-2/Other.app/"),
        )
    }

    @Test
    fun anAppThatIsNotRunningHasNoProcess() {
        assertNull(DevicectlJson.processId(processes, "/var/containers/Bundle/Application/ZZZ-9/Gone.app"))
    }

    @Test
    fun anAppFolderIsNotConfusedWithOneThatOnlySharesItsPrefix() {
        assertNull(DevicectlJson.processId(processes, "/var/containers/Bundle/Application/ABC-1/Exam.app"))
    }

    @Test
    fun readsTheAppearanceWhereverTheStyleIsNamed() {
        assertEquals("dark", DevicectlJson.appearance("""{"result":{"userInterfaceStyle":"dark"}}"""))
        assertEquals("light", DevicectlJson.appearance("""{"result":{"appearance":{"mode":"Light"}}}"""))
        assertNull(DevicectlJson.appearance("""{"result":{"textSize":"large"}}"""))
    }

    @Test
    fun pathsWithAndWithoutPrivateAndSchemeAreTheSamePlace() {
        assertEquals("/var/containers/a.app", DevicectlJson.pathOf("file:///private/var/containers/a.app/"))
        assertEquals("/var/containers/a.app", DevicectlJson.pathOf("/var/containers/a.app"))
    }
}
