package com.jakala.gekko.cli

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.UnixSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A device the usbmuxd daemon of the Mac knows about. [serial] is its UDID without dashes. */
class UsbmuxDevice(val deviceId: Long, val serial: String, val connectionType: String) {
    val isUsb: Boolean
        get() = connectionType.equals("USB", ignoreCase = true)
}

/** An open stream to a port of a device. A socket's channels can be opened only once, so the handshake's are kept. */
class UsbmuxConnection(val socket: Socket, val input: ByteReadChannel, val output: ByteWriteChannel) {
    fun close() {
        socket.close()
    }
}

/**
 * The usbmuxd daemon of macOS (`/var/run/usbmuxd`): the same service Xcode and iTunes use to reach a USB-attached
 * iPhone. A connection through it ends at a port on the device's own loopback, so nothing needs to listen on the
 * network. Messages are a 16 byte little-endian header (length, version, type, tag) and a property list.
 */
class Usbmux(private val socketPath: String = DEFAULT_SOCKET) {
    companion object {
        const val DEFAULT_SOCKET = "/var/run/usbmuxd"
        private const val VERSION = 1L
        private const val TYPE_PLIST = 8L
        private const val HEADER_BYTES = 16
        private const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
        private const val RESULT_DEVICE_NOT_CONNECTED = 2L
        private const val RESULT_REFUSED = 3L

        /** UDIDs are written with and without a dash after the eighth character; compare them without. */
        fun normalize(udid: String): String {
            return udid.replace("-", "").uppercase()
        }
    }

    private val selector = SelectorManager(Dispatchers.Default)

    fun close() {
        selector.close()
    }

    private fun request(type: String, extra: Map<String, Any> = emptyMap()): ByteArray {
        val message =
            linkedMapOf<String, Any>(
                "MessageType" to type,
                "ClientVersionString" to "gekko",
                "ProgName" to "gekko",
                "kLibUSBMuxVersion" to 3,
            )
        message.putAll(extra)
        val payload = Plist.encode(message)
        val header = ByteArray(HEADER_BYTES)
        littleEndian(header, 0, (HEADER_BYTES + payload.size).toLong())
        littleEndian(header, 4, VERSION)
        littleEndian(header, 8, TYPE_PLIST)
        littleEndian(header, 12, 1)
        return header + payload
    }

    private fun littleEndian(target: ByteArray, at: Int, value: Long) {
        for (index in 0 until 4) {
            target[at + index] = ((value shr (8 * index)) and 0xff).toByte()
        }
    }

    private suspend fun open(): Socket {
        return try {
            aSocket(selector).tcp().connect(UnixSocketAddress(socketPath))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw InfrastructureException(
                "Cannot reach usbmuxd at $socketPath (${error.message}); is a Mac with Xcode and a USB cable in use?"
            )
        }
    }

    private suspend fun readReply(input: ByteReadChannel): Map<*, *> {
        val header = ByteArray(HEADER_BYTES)
        input.readFully(header, 0, HEADER_BYTES)
        var length = 0L
        for (index in 3 downTo 0) {
            length = (length shl 8) or (header[index].toLong() and 0xff)
        }
        check(length in HEADER_BYTES..MAX_MESSAGE_BYTES) { "usbmuxd sent a message of $length bytes" }
        val payload = ByteArray((length - HEADER_BYTES).toInt())
        input.readFully(payload, 0, payload.size)
        return Plist.decode(payload) as? Map<*, *> ?: error("usbmuxd did not answer with a dictionary")
    }

    /** Every device the daemon lists, USB and network ones. */
    suspend fun devices(): List<UsbmuxDevice> {
        val socket = open()
        try {
            val output = socket.openWriteChannel(autoFlush = true)
            output.writeFully(request("ListDevices"))
            val reply = readReply(socket.openReadChannel())
            val list = reply["DeviceList"] as? List<*> ?: return emptyList()
            return list.mapNotNull { entry ->
                val properties = (entry as? Map<*, *>)?.get("Properties") as? Map<*, *> ?: return@mapNotNull null
                val id = (properties["DeviceID"] as? Long) ?: ((entry as Map<*, *>)["DeviceID"] as? Long)
                val serial = properties["SerialNumber"] as? String
                if (id == null || serial == null) null
                else UsbmuxDevice(id, normalize(serial), properties["ConnectionType"] as? String ?: "")
            }
        } finally {
            socket.close()
        }
    }

