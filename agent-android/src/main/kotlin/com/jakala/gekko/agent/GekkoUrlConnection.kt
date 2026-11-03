package com.jakala.gekko.agent

import android.os.Build
import com.jakala.gekko.protocol.MockRule
import com.jakala.gekko.protocol.MockRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.security.Permission
import java.security.Principal
import java.security.cert.Certificate
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Where the `HttpURLConnection` hook of the gekko Gradle plugin ends up: the plugin makes every `URL.openConnection()`
 * and `URL.openStream()` call of the app and its libraries (Volley, Glide, Ktor's Android engine, ...) come here.
 *
 * Only a connection to a URL that a mock matches is wrapped; every other one is the real one, so nothing changes for
 * the rest of the app. A connection that is opened before its mock is set is not mocked.
 */
object GekkoUrlConnection {
    @JvmStatic
    fun open(url: URL): URLConnection {
        return wrap(url, url.openConnection())
    }

    @JvmStatic
    fun open(url: URL, proxy: Proxy): URLConnection {
        return wrap(url, url.openConnection(proxy))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun openStream(url: URL): InputStream {
        return open(url).getInputStream()
    }

    private fun wrap(url: URL, real: URLConnection): URLConnection {
        if (real !is HttpURLConnection) return real
        val matcher = GekkoMocks.current() ?: return real
        return try {
            if (matcher.matchesUrl(url.toString())) MockableConnection(real) else real
        } catch (error: Throwable) {
            real
        }
    }
}

/**
 * A connection that decides when the request is complete (its method, its headers and, for a mock that wants to see it,
 * its body are known) whether a mock answers it or the real connection does. It extends [HttpsURLConnection] for both
 * schemes: an app that casts an `https` connection can, and for `http` the TLS settings are simply not used.
 */
internal class MockableConnection(private val real: HttpURLConnection) : HttpsURLConnection(real.url) {
    private var decided = false
    private var mock: MockRule? = null
    private var mockedBody = ByteArray(0)
    private var sent: HeldOutput? = null
    private var realOutput: OutputStream? = null

    // ----------------------------------------------------------------------------------------------- the decision

    private fun requestMethod(): String {
        val method = real.requestMethod
        return if (method == "GET" && real.doOutput) "POST" else method
    }

    /** True once the decision is made; a mock that has to see the body waits for it. */
    private fun decideWithoutBody(): Boolean {
        if (decided) return true
        val matcher = GekkoMocks.current()
        if (matcher != null && matcher.needsBody(requestMethod(), real.url.toString())) return false
        decide(null)
        return true
    }

    private fun decide(body: String?) {
        if (decided) return
        decided = true
        val matcher = GekkoMocks.current() ?: return
        val method = requestMethod()
        val url = real.url.toString()
        val rule = matcher.match(method, url) { body } ?: return
        GekkoMocks.record(rule, method, url)
        mock = rule
        mockedBody = GekkoMocks.bodyOf(rule)
        if (rule.delayMs > 0) Thread.sleep(rule.delayMs)
    }

    /** The first look at the response: the request is complete, a mock or the real connection answers. */
    private fun answered(): MockRule? {
        if (!decided) {
            val body = sent
            decide(body?.text())
            if (mock == null && body != null) {
                // The request body was held back for the decision; the real connection gets it now
                outputOf(real).use { body.writeHeldTo(it) }
            }
        }
        return mock
    }

    // ----------------------------------------------------------------------------------------------- the request

    @Throws(IOException::class)
    override fun connect() {
        if (decideWithoutBody() && mock == null) real.connect()
    }

    override fun disconnect() {
        real.disconnect()
    }

    override fun usingProxy(): Boolean {
        return real.usingProxy()
    }

    @Throws(IOException::class)
    override fun getOutputStream(): OutputStream {
        if (decideWithoutBody()) {
            // A mock does not need what the app sends
            return if (mock != null) DiscardingOutput else outputOf(real)
        }
        return sent ?: HeldOutput().also { sent = it }
    }

    /**
     * What the app writes while a mock still has to see it. Past the limit of what an agent looks at the decision is
     * made without the body (a `bodyContains` mock does not match) and the rest goes straight on, so that an upload
     * does not have to fit in memory.
     */
    private inner class HeldOutput : OutputStream() {
        private val held = ByteArrayOutputStream()
        private var onward: OutputStream? = null

        fun text(): String {
            return held.toByteArray().toString(Charsets.UTF_8)
        }

        /** Writes what is held to [target]; what was passed on already is not held any more. */
        fun writeHeldTo(target: OutputStream) {
            held.writeTo(target)
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val target = onward
            if (target != null) {
                target.write(b, off, len)
                return
            }
            if (held.size() + len <= MockRules.MAX_INSPECTED_BODY_BYTES) {
                held.write(b, off, len)
                return
            }
            decide(null)
            val next = if (mock != null) DiscardingOutput else outputOf(real)
            writeHeldTo(next)
            held.reset()
            onward = next
            next.write(b, off, len)
        }
    }

    private fun outputOf(connection: HttpURLConnection): OutputStream {
        return realOutput ?: connection.getOutputStream().also { realOutput = it }
    }

    override fun setRequestMethod(method: String) {
        real.requestMethod = method
    }

    override fun getRequestMethod(): String {
        return real.requestMethod
    }

    override fun setRequestProperty(key: String, value: String?) {
        real.setRequestProperty(key, value)
    }

    override fun addRequestProperty(key: String, value: String?) {
        real.addRequestProperty(key, value)
    }

    override fun getRequestProperty(key: String): String? {
        return real.getRequestProperty(key)
    }

    override fun getRequestProperties(): MutableMap<String, MutableList<String>> {
        return real.requestProperties
    }

    override fun setDoInput(doinput: Boolean) {
        real.doInput = doinput
    }

    override fun getDoInput(): Boolean {
        return real.doInput
    }

    override fun setDoOutput(dooutput: Boolean) {
        real.doOutput = dooutput
    }

    override fun getDoOutput(): Boolean {
        return real.doOutput
    }

    override fun setUseCaches(usecaches: Boolean) {
        real.useCaches = usecaches
    }

    override fun getUseCaches(): Boolean {
        return real.useCaches
    }

    override fun setDefaultUseCaches(defaultusecaches: Boolean) {
        real.defaultUseCaches = defaultusecaches
    }

    override fun getDefaultUseCaches(): Boolean {
        return real.defaultUseCaches
    }

    override fun setAllowUserInteraction(allowuserinteraction: Boolean) {
        real.allowUserInteraction = allowuserinteraction
    }

    override fun getAllowUserInteraction(): Boolean {
        return real.allowUserInteraction
    }

    override fun setIfModifiedSince(ifmodifiedsince: Long) {
        real.ifModifiedSince = ifmodifiedsince
    }

    override fun getIfModifiedSince(): Long {
        return real.ifModifiedSince
    }

    override fun setConnectTimeout(timeout: Int) {
        real.connectTimeout = timeout
    }

    override fun getConnectTimeout(): Int {
        return real.connectTimeout
    }

    override fun setReadTimeout(timeout: Int) {
        real.readTimeout = timeout
    }

    override fun getReadTimeout(): Int {
        return real.readTimeout
    }

    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
        real.instanceFollowRedirects = followRedirects
    }

    override fun getInstanceFollowRedirects(): Boolean {
        return real.instanceFollowRedirects
    }

    override fun setFixedLengthStreamingMode(contentLength: Int) {
        real.setFixedLengthStreamingMode(contentLength)
    }

    override fun setFixedLengthStreamingMode(contentLength: Long) {
        real.setFixedLengthStreamingMode(contentLength)
    }

    override fun setChunkedStreamingMode(chunklen: Int) {
        real.setChunkedStreamingMode(chunklen)
    }

    override fun getPermission(): Permission {
        return real.permission
    }

    // ---------------------------------------------------------------------------------------------- the response

    @Throws(IOException::class)
    override fun getInputStream(): InputStream {
        val rule = answered() ?: return real.inputStream
        if (rule.status >= HTTP_BAD_REQUEST) {
            if (rule.status == HTTP_NOT_FOUND || rule.status == HTTP_GONE) throw FileNotFoundException(url.toString())
            throw IOException("Server returned HTTP response code: ${rule.status} for URL: $url")
        }
        return ByteArrayInputStream(mockedBody)
    }

    override fun getErrorStream(): InputStream? {
        val rule = answered() ?: return real.errorStream
        return if (rule.status >= HTTP_BAD_REQUEST) ByteArrayInputStream(mockedBody) else null
    }

    @Throws(IOException::class)
    override fun getResponseCode(): Int {
        return answered()?.status ?: real.responseCode
    }

    @Throws(IOException::class)
    override fun getResponseMessage(): String? {
        val rule = answered() ?: return real.responseMessage
        return HttpReasons.of(rule.status)
    }

    /**
     * The headers of a mocked response in the order of the status line first, then the rule's; null when it is real.
     */
    private fun mockedHeaders(rule: MockRule): List<Pair<String?, String>> {
        val headers = mutableListOf<Pair<String?, String>>()
        headers += null to "HTTP/1.1 ${rule.status} ${HttpReasons.of(rule.status)}".trim()
        headers += rule.headers.map { it.key to it.value }
        if (rule.headers.keys.none { it.equals("Content-Length", ignoreCase = true) }) {
            headers += "Content-Length" to mockedBody.size.toString()
        }
        return headers
    }

    override fun getHeaderField(name: String?): String? {
        val rule = answered() ?: return real.getHeaderField(name)
        if (name == null) return mockedHeaders(rule).first().second
        return mockedHeaders(rule).lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    override fun getHeaderField(n: Int): String? {
        val rule = answered() ?: return real.getHeaderField(n)
        return mockedHeaders(rule).getOrNull(n)?.second
    }

    override fun getHeaderFieldKey(n: Int): String? {
        val rule = answered() ?: return real.getHeaderFieldKey(n)
        return mockedHeaders(rule).getOrNull(n)?.first
    }

    override fun getHeaderFields(): MutableMap<String?, MutableList<String>> {
        val rule = answered() ?: return real.headerFields
        val fields = linkedMapOf<String?, MutableList<String>>()
        mockedHeaders(rule).forEach { (name, value) -> fields.getOrPut(name) { mutableListOf() } += value }
        return fields
    }

    override fun getHeaderFieldInt(name: String?, default: Int): Int {
        return getHeaderField(name)?.trim()?.toIntOrNull() ?: default
    }

    override fun getHeaderFieldLong(name: String?, default: Long): Long {
        return getHeaderField(name)?.trim()?.toLongOrNull() ?: default
    }

    override fun getHeaderFieldDate(name: String?, default: Long): Long {
        answered() ?: return real.getHeaderFieldDate(name, default)
        // Date, Last-Modified and Expires of the mock; the app's default when there is none or it is no date
        val value = getHeaderField(name) ?: return default
        return HttpDates.parse(value) ?: default
    }

    override fun getContentLength(): Int {
        return answered()?.let { mockedBody.size } ?: real.contentLength
    }

    override fun getContentLengthLong(): Long {
        if (answered() != null) return mockedBody.size.toLong()
        // The long form came with API 24; an older device only has the int
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) real.contentLengthLong
        else real.contentLength.toLong()
    }

    override fun getContentType(): String? {
        val rule = answered() ?: return real.contentType
        return GekkoMocks.contentTypeOf(rule)
    }

    override fun getContentEncoding(): String? {
        return getHeaderField("Content-Encoding")
    }

    override fun getContent(): Any {
        return getInputStream()
    }

    override fun getContent(classes: Array<Class<*>>): Any {
        return getInputStream()
    }

    override fun toString(): String {
        return real.toString()
    }

    // ------------------------------------------------------------------------------------------- TLS (https only)

    override fun getCipherSuite(): String {
        return (real as? HttpsURLConnection)?.cipherSuite ?: ""
    }

    override fun getLocalCertificates(): Array<Certificate>? {
        return (real as? HttpsURLConnection)?.localCertificates
    }

    override fun getServerCertificates(): Array<Certificate> {
        return (real as? HttpsURLConnection)?.serverCertificates ?: emptyArray()
    }

    override fun getPeerPrincipal(): Principal? {
        return (real as? HttpsURLConnection)?.peerPrincipal
    }

    override fun getLocalPrincipal(): Principal? {
        return (real as? HttpsURLConnection)?.localPrincipal
    }

    override fun setHostnameVerifier(v: HostnameVerifier?) {
        (real as? HttpsURLConnection)?.hostnameVerifier = v
    }

    override fun getHostnameVerifier(): HostnameVerifier? {
        return (real as? HttpsURLConnection)?.hostnameVerifier
    }

    override fun setSSLSocketFactory(sf: SSLSocketFactory?) {
        (real as? HttpsURLConnection)?.sslSocketFactory = sf
    }

    override fun getSSLSocketFactory(): SSLSocketFactory? {
        return (real as? HttpsURLConnection)?.sslSocketFactory
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
    }
}

/** What an app writes to a request that a mock answers anyway. */
private object DiscardingOutput : OutputStream() {
    override fun write(b: Int) {}

    override fun write(b: ByteArray, off: Int, len: Int) {}
}

/** The three date formats an HTTP header may have (RFC 7231); a date is always GMT. */
internal object HttpDates {
    private val FORMATS =
        listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")

    /** The milliseconds since 1970 of an HTTP date; null when [text] is none of the formats. */
    fun parse(text: String): Long? {
        for (pattern in FORMATS) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.isLenient = false
            val date = format.parse(text.trim(), ParsePosition(0))
            if (date != null) return date.time
        }
        return null
    }
}
