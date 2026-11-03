package com.jakala.gekko.agent

import com.jakala.gekko.protocol.MockRules
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSink
import okio.Sink
import okio.buffer

/**
 * Where the OkHttp hook of the gekko Gradle plugin ends up: the plugin makes every `OkHttpClient` constructor call
 * [install] with the builder it is made from. Only the calls that OkHttp 3.12 up to 5.x all have are used, because the
 * app brings its own OkHttp. Classes of the agent that mention OkHttp are loaded by this hook only, so an app without
 * OkHttp never sees them.
 */
object GekkoOkHttp {
    /**
     * Puts one mock interceptor last in the chain of the app's own interceptors. A client made with `newBuilder()`
     * brings the interceptor of its parent along, in front of whatever the app adds: it is moved to the end again.
     */
    @JvmStatic
    fun install(builder: OkHttpClient.Builder) {
        try {
            val interceptors = builder.interceptors()
            interceptors.removeAll { it is MockInterceptor }
            interceptors.add(MockInterceptor())
        } catch (error: Throwable) {
            // a test agent must never break the app's networking
        }
    }
}

/**
 * Answers the requests that match a mock without asking the network. It is the last application interceptor, so what
 * the app adds to a request (an auth header) is there when the mocks look at it and what the app does with a response
 * (logging, error handling) happens to the mocked one too.
 */
internal class MockInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val matcher = GekkoMocks.current() ?: return chain.proceed(request)
        val method = request.method
        val url = request.url.toString()
        if (!matcher.matchesIgnoringBody(method, url)) return chain.proceed(request)
        val body by lazy { bodyText(request) }
        val rule = matcher.match(method, url) { body } ?: return chain.proceed(request)
        GekkoMocks.record(rule, method, url)
        if (rule.delayMs > 0) Thread.sleep(rule.delayMs)
        val builder =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(rule.status)
                .message(HttpReasons.of(rule.status))
                .body(MockBody(GekkoMocks.bodyOf(rule), GekkoMocks.contentTypeOf(rule)))
        try {
            rule.headers.forEach { (name, value) -> builder.header(name, value) }
        } catch (error: IllegalArgumentException) {
            // OkHttp refuses what no server could send; an app expects an IOException from a call, not a crash
            throw IOException("gekko: the mock ${rule.id} has a header OkHttp refuses: ${error.message}", error)
        }
        return builder.build()
    }

    /**
     * The request body as text for a `bodyContains` mock; null when there is none or it cannot be looked at: a body
     * that can be sent only once (the real request would be left with nothing), or one over the limit.
     */
    private fun bodyText(request: Request): String? {
        val body = request.body ?: return null
        return try {
            if (!canBeReadTwice(body)) return null
            val length = body.contentLength()
            if (length > MockRules.MAX_INSPECTED_BODY_BYTES) return null
            val held = Buffer()
            val sink = LimitedSink(held, MockRules.MAX_INSPECTED_BODY_BYTES.toLong()).buffer()
            body.writeTo(sink)
            sink.flush()
            held.readUtf8()
        } catch (error: Exception) {
            null
        }
    }

    /** One-shot and duplex bodies came with OkHttp 3.14; an older OkHttp has none of them. */
    private fun canBeReadTwice(body: RequestBody): Boolean {
        return try {
            !body.isOneShot() && !body.isDuplex()
        } catch (error: NoSuchMethodError) {
            true
        }
    }
}

/** A sink that refuses what is written past [limit] bytes, so that a large body is never held in memory. */
private class LimitedSink(delegate: Sink, private val limit: Long) : ForwardingSink(delegate) {
    private var written = 0L

    override fun write(source: Buffer, byteCount: Long) {
        written += byteCount
        if (written > limit) throw IOException("the body is larger than $limit bytes")
        super.write(source, byteCount)
    }
}

/** A response body that is in memory; its own class so that it works with every OkHttp version. */
internal class MockBody(private val bytes: ByteArray, contentType: String?) : ResponseBody() {
    private val type = contentType?.let(::mediaType)
    private val buffer = Buffer().write(bytes)

    override fun contentType(): MediaType? {
        return type
    }

    override fun contentLength(): Long {
        return bytes.size.toLong()
    }

    override fun source(): BufferedSource {
        return buffer
    }

    private companion object {
        /** `MediaType.get` (OkHttp 4 and 5) or `MediaType.parse` (OkHttp 3); a type that cannot be made is no type. */
        fun mediaType(text: String): MediaType? {
            val mediaType = MediaType::class.java
            for (name in listOf("get", "parse")) {
                try {
                    return mediaType.getMethod(name, String::class.java).invoke(null, text) as MediaType?
                } catch (error: Exception) {
                    // the next name, or none
                }
            }
            return null
        }
    }
}

internal object HttpReasons {
    /** The reason phrase of a status line. */
    fun of(status: Int): String {
        return when (status) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            409 -> "Conflict"
            410 -> "Gone"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> ""
        }
    }
}
