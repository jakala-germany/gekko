package com.jakala.gekko.transport

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server proves itself with the key of the user and answers only requests that are signed with it. */
class ServerAuthTest {
    private val key = KEY.toByteArray()
    private val endpoint = "gekko-com.example.app"
    private val nonce = "00112233445566778899aabbccddeeff"

    private class RecordedConnection(request: ByteArray) : ClientConnection {
        override val input: InputStream = ByteArrayInputStream(request)
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

    private class OneShotListener(private val connection: ClientConnection) : Listener {
        private var served = false

        override val isOpen: Boolean
            get() = !served

        override fun accept(): ClientConnection? {
            served = true
            return connection
        }
    }

    private class Answer(val raw: String) {
        val status: Int = raw.substringBefore("\r\n").split(' ')[1].toInt()
        val body: String = raw.substringAfter("\r\n\r\n")

        fun header(name: String): String? {
            return raw.substringBefore("\r\n\r\n")
                .lines()
                .drop(1)
                .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
        }
    }

    private class Served(val answer: Answer, val seen: List<Command>)

    private fun mac(key: ByteArray, endpoint: String, session: String, nonce: String, body: String): String {
        return GekkoAuth.hmacHex(key, Protocol.requestMessage(endpoint, session, nonce, body.toByteArray()))
    }

    private fun request(body: String, headers: List<String>): ByteArray {
        val head =
            "POST / HTTP/1.1\r\nHost: 127.0.0.1:1\r\n" +
                headers.joinToString("") { "$it\r\n" } +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n"
        return head.toByteArray() + body.toByteArray()
    }

    private fun signed(
        body: String,
        session: String,
        key: ByteArray = this.key,
        endpoint: String = this.endpoint,
        nonce: String = this.nonce,
    ): ByteArray {
        return request(
            body,
            listOf(
                "${Protocol.NONCE_HEADER}: $nonce",
                "${Protocol.SESSION_HEADER}: $session",
                "${Protocol.AUTH_HEADER}: ${mac(key, endpoint, session, nonce, body)}",
            ),
        )
    }

    private fun serve(auth: ServerAuth, request: ByteArray): Served {
        val seen = mutableListOf<Command>()
        val connection = RecordedConnection(request)
        CommandServer(OneShotListener(connection), auth) {
                seen += it
                Response.Ok
            }
            .start()
        return Served(Answer(connection.answer()), seen)
    }

    private fun server(): ServerAuth {
        return ServerAuth(endpoint) { key }
    }

    @Test
    fun hmacMatchesTheKnownAnswersThatTheOtherPlatformsUse() {
        val request = Protocol.requestMessage("20412", "", NONCE_VECTOR, HELLO.toByteArray())
        val response = Protocol.responseMessage("20412", SESSION_VECTOR, NONCE_VECTOR, 200, OK.toByteArray())

        assertEquals(REQUEST_MAC_VECTOR, GekkoAuth.hmacHex(KEY.toByteArray(), request))
        assertEquals(RESPONSE_MAC_VECTOR, GekkoAuth.hmacHex(KEY.toByteArray(), response))
    }

    @Test
    fun answersASignedHelloWithoutASessionAndSignsTheAnswer() {
        val auth = server()

        val served = serve(auth, signed(HELLO, session = ""))

        assertEquals(200, served.answer.status)
        assertEquals(listOf<Command>(Command.Hello), served.seen)
        assertEquals(auth.session, served.answer.header(Protocol.SESSION_HEADER))
        val expected =
            GekkoAuth.hmacHex(
                key,
                Protocol.responseMessage(endpoint, auth.session, nonce, 200, served.answer.body.toByteArray()),
            )
        assertEquals(expected, served.answer.header(Protocol.AUTH_HEADER))
    }

    @Test
    fun runsACommandOfTheCurrentSession() {
        val auth = server()

        val served = serve(auth, signed(TREE, session = auth.session))

        assertEquals(200, served.answer.status)
        assertEquals(listOf<Command>(Command.Tree), served.seen)
        assertNotNull(served.answer.header(Protocol.AUTH_HEADER))
    }

