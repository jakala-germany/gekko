package com.jakala.gekko.agent

import com.jakala.gekko.protocol.MockRule
import com.jakala.gekko.protocol.MockRules
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink

class MockInterceptorTest {
    @AfterTest
    fun noMocksAreLeft() {
        GekkoMocks.clear()
    }

    private fun rule(
        id: String,
        glob: String,
        body: String = "",
        method: String? = null,
        bodyContains: String? = null,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        delayMs: Long = 0,
    ): MockRule {
        return MockRule(
            id = id,
            urlRegex = MockRules.globToRegex(glob),
            method = method,
            bodyContains = bodyContains,
            status = status,
            headers = headers,
            delayMs = delayMs,
            bodyBase64 = Base64.encode(body.encodeToByteArray()),
        )
    }

    /** The "network": answers every request with "real" and counts how often it was asked. */
    private class Network : Interceptor {
        var requests = 0

        /** How many bytes of request body the "network" received. */
        var bodyBytes = 0L

        override fun intercept(chain: Interceptor.Chain): Response {
            requests++
            chain.request().body?.let { body ->
                val received = Buffer()
                body.writeTo(received)
                bodyBytes = received.size
            }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("real".toResponseBody("text/plain".toMediaType()))
                .build()
        }
    }

    private fun client(network: Network): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor(MockInterceptor()).addInterceptor(network).build()
    }

    private fun get(client: OkHttpClient, url: String): Response {
        return client.newCall(Request.Builder().url(url).build()).execute()
    }

    @Test
    fun withoutMocksTheRequestGoesToTheNetwork() {
        val network = Network()

        val response = get(client(network), "https://api.example.com/routes")

        assertEquals("real", response.body!!.string())
        assertEquals(1, network.requests)
    }

    @Test
    fun aMatchingRequestIsAnsweredWithTheStatusHeadersAndBodyOfTheMock() {
        GekkoMocks.set(
            listOf(
                rule(
                    "m1",
                    "https://api.example.com/routes*",
                    body = """{"routes":[]}""",
                    status = 201,
                    headers = mapOf("Content-Type" to "application/json", "X-Mocked" to "yes"),
                )
            )
        )
        val network = Network()

        val response = get(client(network), "https://api.example.com/routes?from=A&to=B")

        assertEquals(201, response.code)
        assertEquals("yes", response.header("X-Mocked"))
        assertEquals("application/json", response.body!!.contentType().toString())
        assertEquals("""{"routes":[]}""", response.body!!.string())
        assertEquals(0, network.requests)
    }

    @Test
    fun theAnsweredRequestsAreReportedOnceAndOldestFirst() {
        GekkoMocks.set(listOf(rule("m1", "https://api.example.com/*", body = "x")))
        val client = client(Network())

        get(client, "https://api.example.com/a")
        get(client, "https://api.example.com/b")
        val first = GekkoMocks.drainHits()
        val second = GekkoMocks.drainHits()

        assertEquals(listOf("https://api.example.com/a", "https://api.example.com/b"), first.map { it.url })
        assertEquals(listOf("m1", "m1"), first.map { it.id })
        assertEquals("GET", first.first().method)
        assertTrue(second.isEmpty())
    }

    @Test
    fun theLastMatchingMockWins() {
        GekkoMocks.set(
            listOf(
                rule("m1", "https://api.example.com/*", body = "first"),
                rule("m2", "https://api.example.com/routes*", body = "second"),
            )
        )
        val client = client(Network())

        assertEquals("second", get(client, "https://api.example.com/routes").body!!.string())
        assertEquals("first", get(client, "https://api.example.com/other").body!!.string())
    }

    @Test
    fun aMockForOnePostRequestLeavesTheGetRequestAlone() {
        GekkoMocks.set(listOf(rule("m1", "https://api.example.com/*", body = "mocked", method = "POST")))
        val network = Network()
        val client = client(network)

        assertEquals("real", get(client, "https://api.example.com/routes").body!!.string())
        val post =
            client
                .newCall(
                    Request.Builder()
                        .url("https://api.example.com/routes")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                )
                .execute()
        assertEquals("mocked", post.body!!.string())
        assertEquals(1, network.requests)
    }

    @Test
    fun theBodyOfTheRequestDecidesWhenAMockAsksForIt() {
        GekkoMocks.set(
            listOf(
                rule(
                    "m1",
                    "https://api.example.com/graphql",
                    body = "routes",
                    method = "POST",
                    bodyContains = "\"operationName\":\"SearchRoutes\"",
                )
            )
        )
        val network = Network()
        val client = client(network)

        fun post(text: String): String {
            val body = text.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("https://api.example.com/graphql").post(body).build()
            return client.newCall(request).execute().body!!.string()
        }

        assertEquals("routes", post("""{"operationName":"SearchRoutes"}"""))
        assertEquals("real", post("""{"operationName":"Profile"}"""))
    }

    @Test
    fun theAnswerIsDelayed() {
        GekkoMocks.set(listOf(rule("m1", "https://api.example.com/*", body = "slow", delayMs = 250)))
        val client = client(Network())

        val started = System.nanoTime()
        val body = get(client, "https://api.example.com/x").body!!.string()
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("slow", body)
        assertTrue(tookMs >= 240, "took ${tookMs}ms")
    }

    @Test
    fun clearingTheMocksSendsTheRequestToTheNetworkAgain() {
        GekkoMocks.set(listOf(rule("m1", "https://api.example.com/*", body = "mocked")))
        val network = Network()
        val client = client(network)
        assertEquals("mocked", get(client, "https://api.example.com/x").body!!.string())

        GekkoMocks.clear()

        assertEquals("real", get(client, "https://api.example.com/x").body!!.string())
        assertTrue(GekkoMocks.drainHits().isEmpty(), "clearing forgets the hits")
    }

    @Test
    fun theInterceptorIsInstalledLastAndOnlyOnce() {
        val builder = OkHttpClient.Builder().addInterceptor(Network())

        GekkoOkHttp.install(builder)
        GekkoOkHttp.install(builder)
        val client = builder.build()
        val again = client.newBuilder().build()

        assertEquals(2, client.interceptors.size)
        assertTrue(client.interceptors.last() is MockInterceptor)
        assertEquals(2, again.interceptors.size)
    }

    @Test
    fun aDerivedClientKeepsTheMockInterceptorBehindTheInterceptorsItAdds() {
        val base = OkHttpClient.Builder().addInterceptor(Network()).also(GekkoOkHttp::install).build()
        val app = Network()

        // What the hooked constructor of OkHttpClient does for every client that is built
        val derived = base.newBuilder().addInterceptor(app).also(GekkoOkHttp::install).build()

        assertEquals(3, derived.interceptors.size)
        assertTrue(derived.interceptors.last() is MockInterceptor)
        assertTrue(derived.interceptors[1] === app)
    }

    /** A body of [parts] times 100 kB whose length nobody knows beforehand, like a streamed upload. */
    private class StreamedBody(private val parts: Int, private val oneShot: Boolean = false) : RequestBody() {
        var written = 0

        override fun contentType(): MediaType? {
            return null
        }

        override fun isOneShot(): Boolean {
            return oneShot
        }

        override fun writeTo(sink: BufferedSink) {
            written++
            repeat(parts) { sink.writeUtf8("a".repeat(100_000)) }
        }
    }

    private fun post(client: OkHttpClient, body: RequestBody): Response {
        return client.newCall(Request.Builder().url("https://api.example.com/upload").post(body).build()).execute()
    }

    @Test
    fun aBodyOverTheLimitIsNotLookedAtAndStillReachesTheNetworkWhole() {
        GekkoMocks.set(listOf(rule("m1", "https://api.example.com/*", body = "x", bodyContains = "needle")))
        val network = Network()
        val upload = ("a".repeat(2_000_000) + "needle").toRequestBody("text/plain".toMediaType())

        val response = post(client(network), upload)

        assertEquals("real", response.body!!.string())
        assertEquals(2_000_006L, network.bodyBytes)
    }

    @Test
    fun aStreamedBodyOverTheLimitIsNotHeldInMemory() {
        GekkoMocks.set(listOf(rule("m1", "https://api.example.com/*", body = "x", bodyContains = "needle")))
        val network = Network()
        val upload = StreamedBody(parts = 20)

        val response = post(client(network), upload)

        assertEquals("real", response.body!!.string())
        assertEquals(2_000_000L, network.bodyBytes)
    }

    @Test
    fun aBodyThatCanBeSentOnlyOnceIsLeftForTheNetwork() {
        GekkoMocks.set(listOf(rule("m1", "https://api.example.com/*", body = "x", bodyContains = "aaa")))
        val network = Network()
        val upload = StreamedBody(parts = 1, oneShot = true)

        val response = post(client(network), upload)

        assertEquals("real", response.body!!.string())
        assertEquals(1, upload.written)
        assertEquals(100_000L, network.bodyBytes)
    }

    @Test
    fun aHeaderOkHttpRefusesFailsTheCallWithAnIoExceptionNotACrash() {
        GekkoMocks.set(
            listOf(
                rule("m1", "https://api.example.com/*", body = "x", headers = mapOf("Content-Disposition" to "Müller"))
            )
        )

        assertFailsWith<IOException> { get(client(Network()), "https://api.example.com/file") }
    }

    @Test
    fun aMockWithoutTheBodyOfARequestThatHasNoneDoesNotMatchABodyFilter() {
        GekkoMocks.set(listOf(rule("m1", "https://api.example.com/*", body = "x", bodyContains = "needle")))
        val network = Network()

        val response = get(client(network), "https://api.example.com/x")

        assertEquals("real", response.body!!.string())
        assertNull(GekkoMocks.drainHits().firstOrNull())
    }
}
