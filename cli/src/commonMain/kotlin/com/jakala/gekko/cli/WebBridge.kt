package com.jakala.gekko.cli

import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.GekkoVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where gekko keeps what the web platform needs: Playwright, the scripts of a session and the list of sessions. */
object WebHome {
    fun directory(): String {
        val home = gekkoHome() ?: error("HOME (USERPROFILE on Windows) is not set; gekko keeps its files in ~/.gekko")
        return "$home/web"
    }
}

/**
 * The web devices: a browser engine, `chromium`, `firefox` or `webkit`, optionally followed by `-2`, `-3`, ... for more
 * sessions of one engine that run side by side. A session is started by the first command that needs it and found again
 * by every later CLI call through its port.
 */
object WebSessions {
    private val pattern = Regex("(chromium|firefox|webkit)(-[0-9]+)?")
    private const val BASE = 30_000
    private const val SLOTS = 2_000

    fun isWebId(id: String): Boolean {
        return pattern.matches(id)
    }

    fun engineOf(id: String): String {
        return id.substringBefore('-')
    }

    /** Stable per session name, so every CLI call finds the bridge an earlier call started. */
    fun port(id: String): Int {
        return BASE + (id.hashCode() and 0x7fffffff) % SLOTS
    }

    fun sessionFile(id: String): String {
        return "${WebHome.directory()}/sessions/$id.json"
    }

    /** The sessions that run right now. A file of a bridge that was killed is ignored. */
    fun running(): List<String> {
        val directory = "${WebHome.directory()}/sessions"
        if (!fileExists(directory)) return emptyList()
        return listFilesRecursive(directory)
            .filter { it.endsWith(".json") }
            .mapNotNull { file ->
                val name = file.substringAfterLast('/').removeSuffix(".json")
                val pid = runCatching {
                    Json.parseToJsonElement(readTextFile(file)).jsonObject["pid"]?.jsonPrimitive?.content
                }
                    .getOrNull()
                name.takeIf { isWebId(it) && pid?.toLongOrNull()?.let { id -> isProcessAlive(id) } == true }
            }
            .sorted()
    }
}

/** Playwright and the browsers it drives, put into `~/.gekko/web` (or the directory of `web.playwright`) by gekko. */
object WebInstall {
    /** The Playwright release gekko was built and tested with. */
    const val PLAYWRIGHT_VERSION = "1.64.1"
    private const val MIN_NODE_MAJOR = 18
    private const val INSTALL_TIMEOUT_SECONDS = 1_200

    fun node(): String {
        return env("GEKKO_NODE") ?: "node"
    }

    /**
     * npm as a command. On Windows `npm` is a `.cmd` file that only runs through cmd.exe, so it is started as `node
     * npm-cli.js` when that script sits next to node.exe (where the Node.js installers put it).
     */
    fun npm(): List<String> {
        if (isWindowsHost) {
            val program = resolveWindowsProgram(node(), env("PATH"), env("PATHEXT"), ::fileExists)
            val script = program?.let { slashes(it).substringBeforeLast('/') + "/node_modules/npm/bin/npm-cli.js" }
            if (program != null && script != null && fileExists(script)) {
                return listOf(program, script)
            }
        }
        return listOf("npm")
    }

    /** The directory whose node_modules hold Playwright: `web.playwright`, `GEKKO_PLAYWRIGHT_DIR` or gekko's own. */
    fun playwrightDirectory(config: GekkoConfig): String {
        val chosen = env("GEKKO_PLAYWRIGHT_DIR") ?: config.web.playwright?.takeIf { it != InstalledRunners.INSTALLED }
        return chosen ?: WebHome.directory()
    }

    fun isManaged(config: GekkoConfig): Boolean {
        return playwrightDirectory(config) == WebHome.directory()
    }

    private fun cli(directory: String): String {
        return "$directory/node_modules/playwright/cli.js"
    }

    fun isInstalled(config: GekkoConfig): Boolean {
        return fileExists(cli(playwrightDirectory(config)))
    }

