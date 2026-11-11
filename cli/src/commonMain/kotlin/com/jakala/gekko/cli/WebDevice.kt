package com.jakala.gekko.cli

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A browser session: Chromium, Firefox or WebKit driven by Playwright through a bridge process (see [WebBridge]). The
 * session is the device: it is started by the first command that needs it and found again by every later CLI call.
 * `clearData` is a fresh browser context, so a test starts without cookies, storage or permissions of an earlier one.
 */
class WebDevice(private val name: String, private val config: GekkoConfig, private val log: (String) -> Unit = {}) :
    Device {
    private val bridge = WebBridge(name, config, log)
    private var client: WebAgentClient? = null
    private var ready = false
    private var settings = DeviceSettings()

    /** `--record` is on: the context of every test is created with a video. */
    var recordVideo = false

    /** For `gekko install`: also install the system libraries the browser needs (Linux). */
    var withDeps = false

    init {
        require(WebSessions.isWebId(name)) { "'$name' is not a web device; use chromium, firefox or webkit" }
    }

    val engine: String
        get() = WebSessions.engineOf(name)

    /** `shared`: one browser context for all tests of a run, see [WebConfig.session]. */
    private val shared: Boolean
        get() {
            val mode = env("GEKKO_WEB_SESSION") ?: config.web.session
            if (mode !in WEB_SESSIONS) {
                throw ConfigurationException(
                    "GEKKO_WEB_SESSION must be one of ${WEB_SESSIONS.joinToString()}, was '$mode'"
                )
            }
            return mode == WEB_SESSION_SHARED
        }

    override fun agentAuth(config: GekkoConfig): ClientAuth {
        return ClientAuth.ofUser(bridge.port.toString())
    }

    override fun systemAuth(): ClientAuth {
        return ClientAuth.ofUser(bridge.port.toString())
    }

    override val supportsWaitFor: Boolean
        get() = true

    override fun agentPort(config: GekkoConfig): Int {
        return bridge.port
    }

    override fun systemPort(): Int {
        return bridge.port
    }

    // The page agent serves both roles: there is no second app in front of a page, a dialog is a window of its own
    override fun agentClient(config: GekkoConfig): CommandClient {
        return sharedClient()
    }

    override fun systemClient(config: GekkoConfig): CommandClient {
        return sharedClient()
    }

    private fun sharedClient(): WebAgentClient {
        return client ?: WebAgentClient(bridge, config).also { client = it }
    }

    /** Starts the session unless it runs; a bridge problem is a problem of the device. */
    private fun session(): WebBridge {
        if (!ready) {
            blocking { bridge.ensureRunning() }
            ready = true
        }
        return bridge
    }

    private fun <T> blocking(block: suspend () -> T): T {
        try {
            return runBlocking { block() }
        } catch (error: WebBridgeException) {
            throw IllegalStateException(error.message, error)
        }
    }

    private fun call(operation: String, fields: JsonObject = JsonObject(emptyMap())): JsonObject {
        val web = session()
        return blocking { web.call(operation, fields) }
    }

    /** True when this CLI call started the session; `gekko run` then stops it again when it is done. */
    val startedHere: Boolean
        get() = bridge.startedHere

    fun stopSession() {
        runBlocking { bridge.stop() }
        ready = false
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun screenshot(): ByteArray {
        return Base64.decode(call("screenshot")["png"]?.jsonPrimitive?.content.orEmpty())
    }

    /** An address without a scheme is a path of the page when it starts like one, otherwise a web address. */
    override fun openLink(url: String) {
        val absolute = url.contains("://") || url.startsWith("about:") || url.startsWith("data:")
        val relative = url.startsWith("/") || url.startsWith(".") || url.startsWith("?") || url.startsWith("#")
        val target = if (absolute || relative) url else "https://$url"
        call(
            "goto",
            buildJsonObject {
                put("url", JsonPrimitive(target))
                put("relative", JsonPrimitive(relative))
            },
        )
    }

    override fun tap(x: Int, y: Int) {
        call(
            "click",
            buildJsonObject {
                put("x", JsonPrimitive(x))
                put("y", JsonPrimitive(y))
            },
        )
    }

    override fun hideKeyboard() {
        call("blur")
    }

    override fun clearData(config: GekkoConfig) {
        call("closeContext")
    }

    override fun install(config: GekkoConfig, path: String?) {
        check(path == null) { "A web app is not installed from a build; set app.web in gekko.yaml" }
        WebInstall.install(config, engine, withDeps, log)
    }

    override fun launch(config: GekkoConfig, arguments: Map<String, String>) {
        val web = session()
        blocking {
            if (web.call("hello")["context"]?.jsonPrimitive?.boolean != true) {
                web.call("newContext", contextOptions(config))
            }
            web.call("goto", buildJsonObject { put("url", JsonPrimitive(appUrl(web, config, arguments))) })
        }
    }

    private fun contextOptions(config: GekkoConfig): JsonObject {
        val permissions = config.web.permissions.flatMap { PermissionNames.web(it) }
        val locale =
            when {
                settings.timeFormat == null -> settings.language
                else -> "${settings.language ?: "en-US"}-u-hc-${if (settings.timeFormat == "24h") "h23" else "h12"}"
            }
        return buildJsonObject {
            locale?.let { put("locale", JsonPrimitive(it)) }
            put("permissions", JsonArray(permissions.map { JsonPrimitive(it) }))
            // A shared session never saves a video (see startRecording), so it is not recorded either
            put("recordVideo", JsonPrimitive(recordVideo && !shared))
        }
    }

    /** The address of the app: the URL of `app.web`, or the address of the file or folder gekko serves for it. */
    private suspend fun appUrl(web: WebBridge, config: GekkoConfig, arguments: Map<String, String>): String {
        val app = config.app.web ?: throw ConfigurationException("app.web is not set in gekko.yaml")
        val base =
            if (app.contains("://")) {
                app
            } else {
                // The bridge looks at the file system: a file is served from its folder, a folder as it is
                val answer = web.call("serve", buildJsonObject { put("path", JsonPrimitive(app)) })
                answer["url"]?.jsonPrimitive?.content
                    ?: throw InfrastructureException("The web bridge did not serve $app")
            }
        if (arguments.isEmpty()) return base
        val query = arguments.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return base + (if (base.contains('?')) "&" else "?") + query
    }

    private fun encode(text: String): String {
        val safe = Regex("[A-Za-z0-9._~-]")
        return text.encodeToByteArray().joinToString("") { byte ->
            val char = (byte.toInt() and 0xff).toChar()
            if (safe.matches(char.toString())) char.toString()
            else "%" + (byte.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()
        }
    }

    override fun terminate(config: GekkoConfig) {
        if (!ready) return
        // A shared session keeps its context (and the login in it); only what the test left open is closed
        call(if (shared) "endTest" else "closeContext")
    }

    /** A test of a group is followed by another one that needs the context and the login in it. */
    override fun endTest(config: GekkoConfig, keepSession: Boolean) {
        if (keepSession && ready) {
            call("endTest")
        } else {
            terminate(config)
        }
    }

    /**
     * A shared session, and every test of a group after the first, starts in the context that is already there: no new
     * context, so cookies and a login stay, and the app is opened again. Without a context yet it is a fresh start.
     */
    override fun freshStart(
        config: GekkoConfig,
        settings: DeviceSettings,
        keepState: Boolean,
        beforeLaunch: () -> Unit,
    ): () -> Unit {
        if (shared || keepState) {
            session()
            if (hasContext()) {
                beforeLaunch()
                launch(config)
                return {}
            }
        }
        return super.freshStart(config, settings, keepState, beforeLaunch)
    }

    /** The bridge holds the mocks apart from the browser context, so they are there when the context is made. */
    override fun setStartupMocks(config: GekkoConfig, mocks: String?) {
        val rules =
            mocks?.let { Json.parseToJsonElement(it).jsonObject["rules"] as? JsonArray } ?: JsonArray(emptyList())
        call("setMocks", buildJsonObject { put("rules", rules) })
    }

    private fun hasContext(): Boolean {
        return blocking { bridge.call("hello")["context"]?.jsonPrimitive?.boolean == true }
    }

    override fun uninstall(config: GekkoConfig): List<String> {
        val stopped = runBlocking { bridge.stop() }
        ready = false
        return if (stopped) listOf("web session $name") else emptyList()
    }

    override fun startSystemRunner(config: GekkoConfig, log: (String) -> Unit) {
        session()
    }

    override fun setDarkMode(enabled: Boolean): () -> Unit {
        call("emulate", buildJsonObject { put("colorScheme", JsonPrimitive(if (enabled) "dark" else "light")) })
        return { runCatching { call("emulate", buildJsonObject { put("colorScheme", JsonNull) }) } }
    }

    override fun setAirplaneMode(enabled: Boolean): () -> Unit {
        call("emulate", buildJsonObject { put("offline", JsonPrimitive(enabled)) })
        return { runCatching { call("emulate", buildJsonObject { put("offline", JsonPrimitive(false)) }) } }
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        call(
            "emulate",
            buildJsonObject {
                put(
                    "geolocation",
                    buildJsonObject {
                        put("latitude", JsonPrimitive(latitude))
                        put("longitude", JsonPrimitive(longitude))
                    },
                )
            },
        )
    }

    override fun setPermissions(config: GekkoConfig, permissions: Map<String, Boolean>) {
        val grant = permissions.filterValues { it }.keys.flatMap { PermissionNames.web(it) }
        val revoke = permissions.filterValues { !it }.keys.flatMap { PermissionNames.web(it) }
        call(
            "permissions",
            buildJsonObject {
                put("grant", JsonArray(grant.map { JsonPrimitive(it) }))
                put("revoke", JsonArray(revoke.map { JsonPrimitive(it) }))
            },
        )
    }

    /** The language and the time format are part of a browser context, so they are applied when the context is made. */
    override fun applySettings(config: GekkoConfig, settings: DeviceSettings): () -> Unit {
        this.settings = settings
        return { this.settings = DeviceSettings() }
    }

    override fun startRecording(): Recording? {
        if (!recordVideo) return null
        if (shared) {
            // A video belongs to one browser context, and saving it closes the pages the next test needs
            log("(recording is not available in a shared web session)")
            return null
        }
        val web = session()
        return object : Recording {
            override val extension: String
                get() = "webm"

            override fun stop(path: String?): Boolean {
                val fields = buildJsonObject { put("path", path?.let { JsonPrimitive(it) } ?: JsonNull) }
                return blocking { web.call("saveVideo", fields)["saved"]?.jsonPrimitive?.boolean == true }
            }
        }
    }

    override fun consoleLog(): String? {
        if (!ready) return null
        return call("console", buildJsonObject { put("clear", JsonPrimitive(true)) })["text"]?.jsonPrimitive?.content
    }
}
