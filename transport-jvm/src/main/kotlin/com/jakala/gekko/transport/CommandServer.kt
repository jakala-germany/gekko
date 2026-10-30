package com.jakala.gekko.transport

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json

/** One client that connected to a [Listener]. */
interface ClientConnection : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Makes a read wait at most [millis] before it fails; connections that cannot time out keep the default. */
    fun setReadTimeout(millis: Int) {}
}

/** Where a [CommandServer] gets its clients from: a loopback port, or a private socket inside an Android app. */
interface Listener {
    /** False once the listener is shut down. */
    val isOpen: Boolean

    /** Blocks until a permitted client connects; null when the listener was closed in the meantime. */
    fun accept(): ClientConnection?
}

/** Who may talk to a server on a device: the adb daemon (shell), root or the app itself, never another app. */
object Peers {
    const val ROOT_UID = 0
    const val SHELL_UID = 2000

    /** [uid] is what the kernel reports for the peer of a socket (negative when unknown), [ownUid] the server's. */
    fun isAllowed(uid: Int, ownUid: Int): Boolean {
        return uid >= 0 && (uid == ROOT_UID || uid == SHELL_UID || uid == ownUid)
    }
}

class SocketConnection(private val socket: Socket) : ClientConnection {
    override val input: InputStream
        get() = socket.getInputStream()

    override val output: OutputStream
        get() = socket.getOutputStream()

    override fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    override fun close() {
        socket.close()
    }
}

/** Accepts clients on a port of the loopback interface; [port] 0 picks a free one. */
class TcpListener(port: Int) : Listener {
    private val server = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))

    val port: Int
        get() = server.localPort

    override val isOpen: Boolean
        get() = !server.isClosed

    override fun accept(): ClientConnection? {
        while (isOpen) {
            try {
                return SocketConnection(server.accept())
            } catch (error: IOException) {
                // closed while waiting, or a client that went away during the handshake
            }
        }
        return null
    }

    fun close() {
        server.close()
    }

    private companion object {
        const val BACKLOG = 8
    }
}

/**
 * A small HTTP/1.1 server for [Command] posts. It is reachable by every process that can connect to its socket, so it
 * keeps requests small, bounded in time and in number, and refuses browsers: a page the tester opens must not be able
 * to drive the app through a loopback port (cross-site requests carry an `Origin`, DNS rebinding a foreign `Host`).
 */
