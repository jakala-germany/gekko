package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.transport.CommandServer
import com.jakala.gekko.transport.ServerAuth
import com.jakala.gekko.transport.TcpListener
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The CLI against the real [CommandServer] of the Android agent and runner, and against programs that only pretend to
 * be one: whoever listens on the port first can answer, so an answer that is not signed for its request is refused.
 */
class SignedClientTest {
    private val key = "k".repeat(64).encodeToByteArray()
    private val otherKey = "x".repeat(64).encodeToByteArray()
    private val cleanup = mutableListOf<() -> Unit>()
    private val hello = Response.HelloResult(Protocol.VERSION, "test", Protocol.ROLE_AGENT)

    @AfterTest
    fun tearDown() {
        cleanup.forEach { runCatching { it() } }
    }

    /** A real server on a port; [executed] collects the commands it ran. */
    private class Real(val listener: TcpListener, val auth: ServerAuth, val executed: MutableList<Command>) {
        val port: Int
            get() = listener.port
    }

    private fun realServer(
        port: Int = 0,
        serverKey: () -> ByteArray? = { key },
        executed: MutableList<Command> = CopyOnWriteArrayList(),
    ): Real {
        val listener = TcpListener(port)
        val auth = ServerAuth(listener.port.toString(), serverKey)
        CommandServer(listener, auth) { command ->
                executed += command
                if (command == Command.Hello) hello else Response.Ok
            }
            .start()
        cleanup += { listener.close() }
        return Real(listener, auth, executed)
    }

    private fun client(port: Int, clientKey: ByteArray = key, endpoint: String = port.toString()): GekkoClient {
        val client = GekkoClient(port, Protocol.ROLE_AGENT, ClientAuth(clientKey, endpoint))
        cleanup += { client.close() }
        return client
    }

    /** What a program on a port was sent. */
    private class Seen(val headers: Map<String, String>, val body: String)