    /** Fails with a way out when Node.js is missing or too old. */
    fun requireNode() {
        val result = runCatching { runProcess(listOf(node(), "--version")) }.getOrNull()
        val major = result?.takeIf { it.exitCode == 0 }?.text?.removePrefix("v")?.substringBefore('.')?.toIntOrNull()
        if (major == null) {
            throw ConfigurationException(
                "Node.js $MIN_NODE_MAJOR or newer is needed for the web platform, but `${node()}` did not answer; install it from https://nodejs.org"
            )
        }
        if (major < MIN_NODE_MAJOR) {
            throw ConfigurationException(
                "Node.js $MIN_NODE_MAJOR or newer is needed for the web platform, found $major"
            )
        }
    }

    /**
     * Puts Playwright in place (unless `web.playwright` names a project's own) and downloads [engine]. Safe to repeat:
     * what is there stays.
     */
    fun install(config: GekkoConfig, engine: String, withDeps: Boolean, log: (String) -> Unit) {
        requireNode()
        val directory = playwrightDirectory(config)
        if (isManaged(config) && !fileExists(cli(directory))) {
            log("Installing Playwright $PLAYWRIGHT_VERSION into $directory")
            makeDirs(directory)
            writeTextFile(
                "$directory/package.json",
                """{ "name": "gekko-web", "private": true, "dependencies": { "playwright": "$PLAYWRIGHT_VERSION" } }""" +
                    "\n",
            )
            val npm =
                runProcess(
                    npm() + listOf("install", "--prefix", directory, "--no-audit", "--no-fund", "--loglevel=error"),
                    timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                )
            check(npm.exitCode == 0) { "npm install playwright failed: ${npm.text.takeLast(2_000)}" }
        }
        check(fileExists(cli(directory))) {
            "Playwright was not found in $directory/node_modules; run: npm install playwright"
        }
        log("Installing $engine")
        val arguments =
            listOf(node(), cli(directory), "install") + (if (withDeps) listOf("--with-deps") else emptyList())
        val browser = runProcess(arguments + engine, timeoutSeconds = INSTALL_TIMEOUT_SECONDS)
        check(browser.exitCode == 0) { "playwright install $engine failed: ${browser.text.takeLast(2_000)}" }
    }
}

/** Something the bridge refused or could not do. [kind] is one of the protocol's `ErrorKind`s, or null. */
class WebBridgeException(message: String, val kind: String?, val transient: Boolean = false) : Exception(message)

/** What the web agent needs from the bridge: one request, one answer. */
interface WebRpc {
    suspend fun call(operation: String, fields: JsonObject = JsonObject(emptyMap())): JsonObject

    /** True while the bridge answers. */
    suspend fun isUp(): Boolean

    fun close()
}

/**
 * The bridge process of one web device: a Node process that holds Playwright and the browser. It is started on demand,
 * outlives the CLI call that started it (so `gekko tree` finds the browser `gekko launch` opened) and stops by itself
 * when it has not been used for a while.
 */
