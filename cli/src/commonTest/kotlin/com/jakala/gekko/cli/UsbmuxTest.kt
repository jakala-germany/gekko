package com.jakala.gekko.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.UnixSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A usbmuxd that knows one USB iPhone (id 7). A `Connect` to [openPort] turns into a tiny HTTP server that answers
 * `ok`; every other port is refused like a device where nothing listens.
 */
private class FakeUsbmuxd(private val path: String, private val openPort: Int) {
    private val selector = SelectorManager(Dispatchers.Default)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ServerSocket? = null
    val connects = mutableListOf<Pair<Long, Long>>()
    var listCalls = 0

    fun start() {
        runProcess(listOf("rm", "-f", path))
        val listener = runBlocking { aSocket(selector).tcp().bind(UnixSocketAddress(path)) }
        server = listener
        scope.launch {
            while (true) {
                val client = listener.accept()
                launch { handle(client) }
            }
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { server?.close() }
        selector.close()
        runProcess(listOf("rm", "-f", path))
    }

    private suspend fun readRequest(input: ByteReadChannel): Map<*, *> {
        val header = ByteArray(16)
        input.readFully(header, 0, 16)
        var length = 0
        for (index in 3 downTo 0) {
            length = (length shl 8) or (header[index].toInt() and 0xff)
        }
        val payload = ByteArray(length - 16)
        input.readFully(payload, 0, payload.size)
        return Plist.decode(payload) as Map<*, *>
    }

    private suspend fun reply(output: ByteWriteChannel, message: Map<String, Any>) {
        val payload = Plist.encode(message)
        val header = ByteArray(16)
        val length = 16 + payload.size
        for (index in 0 until 4) {
            header[index] = ((length shr (8 * index)) and 0xff).toByte()
        }
        header[4] = 1
        header[8] = 8
        output.writeFully(header + payload)
        output.flush()
    }

    private suspend fun handle(socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        try {
            val request = readRequest(input)
            when (request["MessageType"]) {
                "ListDevices" -> {
                    listCalls++
                    reply(
                        output,
                        mapOf(
                            "DeviceList" to
                                listOf(
                                    mapOf(
                                        "DeviceID" to 7,
                                        "MessageType" to "Attached",
                                        "Properties" to
                                            mapOf(
                                                "ConnectionType" to "USB",
                                                "DeviceID" to 7,
                                                "SerialNumber" to "00008110001A2C3E0E92801E",
                                            ),
                                    ),
                                    mapOf(
                                        "DeviceID" to 8,
                                        "MessageType" to "Attached",
                                        "Properties" to
                                            mapOf(
                                                "ConnectionType" to "Network",
                                                "DeviceID" to 8,
                                                "SerialNumber" to "00008020000011112222333A",
                                            ),
                                    ),
                                )
                        ),
                    )
                }
                "Connect" -> {
                    val deviceId = request["DeviceID"] as Long
                    val port = request["PortNumber"] as Long
                    connects += deviceId to port
                    val swapped = ((openPort and 0xff) shl 8) or ((openPort shr 8) and 0xff)
                    if (port != swapped.toLong()) {
                        reply(output, mapOf("MessageType" to "Result", "Number" to 3))
                        return
                    }
                    reply(output, mapOf("MessageType" to "Result", "Number" to 0))
                    var received = ""
                    val buffer = ByteArray(1024)
                    while (!received.contains("\r\n\r\n")) {
                        val count = input.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) return
                        received += buffer.decodeToString(0, count)
                    }
                    output.writeFully(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".encodeToByteArray()
                    )
                    output.flush()
                }
            }
        } finally {
            socket.close()
        }
    }
}

class UsbmuxTest {
    private val path = "/tmp/gekko-test-usbmuxd-${nowMillis()}.sock"
    private val udid = "00008110-001A2C3E0E92801E"
    private val devicePort = 20_002
    private val daemon = FakeUsbmuxd(path, devicePort)

    @BeforeTest
    fun startDaemon() {
        daemon.start()
    }

    @AfterTest
    fun stopDaemon() {
        daemon.stop()
    }

    @Test
    fun listsTheDevicesAndFindsAUsbOneByUdidWithOrWithoutDash() {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val usbmux = Usbmux(path)
                assertEquals(listOf(7L, 8L), usbmux.devices().map { it.deviceId })
                assertEquals(7L, usbmux.usbDevice(udid)?.deviceId)
                assertEquals(7L, usbmux.usbDevice("00008110001a2c3e0e92801e")?.deviceId)
                // The second device is only on Wi-Fi: its ports cannot be reached
                assertNull(usbmux.usbDevice("00008020-0000-1111-2222-333A"))
                usbmux.close()
            }
        }
    }

    @Test
    fun aConnectionToTheLocalPortReachesThePortOfTheDeviceInNetworkByteOrder() {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val usbmux = Usbmux(path)
                val forward = UsbmuxForward(usbmux, udid, devicePort)
                val client = HttpClient(CIO)
                try {
                    val body = client.get("http://127.0.0.1:${forward.port}/").bodyAsText()

                    assertEquals("ok", body)
                    assertEquals(
                        listOf(7L to (((devicePort and 0xff) shl 8) or (devicePort shr 8)).toLong()),
                        daemon.connects,
                    )
                } finally {
                    client.close()
                    forward.close()
                    usbmux.close()
                }
            }
        }
    }

    @Test
    fun theDeviceIsListedOnceNoMatterHowManyConnectionsFollow() {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val usbmux = Usbmux(path)
                val forward = UsbmuxForward(usbmux, udid, devicePort)
                val client = HttpClient(CIO)
                try {
                    repeat(3) { client.get("http://127.0.0.1:${forward.port}/").bodyAsText() }

                    assertEquals(1, daemon.listCalls)
                    assertEquals(3, daemon.connects.size)
                } finally {
                    client.close()
                    forward.close()
                    usbmux.close()
                }
            }
        }
    }

    @Test
    fun aPortWhereNothingListensIsExplained() {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val usbmux = Usbmux(path)
                val device = usbmux.usbDevice(udid)!!

                val error = assertFailsWith<InfrastructureException> { usbmux.connect(device, 9) }

                assertTrue(error.message.orEmpty().contains("Nothing listens on port 9"))
                usbmux.close()
            }
        }
    }

    @Test
    fun aMissingDaemonIsAnInfrastructureProblemThatNamesTheSocket() {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val usbmux = Usbmux("/tmp/gekko-test-no-such-usbmuxd.sock")

                val error = assertFailsWith<InfrastructureException> { usbmux.devices() }

                assertTrue(error.message.orEmpty().contains("/tmp/gekko-test-no-such-usbmuxd.sock"))
                usbmux.close()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
