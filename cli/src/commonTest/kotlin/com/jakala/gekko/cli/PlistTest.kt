package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlistTest {
    /** The same dictionary as XML and, converted with `plutil -convert binary1`, as Apple writes a binary plist. */
    private val binaryHex =
        "62706c6973743030d501020304050607131415534269675a4465766963654c697374544e616d6554466c6167545265616c12" +
            "00011170a108d3090a0b0c0d125b4d657373616765547970655a50726f706572746965735844657669636549445841747461" +
            "63686564d30e0f0b1011125e436f6e6e656374696f6e547970655c53657269616c4e756d626572535553425f101830303030" +
            "383131303030314132433345304539323830314510076400430061006600e909233ff800000000000008131722272c313638" +
            "3f4b565f686f7e8b8faaacb5b600000000000001010000000000000016000000000000000000000000000000bf"

    private fun bytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun assertDeviceList(root: Any) {
        val dictionary = root as Map<*, *>
        val device = (dictionary["DeviceList"] as List<*>).single() as Map<*, *>
        val properties = device["Properties"] as Map<*, *>
        assertEquals(7L, device["DeviceID"])
        assertEquals("Attached", device["MessageType"])
        assertEquals("USB", properties["ConnectionType"])
        assertEquals("00008110001A2C3E0E92801E", properties["SerialNumber"])
        assertEquals("Café", dictionary["Name"])
        assertEquals(true, dictionary["Flag"])
        assertEquals(70000L, dictionary["Big"])
        assertEquals(1.5, dictionary["Real"])
    }

    @Test
    fun readsABinaryPlistThatAppleWrote() {
        assertDeviceList(Plist.decode(bytes(binaryHex)))
    }

    @Test
    fun readsAnXmlPlist() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
            <key>DeviceList</key><array><dict>
            <key>DeviceID</key><integer>7</integer>
            <key>MessageType</key><string>Attached</string>
            <key>Properties</key><dict><key>ConnectionType</key><string>USB</string><key>DeviceID</key><integer>7</integer><key>SerialNumber</key><string>00008110001A2C3E0E92801E</string></dict>
            </dict></array>
            <key>Name</key><string>Café</string>
            <key>Flag</key><true/>
            <key>Big</key><integer>70000</integer>
            <key>Real</key><real>1.5</real>
            </dict></plist>
            """
                .trimIndent()

        assertDeviceList(Plist.decode(xml.encodeToByteArray()))
    }

    @Test
    fun whatItWritesItReadsBack() {
        val message =
            mapOf(
                "MessageType" to "Connect",
                "DeviceID" to 7,
                "PortNumber" to 8_270,
                "Names" to listOf("a & b", "<c>"),
                "On" to true,
                "Empty" to emptyList<String>(),
            )

        val read = Plist.decode(Plist.encode(message)) as Map<*, *>

        assertEquals("Connect", read["MessageType"])
        assertEquals(7L, read["DeviceID"])
        assertEquals(8_270L, read["PortNumber"])
        assertEquals(listOf("a & b", "<c>"), read["Names"])
        assertEquals(true, read["On"])
        assertEquals(emptyList<Any>(), read["Empty"])
    }

    @Test
    fun somethingThatIsNoPlistIsAnErrorThatShowsWhatCameBack() {
        val error = assertFailsWith<IllegalStateException> { Plist.decode("HTTP/1.1 400".encodeToByteArray()) }

        assertTrue(error.message.orEmpty().contains("HTTP/1.1 400"))
    }

    @Test
    fun aTruncatedBinaryPlistIsAnErrorAndNotACrash() {
        assertFailsWith<IllegalStateException> { Plist.decode(bytes(binaryHex).copyOf(20)) }
    }
}
