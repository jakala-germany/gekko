package com.jakala.gekko.transport

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Response
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandServerTest {
    private fun post(body: String, vararg headers: String): String {
        val extra = headers.joinToString("") { "$it\r\n" }
        return "POST / HTTP/1.1\r\n${extra}Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
    }

    /** The server writes headers and body separately, so a single read may see only the headers. */
    private fun readBody(input: InputStream): String {
        var length = 0
        while (true) {
            val line = StringBuilder()
            while (true) {
                val next = input.read()
                if (next < 0 || next == '\n'.code) break
                line.append(next.toChar())
            }
            val header = line.toString().trim()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toInt()
            }
        }
        return String(input.readNBytes(length))
    }

    private class RecordedConnection(override val input: InputStream) : ClientConnection {
        constructor(request: String) : this(ByteArrayInputStream(request.toByteArray()))

        val sent = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        override val output: OutputStream = sent

        override fun close() {
            closed.countDown()
        }

        fun answer(): String {
            assertTrue(closed.await(5, TimeUnit.SECONDS), "the server did not close the connection")
            return sent.toString(Charsets.UTF_8)
        }
    }

    /** Hands out one connection, then reports that it is shut down. */
    private class OneShotListener(private val connection: ClientConnection) : Listener {
        private var served = false

        override val isOpen: Boolean
            get() = !served

        override fun accept(): ClientConnection? {
            served = true
            return connection
        }
    }

    /** Runs one request through a server and returns what the server answered and which commands it executed. */
    private fun serveOnce(request: String): Pair<String, List<Command>> {
        val seen = mutableListOf<Command>()
        val connection = RecordedConnection(request)
        CommandServer(OneShotListener(connection)) {
                seen += it
                Response.Ok
            }
            .start()
        return connection.answer() to seen
    }

    private fun statusLine(socket: Socket): String? {
        socket.soTimeout = 5_000
        return BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
    }

    @Test
    fun servesAnyListenerAndClosesTheConnectionAfterTheLastRequest() {
        val connection = RecordedConnection(post("{\"type\":\"foreground\"}"))

        CommandServer(OneShotListener(connection)) { Response.ForegroundResult(true) }.start()

        val answer = connection.answer()
        assertTrue(answer.startsWith("HTTP/1.1 200 OK"))
        assertTrue(answer.contains("\"appInForeground\":true"))
    }

    @Test
    fun answersAnUnknownCommandWithTheUnsupportedKind() {
        val (answer, seen) = serveOnce(post("{\"type\":\"nonsense\"}"))

        assertTrue(answer.startsWith("HTTP/1.1 200 OK"))
        assertTrue(answer.contains("\"type\":\"error\""))
        assertTrue(answer.contains("\"kind\":\"unsupported\""))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun answersAFailingHandlerWithAnErrorWithoutKind() {
        val connection = RecordedConnection(post("{\"type\":\"tree\"}"))

        CommandServer(OneShotListener(connection)) { error("main thread did not respond") }.start()

        val answer = connection.answer()
        assertTrue(answer.contains("main thread did not respond"))
        assertTrue(answer.contains("\"kind\":null"))
    }

    @Test
    fun servesSeveralRequestsOnOneLoopbackConnection() {
        val listener = TcpListener(0)
        val seen = mutableListOf<Command>()
        CommandServer(listener) { command ->
                seen += command
                Response.Ok
            }
            .start()

        Socket("127.0.0.1", listener.port).use { socket ->
            repeat(2) {
                socket
                    .getOutputStream()
                    .write(post("{\"type\":\"tree\"}", "Host: 127.0.0.1:${listener.port}").toByteArray())
                socket.getOutputStream().flush()
                assertEquals("{\"type\":\"ok\"}", readBody(socket.getInputStream()))
            }
        }
        listener.close()

        assertEquals(listOf<Command>(Command.Tree, Command.Tree), seen)
    }

    @Test
    fun closesAfterTheAnswerWhenTheClientAsksForIt() {
        val (answer, seen) = serveOnce(post("{\"type\":\"tree\"}", "Connection: close"))

        assertTrue(answer.contains("Connection: close"))
        assertEquals(listOf<Command>(Command.Tree), seen)
    }

    @Test
    fun refusesAForeignHostWithoutRunningAnyCommand() {
        val (answer, seen) = serveOnce(post("{\"type\":\"tree\"}", "Host: attacker.example:4711"))

        assertTrue(answer.startsWith("HTTP/1.1 403"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun refusesABrowserRequestWithoutRunningAnyCommand() {
        val (answer, seen) =
            serveOnce(post("{\"type\":\"tree\"}", "Host: 127.0.0.1:4711", "Origin: https://attacker.example"))

        assertTrue(answer.startsWith("HTTP/1.1 403"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun acceptsEveryLoopbackSpellingOfTheHost() {
        for (host in listOf("127.0.0.1", "127.0.0.1:5037", "localhost", "LocalHost:80", "[::1]", "[::1]:9000")) {
            val (answer, seen) = serveOnce(post("{\"type\":\"tree\"}", "Host: $host"))

            assertTrue(answer.startsWith("HTTP/1.1 200 OK"), host)
            assertEquals(listOf<Command>(Command.Tree), seen, host)
        }
    }

    @Test
    fun recognisesLoopbackHostsOnly() {
        val loopback = listOf("127.0.0.1", "127.0.0.1:1", "localhost", "LOCALHOST:65535", "[::1]", "[::1]:80")
        val foreign =
            listOf(
                "",
                "evil.example",
                "127.0.0.1.evil.example",
                "localhost.evil.example",
                "127.0.0.2",
                "10.0.0.1:80",
                "[::2]",
                "::1",
                "[::1",
                "[::1]x",
                "127.0.0.1:",
                "127.0.0.1:http",
                "127.0.0.1:65536",
                "127.0.0.1:123456",
                "user@127.0.0.1",
                "localhost:80:80",
            )

        loopback.forEach { assertTrue(CommandServer.isLoopbackHost(it), it) }
        foreign.forEach { assertFalse(CommandServer.isLoopbackHost(it), it) }
    }

    @Test
    fun refusesABodyBeyondTheLimitWithoutReadingIt() {
        val request = "POST / HTTP/1.1\r\nContent-Length: ${CommandServer.MAX_BODY_BYTES + 1}\r\n\r\n"
        val (answer, seen) = serveOnce(request)

        assertTrue(answer.startsWith("HTTP/1.1 413"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun refusesALengthThatDoesNotFitANumber() {
        val (answer, _) = serveOnce("POST / HTTP/1.1\r\nContent-Length: 99999999999999999999999\r\n\r\n")

        assertTrue(answer.startsWith("HTTP/1.1 413"))
    }

    @Test
    fun answersAnInvalidContentLengthWithBadRequestAndCloses() {
        for (length in listOf("-1", "abc", "", "1.5", "+4", "4 4")) {
            val (answer, seen) = serveOnce("POST / HTTP/1.1\r\nContent-Length: $length\r\n\r\n{}")

            assertTrue(answer.startsWith("HTTP/1.1 400"), "Content-Length: $length")
            assertTrue(seen.isEmpty())
        }
    }

    @Test
    fun answersConflictingContentLengthsWithBadRequest() {
        val (answer, seen) = serveOnce("POST / HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 15\r\n\r\n{}")

        assertTrue(answer.startsWith("HTTP/1.1 400"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun refusesChunkedBodies() {
        val (answer, seen) = serveOnce("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")

        assertTrue(answer.startsWith("HTTP/1.1 400"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun answersAMalformedRequestLineWithBadRequest() {
        val (answer, _) = serveOnce("nonsense\r\n\r\n")

        assertTrue(answer.startsWith("HTTP/1.1 400"))
    }

    @Test
    fun readsALargeBodyWithinTheLimit() {
        val padding = " ".repeat(1_000_000)
        val (answer, seen) = serveOnce(post("{\"type\":\"tree\"}$padding"))

        assertTrue(answer.startsWith("HTTP/1.1 200 OK"))
        assertEquals(listOf<Command>(Command.Tree), seen)
    }

    @Test
    fun stopsReadingAnEndlessHeaderSection() {
        val endless =
            object : InputStream() {
                private val start = "POST / HTTP/1.1\r\nX-Padding: ".toByteArray()
                private var position = 0

                override fun read(): Int {
                    val next = if (position < start.size) start[position].toInt() else 'a'.code
                    position++
                    return next
                }
            }
        val connection = RecordedConnection(endless)

        CommandServer(OneShotListener(connection)) { Response.Ok }.start()

        assertTrue(connection.answer().startsWith("HTTP/1.1 413"))
    }

    @Test
    fun stopsReadingAnEndlessRequestLine() {
        val connection =
            RecordedConnection(ByteArrayInputStream(ByteArray(CommandServer.MAX_HEAD_BYTES * 4) { 'G'.code.toByte() }))

        CommandServer(OneShotListener(connection)) { Response.Ok }.start()

        assertTrue(connection.answer().startsWith("HTTP/1.1 413"))
    }

    @Test
    fun turnsAwayConnectionsBeyondTheLimit() {
        val listener = TcpListener(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CommandServer(listener, maxConnections = 1) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                Response.Ok
            }
            .start()

        Socket("127.0.0.1", listener.port).use { first ->
            first.getOutputStream().write(post("{\"type\":\"tree\"}").toByteArray())
            first.getOutputStream().flush()
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val refused = Socket("127.0.0.1", listener.port).use { statusLine(it) }
            release.countDown()

            assertEquals("HTTP/1.1 503 Service Unavailable", refused)
            assertEquals("{\"type\":\"ok\"}", readBody(first.getInputStream()))
        }
        listener.close()
    }

    @Test
    fun givesTheSlotBackWhenAConnectionEnds() {
        val listener = TcpListener(0)
        CommandServer(listener, maxConnections = 1) { Response.Ok }.start()

        repeat(3) {
            Socket("127.0.0.1", listener.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(post("{\"type\":\"tree\"}", "Connection: close").toByteArray())
                socket.getOutputStream().flush()
                assertEquals("{\"type\":\"ok\"}", readBody(socket.getInputStream()))
                assertEquals(-1, socket.getInputStream().read())
            }
        }
        listener.close()
    }

    @Test
    fun answersARequestThatStallsWithATimeout() {
        val listener = TcpListener(0)
        CommandServer(listener, readTimeoutMs = 300) { Response.Ok }.start()

        val status =
            Socket("127.0.0.1", listener.port).use { socket ->
                socket.getOutputStream().write("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc".toByteArray())
                socket.getOutputStream().flush()
                statusLine(socket)
            }
        listener.close()

        assertEquals("HTTP/1.1 408 Request Timeout", status)
    }

    @Test
    fun answersARequestThatArrivesByTheByteWithATimeout() {
        val listener = TcpListener(0)
        CommandServer(listener, readTimeoutMs = 400) { Response.Ok }.start()

        val status =
            Socket("127.0.0.1", listener.port).use { socket ->
                val dripper = thread {
                    runCatching {
                        socket.getOutputStream().write("POST / HTTP/1.1\r\nX-Slow: ".toByteArray())
                        repeat(50) {
                            Thread.sleep(100)
                            socket.getOutputStream().write('a'.code)
                            socket.getOutputStream().flush()
                        }
                    }
                }
                val line = statusLine(socket)
                dripper.join(10_000)
                line
            }
        listener.close()

        assertEquals("HTTP/1.1 408 Request Timeout", status)
    }

    @Test
    fun closesAnIdleConnectionAfterTheTimeoutWithoutAnAnswer() {
        val listener = TcpListener(0)
        CommandServer(listener, readTimeoutMs = 300) { Response.Ok }.start()

        val read =
            Socket("127.0.0.1", listener.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getInputStream().read()
            }
        listener.close()

        assertEquals(-1, read)
    }

    @Test
    fun onlyAdbRootAndTheAppItselfMayConnectToADevice() {
        val own = 10_268

        assertTrue(Peers.isAllowed(Peers.SHELL_UID, own))
        assertTrue(Peers.isAllowed(Peers.ROOT_UID, own))
        assertTrue(Peers.isAllowed(own, own))
        assertFalse(Peers.isAllowed(10_269, own))
        assertFalse(Peers.isAllowed(1_000, own))
        assertFalse(Peers.isAllowed(-1, own))
        assertFalse(Peers.isAllowed(-1, -1))
    }
}