class CommandServer(
    private val listener: Listener,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val handler: (Command) -> Response,
) {
    constructor(
        listener: Listener,
        handler: (Command) -> Response,
    ) : this(listener, MAX_CONNECTIONS, READ_TIMEOUT_MS, handler)

    constructor(port: Int, handler: (Command) -> Response) : this(TcpListener(port), handler)

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        // Newer CLIs may send fields an older agent or runner does not know yet
        ignoreUnknownKeys = true
    }

    private val active = AtomicInteger()

    fun start() {
        thread(name = "gekko-server", isDaemon = true) {
            while (listener.isOpen) {
                val connection = listener.accept() ?: break
                if (active.incrementAndGet() > maxConnections) {
                    active.decrementAndGet()
                    refuse(connection)
                    continue
                }
                thread(name = "gekko-conn", isDaemon = true) {
                    runCatching { serve(connection) }
                    // The slot is free before the client sees the connection close, so its next connection is served
                    active.decrementAndGet()
                    runCatching { connection.close() }
                }
            }
        }
    }

    private fun refuse(connection: ClientConnection) {
        runCatching {
            connection.use {
                writeError(it.output, HttpRejection(STATUS_UNAVAILABLE, "Too many connections to the gekko server"))
            }
        }
    }

    private fun serve(connection: ClientConnection) {
        val timed = TimedInput(connection, readTimeoutMs)
        val input = BufferedInputStream(timed)
        while (true) {
            timed.beginRequest()
            val request =
                try {
                    readRequest(input, timed) ?: return
                } catch (rejection: HttpRejection) {
                    writeError(connection.output, rejection)
                    return
                } catch (error: IOException) {
                    // Silence between requests is a client that left; silence inside one is answered
                    if (timed.started) {
                        writeError(
                            connection.output,
                            HttpRejection(STATUS_TIMEOUT, "The request did not arrive in time"),
                        )
                    }
                    return
                }
            val response = json.encodeToString<Response>(answer(request.body))
            writeResponse(connection.output, STATUS_OK, response, request.keepAlive)
            if (!request.keepAlive) {
                return
            }
        }
    }

    private fun answer(body: String): Response {
        val command =
            try {
                json.decodeFromString<Command>(body)
            } catch (error: IllegalArgumentException) {
                // Unknown type, missing field or no JSON at all (a SerializationException is one of these)
                return Response.Error(error.message ?: error.toString(), ErrorKind.UNSUPPORTED)
            }
        return runCatching { handler(command) }.getOrElse { Response.Error(it.message ?: it.toString()) }
    }

    /** Null when the client closed the connection before it started a request. */
    private fun readRequest(input: BufferedInputStream, timed: TimedInput): Request? {
        val head = readHead(input, timed) ?: return null
        val requestLine = head.first().split(' ').filter { it.isNotEmpty() }
        if (requestLine.size < 2) {
            throw HttpRejection(STATUS_BAD_REQUEST, "Malformed request line")
        }
        val headers = parseHeaders(head.drop(1))
        if (headers["host"].any { !isLoopbackHost(it) }) {
            throw HttpRejection(
                STATUS_FORBIDDEN,
                "Only loopback hosts may talk to the gekko server",
                ErrorKind.UNSUPPORTED,
            )
        }
        if (headers["origin"].isNotEmpty()) {
            throw HttpRejection(STATUS_FORBIDDEN, "Browser requests are not served", ErrorKind.UNSUPPORTED)
        }
        if (headers["transfer-encoding"].isNotEmpty()) {
            throw HttpRejection(STATUS_BAD_REQUEST, "Chunked bodies are not supported", ErrorKind.UNSUPPORTED)
        }
        val length = contentLength(headers["content-length"])
        val body = readBody(input, length)
        val keepAlive = headers["connection"].none { it.equals("close", ignoreCase = true) }
        return Request(body, keepAlive)
    }

    /** The request line and the headers; stops reading once [MAX_HEAD_BYTES] are exceeded. */
    private fun readHead(input: BufferedInputStream, timed: TimedInput): List<String>? {
        val lines = mutableListOf<String>()
        val line = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val next = input.read()
            if (next < 0) {
                return null
            }
            if (total == 0) {
                timed.startRequest()
            }
            total++
            if (total > MAX_HEAD_BYTES) {
                throw HttpRejection(STATUS_TOO_LARGE, "The request head is larger than $MAX_HEAD_BYTES bytes")
            }
            if (next != '\n'.code) {
                line.write(next)
                continue
            }
            // String(bytes, charset) instead of ByteArrayOutputStream.toString(Charset), which Android has from API 33
            val text = String(line.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
            line.reset()
            if (text.isNotEmpty()) {
                lines += text
            } else if (lines.isNotEmpty()) {
                return lines
            }
        }
    }

    private fun parseHeaders(lines: List<String>): Headers {
        val values = mutableMapOf<String, MutableList<String>>()
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) {
                throw HttpRejection(STATUS_BAD_REQUEST, "Malformed header")
            }
            values.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() } +=
                line.substring(colon + 1).trim()
        }
        return Headers(values)
    }

    private fun contentLength(values: List<String>): Int {
        if (values.isEmpty()) {
            return 0
        }
        if (values.distinct().size > 1 || values.first().isEmpty() || !values.first().all { it in '0'..'9' }) {
            throw HttpRejection(STATUS_BAD_REQUEST, "Invalid Content-Length", ErrorKind.UNSUPPORTED)
        }
        val length = values.first().toLongOrNull() ?: Long.MAX_VALUE
        if (length > MAX_BODY_BYTES) {
            throw HttpRejection(
                STATUS_TOO_LARGE,
                "The body is larger than $MAX_BODY_BYTES bytes",
                ErrorKind.UNSUPPORTED,
            )
        }
        return length.toInt()
    }

    /** Reads in chunks, so a client that announces a large body and never sends it costs no memory. */
    private fun readBody(input: BufferedInputStream, length: Int): String {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (bytes.size() < length) {
            val count = input.read(chunk, 0, minOf(chunk.size, length - bytes.size()))
            if (count < 0) {
                throw IOException("The client closed the connection inside a request")
            }
            bytes.write(chunk, 0, count)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun writeError(output: OutputStream, rejection: HttpRejection) {
        val body = json.encodeToString<Response>(Response.Error(rejection.message, rejection.kind))
        runCatching { writeResponse(output, rejection.status, body, keepAlive = false) }
    }

    private fun writeResponse(output: OutputStream, status: Int, body: String, keepAlive: Boolean) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = if (keepAlive) "keep-alive" else "close"
        val header =
            "HTTP/1.1 $status ${reason(status)}\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: $connection\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun reason(status: Int): String {
        return when (status) {
            STATUS_OK -> "OK"
            STATUS_BAD_REQUEST -> "Bad Request"
            STATUS_FORBIDDEN -> "Forbidden"
            STATUS_TIMEOUT -> "Request Timeout"
            STATUS_TOO_LARGE -> "Payload Too Large"
            STATUS_UNAVAILABLE -> "Service Unavailable"
            else -> "Error"
        }
    }

    private class Request(val body: String, val keepAlive: Boolean)

    private class HttpRejection(val status: Int, override val message: String, val kind: String? = null) :
        Exception(message)

    private class Headers(private val values: Map<String, List<String>>) {
        operator fun get(name: String): List<String> {
            return values[name].orEmpty()
        }
    }

    /**
     * Gives every read the time that is left: [readTimeoutMs] while the connection is idle between requests, and from
     * the first byte of a request on the rest of one [readTimeoutMs] for the whole request, so a client cannot hold a
     * connection by sending a byte now and then.
     */
    private class TimedInput(private val connection: ClientConnection, private val timeoutMs: Long) : InputStream() {
        private val source = connection.input
        private var deadline = 0L

        var started = false
            private set

        fun beginRequest() {
            started = false
            deadline = 0
        }

        fun startRequest() {
            started = true
            deadline = System.nanoTime() / NANOS_PER_MS + timeoutMs
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val remaining = if (deadline == 0L) timeoutMs else deadline - System.nanoTime() / NANOS_PER_MS
            if (remaining <= 0) {
                throw SocketTimeoutException("The request took longer than $timeoutMs ms")
            }
            connection.setReadTimeout(minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
            return source.read(buffer, offset, length)
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        const val READ_TIMEOUT_MS = 30_000L
        const val MAX_HEAD_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 4 * 1024 * 1024

        private const val CHUNK_BYTES = 8 * 1024
        private const val NANOS_PER_MS = 1_000_000L
        private const val STATUS_OK = 200
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_FORBIDDEN = 403
        private const val STATUS_TIMEOUT = 408
        private const val STATUS_TOO_LARGE = 413
        private const val STATUS_UNAVAILABLE = 503

        /** The `Host` of a request to a loopback server: 127.0.0.1, localhost or [::1], with an optional port. */
        internal fun isLoopbackHost(host: String): Boolean {
            val value = host.trim()
            val name: String
            val port: String?
            if (value.startsWith("[")) {
                val close = value.indexOf(']')
                if (close < 0) {
                    return false
                }
                name = value.substring(0, close + 1)
                val rest = value.substring(close + 1)
                port =
                    when {
                        rest.isEmpty() -> null
                        rest.startsWith(":") -> rest.substring(1)
                        else -> return false
                    }
            } else {
                val colon = value.indexOf(':')
                name = if (colon < 0) value else value.substring(0, colon)
                port = if (colon < 0) null else value.substring(colon + 1)
            }
            if (port != null && !isPort(port)) {
                return false
            }
            return name == "[::1]" || name == "127.0.0.1" || name.equals("localhost", ignoreCase = true)
        }

        private fun isPort(value: String): Boolean {
            return value.isNotEmpty() && value.length <= 5 && value.all { it in '0'..'9' } && value.toInt() <= 65_535
        }
    }
}