    /** A program on a free port that answers every request with [answer] and remembers what it was sent. */
    private fun impostor(seen: MutableList<Seen>, answer: (HttpExchange) -> Unit): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() }
            seen += Seen(headers, String(exchange.requestBody.readBytes()))
            answer(exchange)
            exchange.close()
        }
        server.start()
        cleanup += { server.stop(0) }
        return server.address.port
    }

    private fun HttpExchange.reply(body: String, vararg headers: Pair<String, String?>) {
        val bytes = body.toByteArray()
        headers.forEach { (name, value) -> if (value != null) responseHeaders.add(name, value) }
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.write(bytes)
    }

    /** A request the way the CLI signs it, sent without the CLI. */
    private fun post(port: Int, body: String, session: String, nonce: String, mac: String? = null): HttpURLConnection {
        val signature =
            mac
                ?: GekkoAuth.hmacHex(
                    key,
                    Protocol.requestMessage(port.toString(), session, nonce, body.encodeToByteArray()),
                )
        val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty(Protocol.NONCE_HEADER, nonce)
        connection.setRequestProperty(Protocol.SESSION_HEADER, session)
        connection.setRequestProperty(Protocol.AUTH_HEADER, signature)
        connection.outputStream.use { it.write(body.encodeToByteArray()) }
        return connection
    }

    private val helloBody = encodeCommand(Command.Hello)
    private val treeBody = encodeCommand(Command.Tree)

    @Test
    fun talksToTheRealServerOfTheAndroidAgent() {
        runBlocking {
            val server = realServer()
            val client = client(server.port)

            assertTrue(client.isReachable())
            assertEquals(Response.Ok, client.send(Command.Tree))
            assertEquals(Response.Ok, client.send(Command.Foreground))
            assertEquals(listOf(Command.Hello, Command.Tree, Command.Foreground), server.executed)
        }
    }

    @Test
    fun opensAHelloBeforeItsFirstCommand() {
        runBlocking {
            val server = realServer()

            client(server.port).send(Command.Tree)

            assertEquals(listOf(Command.Hello, Command.Tree), server.executed)
        }
    }

    @Test
    fun learnsTheNewSessionWhenTheServerWasRestartedAndRunsTheCommandOnce() {
        runBlocking {
            val first = realServer()
            val client = client(first.port)
            assertEquals(Response.Ok, client.send(Command.Tree))
            first.listener.close()
            val restarted = realServer(port = first.port)

            assertEquals(Response.Ok, client.send(Command.Foreground))

            assertEquals(listOf<Command>(Command.Foreground), restarted.executed)
        }
    }

    @Test
    fun refusesAServerThatHoldsAnotherKey() {
        val server = realServer()

        assertFailsWith<UntrustedServerException> {
            runBlocking { client(server.port, clientKey = otherKey).send(Command.Tree) }
        }
        assertTrue(server.executed.isEmpty())
    }

    @Test
    fun refusesAServerThatIsReachedThroughAnotherEndpointThanItBelievesIn() {
        val server = realServer()

        assertFailsWith<UntrustedServerException> {
            runBlocking { client(server.port, endpoint = "another-socket").send(Command.Tree) }
        }
        assertTrue(server.executed.isEmpty())
    }

    @Test
    fun refusesAServerThatHasNoKeyYet() {
        val server = realServer(serverKey = { null })

        assertFailsWith<UntrustedServerException> { runBlocking { client(server.port).isReachable() } }
    }

    @Test
    fun refusesAnUnsignedAnswerAndNeverSendsTheKey() {
        val seen = CopyOnWriteArrayList<Seen>()
        val port = impostor(seen) { it.reply("{\"type\":\"ok\"}") }

        val error = assertFailsWith<UntrustedServerException> { runBlocking { client(port).send(Command.Tree) } }

        assertTrue(error.message.orEmpty().contains("port $port"))
        // Nothing but the hello went out, and nothing in it is the key
        assertEquals(1, seen.size)
        assertFalse((seen.single().headers.toString() + seen.single().body).contains(String(key)))
        assertFalse(seen.single().headers.containsKey("x-gekko-token"))
    }

    @Test
    fun anOlderServerThatAnswersWithoutSigningIsNotTrustedEither() {
        val port =
            impostor(CopyOnWriteArrayList()) {
                it.reply("{\"type\":\"hello\",\"protocol\":1,\"version\":\"0.1\",\"role\":\"agent\"}")
            }

        assertFailsWith<UntrustedServerException> { runBlocking { client(port).isReachable() } }
    }

    @Test
    fun refusesAnAnswerThatIsSignedWithAnotherKey() {
        val port =
            impostor(CopyOnWriteArrayList()) { exchange ->
                val body = "{\"type\":\"ok\"}"
                val nonce = exchange.requestHeaders.getFirst(Protocol.NONCE_HEADER)
                val port = exchange.localAddress.port.toString()
                val forged =
                    GekkoAuth.hmacHex(
                        otherKey,
                        Protocol.responseMessage(port, "s", nonce, 200, body.encodeToByteArray()),
                    )
                exchange.reply(body, Protocol.SESSION_HEADER to "s", Protocol.AUTH_HEADER to forged)
            }

        assertFailsWith<UntrustedServerException> { runBlocking { client(port).send(Command.Tree) } }
    }

    @Test
    fun refusesAGenuineAnswerThatWasMadeForAnotherRequest() {
        // The answer of the real server to a hello of its own, kept by a program that plays it to whoever asks
        val server = realServer()
        val genuine = post(server.port, helloBody, session = "", nonce = "a".repeat(32))
        val body = genuine.inputStream.readBytes().decodeToString()
        val session = genuine.getHeaderField(Protocol.SESSION_HEADER)
        val mac = genuine.getHeaderField(Protocol.AUTH_HEADER)
        assertNotNull(mac)
        val port =
            impostor(CopyOnWriteArrayList()) {
                it.reply(body, Protocol.SESSION_HEADER to session, Protocol.AUTH_HEADER to mac)
            }

        // The CLI believes it talks to the endpoint of the real server; the replayed answer carries another nonce
        assertFailsWith<UntrustedServerException> {
            runBlocking { client(port, endpoint = server.port.toString()).isReachable() }
        }
    }

    @Test
    fun aRecordedRequestIsWorthlessAgainstAServerThatWasRestarted() {
        val first = realServer()
        val nonce = "b".repeat(32)
        val recorded = post(first.port, treeBody, session = first.auth.session, nonce = nonce)
        assertEquals(200, recorded.responseCode)
        first.listener.close()
        val restarted = realServer(port = first.port)

        // The same bytes, played to the server that started afterwards
        val replayed = post(first.port, treeBody, session = first.auth.session, nonce = nonce)

        assertEquals(401, replayed.responseCode)
        assertTrue(restarted.executed.isEmpty())
    }

    @Test
    fun aRequestWithTheWrongMacRunsNothing() {
        val server = realServer()

        val answer =
            post(server.port, treeBody, session = server.auth.session, nonce = "c".repeat(32), mac = "0".repeat(64))

        assertEquals(401, answer.responseCode)
        assertTrue(server.executed.isEmpty())
    }
}