    @Test
    fun refusesACommandOfAnotherSessionAndSaysSoInASignedAnswer() {
        val auth = server()

        val served = serve(auth, signed(TREE, session = "0".repeat(32)))

        assertEquals(401, served.answer.status)
        assertTrue(served.answer.body.contains("\"kind\":\"session\""))
        assertNotNull(served.answer.header(Protocol.AUTH_HEADER))
        assertTrue(served.seen.isEmpty())
    }

    @Test
    fun refusesARequestThatIsNotSigned() {
        val served = serve(server(), request(TREE, emptyList()))

        assertEquals(401, served.answer.status)
        assertTrue(served.answer.body.contains("\"kind\":\"unauthorized\""))
        assertNull(served.answer.header(Protocol.AUTH_HEADER))
        assertTrue(served.seen.isEmpty())
    }

    @Test
    fun refusesARequestSignedWithAnotherKey() {
        val auth = server()

        val served = serve(auth, signed(TREE, session = auth.session, key = "another key".toByteArray()))

        assertEquals(401, served.answer.status)
        assertTrue(served.seen.isEmpty())
    }

    @Test
    fun refusesARequestThatWasSignedForAnotherEndpoint() {
        val auth = server()

        val served = serve(auth, signed(TREE, session = auth.session, endpoint = "gekko-com.other.app"))

        assertEquals(401, served.answer.status)
        assertTrue(served.seen.isEmpty())
    }

    @Test
    fun refusesARequestWhoseBodyWasChanged() {
        val auth = server()
        val genuine = String(signed(TREE, session = auth.session))

        val served = serve(auth, genuine.replace("\"tree\"", "\"trex\"").toByteArray())

        assertEquals(401, served.answer.status)
        assertTrue(served.seen.isEmpty())
    }

    @Test
    fun refusesARequestWithoutANonce() {
        val auth = server()
        val body = TREE
        val unsigned =
            request(
                body,
                listOf(
                    "${Protocol.SESSION_HEADER}: ${auth.session}",
                    "${Protocol.AUTH_HEADER}: ${mac(key, endpoint, auth.session, nonce, body)}",
                ),
            )

        val served = serve(auth, unsigned)

        assertEquals(401, served.answer.status)
        assertTrue(served.seen.isEmpty())
    }

    @Test
    fun saysThatItHasNoKeyYetWithoutPretendingToSign() {
        val auth = ServerAuth(endpoint) { null }

        val served = serve(auth, signed(HELLO, session = ""))

        assertEquals(401, served.answer.status)
        assertTrue(served.answer.body.contains("\"kind\":\"noKey\""))
        assertNull(served.answer.header(Protocol.AUTH_HEADER))
        assertTrue(served.seen.isEmpty())
    }

    @Test
    fun picksANewSessionForEveryServer() {
        assertFalse(server().session == server().session)
        assertEquals(32, server().session.length)
    }

    @Test
    fun comparesMacsWithoutCaringAboutTheirLength() {
        assertTrue(GekkoAuth.matches("abc", "abc"))
        assertFalse(GekkoAuth.matches("abc", "abd"))
        assertFalse(GekkoAuth.matches("abc", "abcd"))
        assertFalse(GekkoAuth.matches("abc", ""))
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val NONCE_VECTOR = "00112233445566778899aabbccddeeff"
        const val SESSION_VECTOR = "ffeeddccbbaa99887766554433221100"
        const val HELLO = "{\"type\":\"hello\"}"
        const val TREE = "{\"type\":\"tree\"}"
        const val OK = "{\"type\":\"ok\"}"
        const val REQUEST_MAC_VECTOR = "4ee5a8b1b169ea84d0a350b84270a987f8ff285a9087ce3bf17d75a0943631a0"
        const val RESPONSE_MAC_VECTOR = "685c66ca974f75c6b2de0f8a18d78a0e3a36c3ad37e41cb3f33c1d945e6032d5"
    }
}