    /** The USB device with this UDID (dashes and case do not matter), or null. */
    suspend fun usbDevice(udid: String): UsbmuxDevice? {
        val wanted = normalize(udid)
        return devices().firstOrNull { it.isUsb && it.serial == wanted }
    }

    /**
     * Opens a stream to [port] on the loopback of the device. The returned connection carries the bytes from here on;
     * it is closed by the caller.
     */
    suspend fun connect(device: UsbmuxDevice, port: Int): UsbmuxConnection {
        val socket = open()
        try {
            val output = socket.openWriteChannel(autoFlush = true)
            val input = socket.openReadChannel()
            // The daemon wants the port in network byte order
            val networkPort = ((port and 0xff) shl 8) or ((port shr 8) and 0xff)
            output.writeFully(request("Connect", mapOf("DeviceID" to device.deviceId, "PortNumber" to networkPort)))
            val reply = readReply(input)
            val result = reply["Number"] as? Long ?: error("usbmuxd answered a connect without a result")
            if (result != 0L) {
                socket.close()
                throw InfrastructureException(
                    when (result) {
                        RESULT_REFUSED ->
                            "Nothing listens on port $port of the device (the app or the system runner is not running)"
                        RESULT_DEVICE_NOT_CONNECTED -> "The device ${device.serial} is not connected over USB any more"
                        else -> "usbmuxd refused the connection to port $port of the device (error $result)"
                    }
                )
            }
            return UsbmuxConnection(socket, input, output)
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }
}

/**
 * Makes a port of a USB-attached iPhone reachable as `127.0.0.1:<port>` for as long as this process lives, so the HTTP
 * client of the CLI works the same as for simulators. Every connection to the local port gets a connection of its own
 * to the device.
 */
class UsbmuxForward(private val usbmux: Usbmux, private val udid: String, private val devicePort: Int) {
    private val selector = SelectorManager(Dispatchers.Default)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ServerSocket? = null

    /** The daemon's id of the device; kept so a connection does not list the devices again, dropped when it fails. */
    private var target: UsbmuxDevice? = null

    /** The local port; the first call starts the listener. */
    val port: Int by lazy { start() }

    private fun start(): Int {
        val listener = runBlocking {
            aSocket(selector).tcp().bind(io.ktor.network.sockets.InetSocketAddress("127.0.0.1", 0))
        }
        server = listener
        scope.launch {
            while (true) {
                val client = listener.accept()
                launch { serve(client) }
            }
        }
        return (listener.localAddress as io.ktor.network.sockets.InetSocketAddress).port
    }

    private suspend fun serve(client: Socket) {
        var device: UsbmuxConnection? = null
        try {
            val known =
                target
                    ?: usbmux.usbDevice(udid)
                    ?: throw InfrastructureException("Device $udid is not attached over USB")
            target = known
            device = usbmux.connect(known, devicePort)
            val clientInput = client.openReadChannel()
            val clientOutput = client.openWriteChannel(autoFlush = true)
            coroutineScope {
                val toDevice = launch { pipe(clientInput, device.output) }
                val toClient = launch { pipe(device.input, clientOutput) }
                // One side ending ends the connection, so a client never waits on a stream nobody feeds
                toDevice.invokeOnCompletion { toClient.cancel() }
                toClient.invokeOnCompletion { toDevice.cancel() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The client sees a closed connection and reports it; the device may have been replugged, so look again
            target = null
        } finally {
            runCatching { client.close() }
            runCatching { device?.close() }
        }
    }

    private suspend fun pipe(from: ByteReadChannel, to: ByteWriteChannel) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = from.readAvailable(buffer, 0, buffer.size)
            if (count < 0) {
                return
            }
            to.writeFully(buffer, 0, count)
            to.flush()
        }
    }

    fun close() {
        scope.cancel()
        runCatching { server?.close() }
    }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
    }
}
