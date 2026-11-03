package com.jakala.gekko.agent

import com.jakala.gekko.protocol.MockRule
import com.jakala.gekko.protocol.MockRules
import com.sun.net.httpserver.HttpServer
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockableConnectionTest {
    private lateinit var server: HttpServer
    private val received = mutableListOf<String>()
    private lateinit var base: String

    @BeforeTest
    fun startTheBackend() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            received += "${exchange.requestMethod} ${exchange.requestURI} $body".trim()
            val answer = "real".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stopTheBackend() {
        server.stop(0)
        GekkoMocks.clear()
    }

    private fun rule(
        glob: String,
        body: String = "",
        status: Int = 200,
        method: String? = null,
        bodyContains: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): MockRule {
        return MockRule(
            id = "m1",
            urlRegex = MockRules.globToRegex(glob),
            method = method,
            bodyContains = bodyContains,
            status = status,
            headers = headers,
            bodyBase64 = Base64.encode(body.encodeToByteArray()),
        )
    }

    private fun open(path: String): HttpURLConnection {
        return GekkoUrlConnection.open(URL("$base$path")) as HttpURLConnection
    }

    @Test
    fun aConnectionNoMockIsMeantForIsTheRealOne() {
        GekkoMocks.set(listOf(rule("https://api.example.com/*", body = "mocked")))

        val connection = open("/routes")

        assertFalse(connection is MockableConnection)
        assertEquals("real", connection.inputStream.readBytes().decodeToString())
    }

    @Test
    fun withoutMocksEveryConnectionIsTheRealOne() {
        val connection = open("/routes")

        assertFalse(connection is MockableConnection)
    }

    @Test
    fun aMockedConnectionAnswersWithTheMockAndNeverAsksTheBackend() {
        GekkoMocks.set(
            listOf(
                rule(
                    "$base/routes*",
                    body = """{"routes":[]}""",
                    status = 200,
                    headers = mapOf("Content-Type" to "application/json", "X-Mocked" to "yes"),
                )
            )
        )

        val connection = open("/routes?from=A")

        assertTrue(connection is MockableConnection)
        assertEquals(200, connection.responseCode)
        assertEquals("""{"routes":[]}""", connection.inputStream.readBytes().decodeToString())
        assertEquals("application/json", connection.contentType)
        assertEquals("yes", connection.getHeaderField("x-mocked"))
        assertEquals(13, connection.contentLength)
        assertTrue(received.isEmpty(), "the backend was asked: $received")
        assertEquals(listOf("$base/routes?from=A"), GekkoMocks.drainHits().map { it.url })
    }

    @Test
    fun theStatusLineIsHeaderZero() {
        GekkoMocks.set(listOf(rule("$base/*", body = "x", headers = mapOf("A" to "1"))))

        val connection = open("/x")

        assertNull(connection.getHeaderFieldKey(0))
        assertEquals("HTTP/1.1 200 OK", connection.getHeaderField(0))
        assertEquals("A", connection.getHeaderFieldKey(1))
        assertEquals(listOf("1"), connection.headerFields["A"])
    }

    @Test
    fun theDateHeadersOfAMockAreDates() {
        GekkoMocks.set(
            listOf(
                rule(
                    "$base/*",
                    body = "x",
                    headers =
                        mapOf(
                            "Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT",
                            "Expires" to "Wednesday, 21-Oct-15 07:28:00 GMT",
                            "Date" to "not a date",
                        ),
                )
            )
        )

        val connection = open("/x")

        assertEquals(1_445_412_480_000L, connection.lastModified)
        assertEquals(1_445_412_480_000L, connection.expiration)
        assertEquals(0L, connection.date)
        assertEquals(42L, connection.getHeaderFieldDate("Missing", 42L))
    }

    @Test
    fun theHitLogKeepsTheLatestRequestsOnly() {
        val mock = rule("$base/*")

        repeat(10_005) { GekkoMocks.record(mock, "GET", "$base/$it") }

        val hits = GekkoMocks.drainHits()
        assertEquals(10_000, hits.size)
        assertEquals("$base/5", hits.first().url)
    }

    @Test
    fun aMockedErrorHasNoInputStreamButAnErrorStream() {
        GekkoMocks.set(listOf(rule("$base/*", body = "gone", status = 500)))

        val connection = open("/x")

        assertEquals(500, connection.responseCode)
        assertFailsWith<IOException> { connection.inputStream }
        assertEquals("gone", connection.errorStream.readBytes().decodeToString())
    }

    @Test
    fun aMockedNotFoundIsAFileNotFoundException() {
        GekkoMocks.set(listOf(rule("$base/*", status = 404)))

        assertFailsWith<FileNotFoundException> { open("/x").inputStream }
    }

    @Test
    fun whatTheAppWritesToAMockedPostIsDiscarded() {
        GekkoMocks.set(listOf(rule("$base/*", body = "created", status = 201, method = "POST")))
        val connection = open("/items")

        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write("""{"a":1}""".toByteArray()) }

        assertEquals(201, connection.responseCode)
        assertEquals("created", connection.inputStream.readBytes().decodeToString())
        assertTrue(received.isEmpty())
    }

    @Test
    fun aGetConnectionIsNotAnsweredByAMockForPost() {
        GekkoMocks.set(listOf(rule("$base/*", body = "created", method = "POST")))
        val connection = open("/items")

        assertEquals(200, connection.responseCode)
        assertEquals("real", connection.inputStream.readBytes().decodeToString())
        assertEquals(listOf("GET /items"), received)
    }

    @Test
    fun theBodyOfARequestDecidesWhenAMockAsksForItAndTheBackendStillGetsTheBody() {
        GekkoMocks.set(listOf(rule("$base/graphql", body = "mocked", method = "POST", bodyContains = "SearchRoutes")))

        fun post(text: String): String {
            val connection = open("/graphql")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(text.toByteArray()) }
            return connection.inputStream.readBytes().decodeToString()
        }

        assertEquals("mocked", post("""{"operationName":"SearchRoutes"}"""))
        assertTrue(received.isEmpty())
        assertEquals("real", post("""{"operationName":"Profile"}"""))
        assertEquals(listOf("""POST /graphql {"operationName":"Profile"}"""), received)
    }

    private fun upload(path: String, chunks: Int): HttpURLConnection {
        val connection = open(path)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { output -> repeat(chunks) { output.write("a".repeat(100_000).toByteArray()) } }
        return connection
    }

    @Test
    fun aBodyOverTheLimitIsNotHeldAndTheBackendStillGetsItWhole() {
        GekkoMocks.set(listOf(rule("$base/graphql", body = "mocked", method = "POST", bodyContains = "aaa")))

        val connection = upload("/graphql", chunks = 25)

        assertEquals("real", connection.inputStream.readBytes().decodeToString())
        assertEquals(listOf("POST /graphql " + "a".repeat(2_500_000)), received)
    }

    @Test
    fun aMockThatDoesNotNeedTheBodyAnswersAnUploadThatWasTooBigToLookAt() {
        GekkoMocks.set(
            listOf(
                rule("$base/graphql", body = "plain", method = "POST"),
                rule("$base/graphql", body = "special", method = "POST", bodyContains = "aaa"),
            )
        )

        val connection = upload("/graphql", chunks = 25)

        assertEquals("plain", connection.inputStream.readBytes().decodeToString())
        assertTrue(received.isEmpty())
    }

    @Test
    fun aPostWithoutAMethodCallIsStillAPostWhenItHasOutput() {
        GekkoMocks.set(listOf(rule("$base/*", body = "mocked", method = "POST")))
        val connection = open("/items")

        connection.doOutput = true
        connection.outputStream.use { it.write("x".toByteArray()) }

        assertEquals("mocked", connection.inputStream.readBytes().decodeToString())
    }

    @Test
    fun theConnectionCanBeCastToAnHttpsConnection() {
        GekkoMocks.set(listOf(rule("$base/*", body = "x")))

        val connection = open("/x")

        assertTrue(connection is HttpsURLConnection)
        connection.connect()
        assertEquals(200, connection.responseCode)
    }

    @Test
    fun openStreamReadsTheMock() {
        GekkoMocks.set(listOf(rule("$base/*", body = "streamed")))

        val text = GekkoUrlConnection.openStream(URL("$base/x")).readBytes().decodeToString()

        assertEquals("streamed", text)
    }
}