class WebBridge(
    private val name: String,
    private val config: GekkoConfig,
    private val log: (String) -> Unit = {},
) : WebRpc {
    val port: Int = WebSessions.port(name)
    var startedHere = false
        private set

    /** The release and a checksum of the scripts: a session with other scripts than the CLI carries is replaced. */
    private val version: String =
        "${GekkoVersion.NAME}+" + (WebAssets.BRIDGE_SCRIPT + WebAssets.PAGE_SCRIPT).hashCode().toUInt().toString(16)

    private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS } }
    private val json = Json { ignoreUnknownKeys = true }

    // Whatever listens on the port of the session can answer; only a bridge that holds the key of this user is believed
    private val peer =
        SignedPeer(
            http,
            port,
            ClientAuth.ofUser(port.toString()),
            "gekko web bridge",
            "A bridge of an older gekko is replaced by itself; for another program, stop it or name this web session " +
                "differently (for example $name-2).",
        )

    override suspend fun call(operation: String, fields: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("op", JsonPrimitive(operation))
            fields.forEach { (key, value) -> put(key, value) }
        }
        val reply = peer.send(body.toString().encodeToByteArray(), operation == "hello", HELLO_BODY) { isStale(it) }
        val answer = json.parseToJsonElement(reply.body.decodeToString()).jsonObject
        if (answer["ok"]?.jsonPrimitive?.boolean != true) {
            throw WebBridgeException(
                answer["error"]?.jsonPrimitive?.contentOrNull ?: "the web bridge failed",
                answer["kind"]?.jsonPrimitive?.contentOrNull,
                answer["transient"]?.jsonPrimitive?.boolean ?: false,
            )
        }
        return answer
    }

    override suspend fun isUp(): Boolean {
        return hello() != null
    }

    /** A signed refusal that names another session: the bridge was restarted since the last hello. */
    private fun isStale(reply: SignedPeer.Reply): Boolean {
        val answer = runCatching { json.parseToJsonElement(reply.body.decodeToString()).jsonObject }.getOrNull()
        return answer?.get("kind")?.jsonPrimitive?.contentOrNull == ErrorKind.SESSION
    }

    /**
     * The answer of the bridge, null when nothing answers. Something that answers without proving that it is the bridge
     * of this user is not null but an [UntrustedServerException].
     */
    private suspend fun hello(): JsonObject? {
        return try {
            withTimeoutOrNull(HELLO_TIMEOUT_MS) { call("hello") }
        } catch (error: UntrustedServerException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    /** Starts the bridge and the browser unless they run already; replaces a bridge of another gekko release. */
    suspend fun ensureRunning() {
        val engine = WebSessions.engineOf(name)
        var answer =
            try {
                hello()
            } catch (error: UntrustedServerException) {
                replaceUnsignedBridge(error)
                null
            }
        if (answer != null && answer["version"]?.jsonPrimitive?.contentOrNull != version) {
            log("Replacing the web session $name: it belongs to another gekko build")
            stop()
            answer = null
        }
        if (answer == null) {
            launchProcess()
            startedHere = true
        }
        val viewport = config.web.viewport.split('x').map { it.toInt() }
        call(
            "start",
            buildJsonObject {
                put("browser", JsonPrimitive(engine))
                put("headless", JsonPrimitive(env("GEKKO_WEB_HEADED") == null && config.web.headless))
                put(
                    "viewport",
                    buildJsonObject {
                        put("width", JsonPrimitive(viewport[0]))
                        put("height", JsonPrimitive(viewport[1]))
                    },
                )
                config.web.device?.let { put("device", JsonPrimitive(it)) }
            },
        )
    }

    /**
     * A bridge of a gekko from before the signed protocol cannot prove who it is, and gekko does not tell it to quit
     * (nothing is sent to something that is not known to be the bridge). It is ended through the process that its own
     * session file names, which only this user can have written; anything else that holds the port stays and fails the
     * start with [untrusted].
     */
    private suspend fun replaceUnsignedBridge(untrusted: UntrustedServerException) {
        val pid = ownBridgePid() ?: throw untrusted
        log("Replacing the web session $name: its bridge does not sign its answers (an older gekko)")
        runProcess(listOf("kill", pid))
        val deadline = nowMillis() + STOP_TIMEOUT_MS
        while (nowMillis() < deadline) {
            val held =
                try {
                    hello() != null
                } catch (error: UntrustedServerException) {
                    true
                }
            if (!held) return
            delay(POLL_MS)
        }
        throw untrusted
    }

    /** The process of this session's bridge when its session file names one that really runs a bridge on [port]. */
    private fun ownBridgePid(): String? {
        // There is no gekko from before the signed protocol on Windows, and no `ps` or `kill` there either
        if (isWindowsHost) return null
        val file = WebSessions.sessionFile(name)
        if (!fileExists(file)) return null
        val pid =
            runCatching { Json.parseToJsonElement(readTextFile(file)).jsonObject["pid"]?.jsonPrimitive?.content }
                .getOrNull()
                ?.takeIf { candidate -> candidate.isNotEmpty() && candidate.all { it in '0'..'9' } } ?: return null
        val command = runProcess(listOf("ps", "-p", pid, "-o", "command=")).text
        return pid.takeIf { command.contains("bridge.mjs") && command.contains("--port $port") }
    }

    private suspend fun launchProcess() {
        WebInstall.requireNode()
        val playwright = WebInstall.playwrightDirectory(config)
        if (!WebInstall.isInstalled(config)) {
            throw ConfigurationException(
                "Playwright is not installed in $playwright; run: gekko install --platform web (needs Node.js 18 or newer)"
            )
        }
        // One folder per session: sessions that start at the same time never rewrite a script another one is reading
        val scripts = "${WebHome.directory()}/bridge/$name"
        makeDirs(scripts)
        writeTextFile("$scripts/bridge.mjs", WebAssets.BRIDGE_SCRIPT)
        writeTextFile("$scripts/page.js", WebAssets.PAGE_SCRIPT)
        makeDirs("${WebHome.directory()}/sessions")
        makeDirs("${WebHome.directory()}/logs")
        val logFile = "${WebHome.directory()}/logs/$name.log"
        // Every start has a log of its own: the hint after a failure must not come from an earlier one
        runCatching { writeTextFile(logFile, "") }
        val started =
            spawnDetached(
                listOf(
                    WebInstall.node(),
                    "$scripts/bridge.mjs",
                    "--port",
                    "$port",
                    "--version",
                    version,
                    "--playwright-dir",
                    playwright,
                    "--session",
                    WebSessions.sessionFile(name),
                ),
                environment = mapOf("GEKKO_BRIDGE_TOKEN" to UserToken.value),
                logFile = logFile,
            )
        if (!started) {
            throw InfrastructureException("The web bridge could not be started: `${WebInstall.node()}` is not runnable")
        }
        val deadline = nowMillis() + START_TIMEOUT_MS
        while (nowMillis() < deadline) {
            if (hello() != null) return
            delay(POLL_MS)
        }
        val tail = if (fileExists(logFile)) readTextFile(logFile).takeLast(1_000) else ""
        val hint =
            if (tail.contains("EADDRINUSE")) {
                " Port $port is taken by another program or by a web session that belongs to another user or GEKKO_HOME;" +
                    " stop that one or name this session differently (for example $name-2)."
            } else {
                ""
            }
        throw InfrastructureException(
            "The web bridge did not start within ${START_TIMEOUT_MS / 1_000}s. $tail$hint".trim()
        )
    }

    /** Closes the browser and ends the bridge; true when there was one. */
    suspend fun stop(): Boolean {
        val running =
            try {
                hello() != null
            } catch (error: UntrustedServerException) {
                // A bridge of an older gekko cannot sign, so it is not asked to quit but ended through its session
                // file;
                // anything else that holds the port is reported
                replaceUnsignedBridge(error)
                return true
            }
        if (!running) return false
        runCatching { call("shutdown") }
        val deadline = nowMillis() + STOP_TIMEOUT_MS
        while (nowMillis() < deadline && hello() != null) {
            delay(POLL_MS)
        }
        return true
    }

    override fun close() {
        http.close()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 120_000L
        const val HELLO_TIMEOUT_MS = 2_000L
        const val START_TIMEOUT_MS = 20_000L
        const val STOP_TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
        val HELLO_BODY = """{"op":"hello"}""".encodeToByteArray()
    }
}

internal fun JsonObject.string(key: String): String? {
    val value = this[key]
    return if (value is JsonPrimitive && value.isString) value.content else null
}

internal fun JsonElement?.primitiveOrNull(): JsonPrimitive? {
    return (this as? JsonPrimitive)?.takeIf { it.contentOrNull != null }
}
