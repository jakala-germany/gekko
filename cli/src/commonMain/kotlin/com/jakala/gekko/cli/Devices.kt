package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

private val protocolJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** One server on a device (the in-app agent or the system runner). */
interface CommandClient {
    suspend fun send(command: Command): Response

    suspend fun isReachable(): Boolean

    fun close()
}

/**
 * Talks to one server on the device. [role] says which one ([Protocol.ROLE_AGENT] or [Protocol.ROLE_RUNNER]); [token]
 * is sent with every request where the server wants one (iOS).
 */
class GekkoClient(private val port: Int, private val role: String, private val token: String? = null) : CommandClient {
    private companion object {
        const val REACHABLE_TIMEOUT_MS = 3_000L
    }

    private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 60_000 } }

    override suspend fun send(command: Command): Response {
        val body = protocolJson.encodeToString(Command.serializer(), command)
        val text =
            http
                .post("http://127.0.0.1:$port/") {
                    token?.let { header(Protocol.TOKEN_HEADER, it) }
                    setBody(body)
                }
                .bodyAsText()
        return protocolJson.decodeFromString(Response.serializer(), text)
    }

    /** True when the server answers `hello`; throws [IncompatibleVersionException] when it speaks another protocol. */
    override suspend fun isReachable(): Boolean {
        val answer =
            runCatching { withTimeoutOrNull(REACHABLE_TIMEOUT_MS) { send(Command.Hello) } }.getOrNull() ?: return false
        Compatibility.check(answer, role)
        return true
    }

    override fun close() {
        http.close()
    }
}

/**
 * Abstract socket of the in-app agent on Android: reachable only through adb and without an INTERNET permission, so no
 * other app can talk to it. The app id keeps several debug apps with an agent from fighting over one name.
 */
fun agentSocketName(appId: String): String {
    return "gekko-$appId"
}

/** Abstract socket of the Android system runner (an app of its own, so the name is fixed). */
const val SYSTEM_RUNNER_SOCKET = "gekko-system-runner"

/** A screen recording in progress. */
interface Recording {
    /**
     * Stops the recording and saves the video to [path]; with a null [path] it is thrown away. True when one was saved.
     */
    fun stop(path: String?): Boolean
}

interface Device {
    fun screenshot(): ByteArray

    fun pressKey(key: String)

    fun openLink(url: String)

    /** Host port (on 127.0.0.1) that reaches the in-app agent of this device; never shared between devices. */
    fun agentPort(config: GekkoConfig): Int

    /** Host port (on 127.0.0.1) that reaches the system runner of this device. */
    fun systemPort(): Int

    /**
     * The secret the servers on this device want in the header of every request; null when they need none (Android).
     */
    val token: String?
        get() = null

    /**
     * True when the agent and the system runner answer [Command.WaitFor] and the agent reports by itself that the app
     * is not in front ([Response.NotForeground]); then the CLI neither asks for the foreground before every command nor
     * reads the tree over the wire to wait for an element. Only Android does so; the iOS agents do not.
     */
    val supportsWaitFor: Boolean
        get() = false

    fun clearData(config: GekkoConfig)

    /** Installs the app under test from [path] (the app of `gekko.yaml` when null); keeps its data. */
    fun install(config: GekkoConfig, path: String?)

    /** Starts the app; [arguments] are handed to it (Android intent extras, iOS launch arguments). */
    fun launch(config: GekkoConfig, arguments: Map<String, String> = emptyMap())

    /** True while the app under test has a process; null when this device cannot tell. */
    fun isAppRunning(config: GekkoConfig): Boolean? {
        return null
    }

    /** Starts recording the screen; null when this device cannot. */
    fun startRecording(): Recording? {
        return null
    }

    /**
     * Removes everything gekko put on the device: the app under test, the system runner and the port forwards. Fails
     * when something is still there afterwards. Returns what was removed.
     */
    fun uninstall(config: GekkoConfig): List<String>

    /** Quits the app under test, e.g. when a test is done. */
    fun terminate(config: GekkoConfig)

    /** Force-stops the apps of `android.stopApps` so nothing of an earlier test is left in front. */
    fun stopOtherApps(config: GekkoConfig) {}

    /** Taps a spot of the screen given in pixels. */
    fun tap(x: Int, y: Int) {
        error("tapping a point is not supported on this device")
    }

    /** Closes the on-screen keyboard when it is open. */
    fun hideKeyboard() {
        error("hideKeyboard is not supported on this device")
    }

    /** Starts another app (not the one under test), e.g. the calendar. */
    fun launchPackage(id: String) {
        error("launchPackage is not supported on this device")
    }

    /** Force-stops another app (not the one under test). */
    fun stopPackage(id: String) {
        error("stopPackage is not supported on this device")
    }

    /** Today's date on the device as `yyyy-MM-dd`. */
    fun today(): String {
        return Dates.isoDate(nowMillis())
    }

    /** Switches dark mode; returns the function that restores the previous setting. */
    fun setDarkMode(enabled: Boolean): () -> Unit {
        error("setDarkMode is not supported on this device")
    }

    /** Switches airplane mode; returns the function that restores the previous setting. */
    fun setAirplaneMode(enabled: Boolean): () -> Unit {
        error("setAirplaneMode is not supported on this device")
    }

    fun setLocation(latitude: Double, longitude: Double) {
        error("setLocation is not supported on this device")
    }

    /**
     * Switches the system runner on as the mock location app, so that it can answer for the location of a device that
     * has no emulator console; the runner has to be installed.
     */
    fun allowMockLocation() {
        error("setLocation is not supported on this device")
    }

    /** Grants (true) or revokes (false) permissions of the app under test; keys are test-level names. */
    fun setPermissions(config: GekkoConfig, permissions: Map<String, Boolean>) {
        error("setPermissions is not supported on this device")
    }

    /**
     * Puts the device into the language / time format a test asks for; the returned function restores the previous
     * state.
     */
    fun applySettings(config: GekkoConfig, settings: DeviceSettings): () -> Unit {
        return {}
    }

    /**
     * Clears the app data, applies [settings] and launches the app. The settings go in after the data was cleared
     * because clearing also wipes per-app state such as the app language. Returns the function that restores them.
     */
    fun freshStart(config: GekkoConfig, settings: DeviceSettings = DeviceSettings()): () -> Unit {
        stopOtherApps(config)
        clearData(config)
        val restore = applySettings(config, settings)
        launch(config)
        return restore
    }

    /** How long a freshly started system runner may take to answer. */
    val runnerStartTimeoutMs: Long
        get() = 20_000

    /** Starts the out-of-process runner (UiAutomator / XCUITest) that serves [systemPort]. */
    fun startSystemRunner(config: GekkoConfig, log: (String) -> Unit)
}

private const val RECORDING_LIMIT_SECONDS = 180

class AdbDevice(val serial: String?) : Device {
    fun adb(vararg args: String, mergeStderr: Boolean = true): ProcessResult {
        val prefix = if (serial != null) listOf("adb", "-s", serial) else listOf("adb")
        return runProcess(prefix + args.toList(), mergeStderr)
    }

    /** Runs a command on the device; every argument arrives as one word, whatever characters it has. */
    fun shell(vararg args: String): ProcessResult {
        return adb("shell", args.joinToString(" ") { shellQuote(it) })
    }

    /** Runs a shell script on the device, e.g. to start something in the background. */
    fun shellScript(script: String): ProcessResult {
        return adb("shell", script)
    }

    override fun screenshot(): ByteArray {
        return adb("exec-out", "screencap", "-p", mergeStderr = false).stdout
    }

    fun isInstalled(appId: String): Boolean {
        return shell("pm", "list", "packages", appId).text.lines().any { it.trim() == "package:$appId" }
    }

    /** Stops before any step on a device that is too old, where the commands below would misbehave silently. */
    fun requireSupportedApi() {
        unsupportedApi(serial, shell("getprop", "ro.build.version.sdk").text)?.let { throw InfrastructureException(it) }
    }

    private val hostPorts = mutableMapOf<String, Int>()

    override val supportsWaitFor: Boolean
        get() = true

    override fun agentPort(config: GekkoConfig): Int {
        return hostPort(agentSocketName(androidAppId(config)))
    }

    override fun systemPort(): Int {
        return hostPort(SYSTEM_RUNNER_SOCKET)
    }

    /**
     * The agent and the runner listen on an abstract socket inside each device; adb maps it to a free host port that is
     * unique per device.
     */
    private fun hostPort(socket: String): Int {
        return hostPorts.getOrPut(socket) { existingForward(socket) ?: newForward(socket) }
    }

    private fun existingForward(socket: String): Int? {
        val id = serial ?: return null
        return adb("forward", "--list")
            .text
            .lines()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 3 && it[0] == id && it[2] == "localabstract:$socket" && it[1].startsWith("tcp:") }
            ?.get(1)
            ?.removePrefix("tcp:")
            ?.toIntOrNull()
    }

    private fun newForward(socket: String): Int {
        val output = adb("forward", "tcp:0", "localabstract:$socket", mergeStderr = false).text
        return output.lines().firstNotNullOfOrNull { it.trim().toIntOrNull() }
            ?: error("adb could not forward $socket of ${serial ?: "the device"}: $output")
    }

    override fun pressKey(key: String) {
        val code =
            when (key.lowercase()) {
                "back" -> "4"
                "home" -> "3"
                "enter" -> "66"
                "tab" -> "61"
                else -> key
            }
        shell("input", "keyevent", code)
    }

    override fun openLink(url: String) {
        shell("am", "start", "-a", "android.intent.action.VIEW", "-d", url)
    }

    override fun tap(x: Int, y: Int) {
        shell("input", "tap", x.toString(), y.toString())
    }

    override fun hideKeyboard() {
        if (shell("dumpsys", "input_method").text.contains("mInputShown=true")) {
            pressKey("back")
        }
    }

    override fun launchPackage(id: String) {
        val result = shell("monkey", "-p", id, "-c", "android.intent.category.LAUNCHER", "1")
        check(result.exitCode == 0 && !result.text.contains("No activities found")) {
            "Could not start $id: ${result.text}"
        }
    }

    override fun stopPackage(id: String) {
        shell("am", "force-stop", id)
    }

    override fun today(): String {
        return shell("date", "+%Y-%m-%d").text.trim()
    }

    override fun launch(config: GekkoConfig, arguments: Map<String, String>) {
        val appId = androidAppId(config)
        if (!isInstalled(appId)) {
            throw InfrastructureException(
                "$appId is not installed on ${serial ?: "the device"}; install it (gekko install, --app)"
            )
        }
        val activity = config.app.androidActivity
        if (activity != null) {
            shell("am", "start", "-n", "$appId/$activity", *extras(arguments))
        } else if (arguments.isNotEmpty()) {
            // monkey cannot pass extras: start the launcher activity by name
            val component =
                shell("cmd", "package", "resolve-activity", "--brief", appId).text.lines().lastOrNull {
                    it.contains("/")
                } ?: error("No launcher activity found for $appId; set app.androidActivity in gekko.yaml")
            shell("am", "start", "-n", component.trim(), *extras(arguments))
        } else {
            shell("monkey", "-p", appId, "-c", "android.intent.category.LAUNCHER", "1")
        }
    }

    private fun extras(arguments: Map<String, String>): Array<String> {
        return arguments.flatMap { (key, value) -> listOf("--es", key, value) }.toTypedArray()
    }

    override fun isAppRunning(config: GekkoConfig): Boolean {
        return shell("pidof", androidAppId(config)).exitCode == 0
    }

    override fun startRecording(): Recording {
        val remote = "/sdcard/gekko-record-${nowMillis()}.mp4"
        shellScript("nohup screenrecord --time-limit $RECORDING_LIMIT_SECONDS $remote >/dev/null 2>&1 &")
        return object : Recording {
            override fun stop(path: String?): Boolean {
                // SIGINT lets screenrecord finish the file
                shellScript("kill -2 \$(pidof screenrecord) 2>/dev/null; sleep 1")
                val saved = path != null && adb("pull", remote, path).exitCode == 0
                shell("rm", "-f", remote)
                return saved
            }
        }
    }

    override fun terminate(config: GekkoConfig) {
        shell("am", "force-stop", androidAppId(config))
        stopOtherApps(config)
    }

    override fun stopOtherApps(config: GekkoConfig) {
        config.android.stopApps.forEach { shell("am", "force-stop", it) }
    }

    override fun setDarkMode(enabled: Boolean): () -> Unit {
        // `cmd uimode night` answers "Night mode: yes|no|auto"
        val previous = shell("cmd", "uimode", "night").text.substringAfterLast(':').trim().ifEmpty { "auto" }
        val result = shell("cmd", "uimode", "night", if (enabled) "yes" else "no")
        checkCmd(result, "Switching dark mode failed", minApi = 29)
        return { shell("cmd", "uimode", "night", previous) }
    }

    override fun setAirplaneMode(enabled: Boolean): () -> Unit {
        val wasOn = shell("settings", "get", "global", "airplane_mode_on").text.trim() == "1"
        switchAirplaneMode(enabled)
        return { switchAirplaneMode(wasOn) }
    }

    private fun switchAirplaneMode(enabled: Boolean) {
        val result = shell("cmd", "connectivity", "airplane-mode", if (enabled) "enable" else "disable")
        checkCmd(result, "Switching airplane mode failed", minApi = 30)
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        // The emulator console wants longitude first; a physical device has no console
        val result = adb("emu", "geo", "fix", longitude.toString(), latitude.toString())
        check(result.exitCode == 0 && result.text.contains("OK")) {
            "setLocation works on Android emulators only: ${result.text}"
        }
    }

    override fun allowMockLocation() {
        val result = shell("appops", "set", Drivers.RUNNER_PACKAGE, "android:mock_location", "allow")
        check(result.exitCode == 0 && !result.text.contains("Exception") && !result.text.startsWith("Error")) {
            "Could not allow mock locations for the gekko system runner: ${result.text}"
        }
    }

    override fun setPermissions(config: GekkoConfig, permissions: Map<String, Boolean>) {
        val appId = androidAppId(config)
        for ((name, granted) in permissions) {
            val results =
                PermissionNames.android(name).map { shell("pm", if (granted) "grant" else "revoke", appId, it) }
            // An app declares only some of the permissions of a group, so one success is enough
            check(results.any { it.exitCode == 0 && !it.text.contains("Exception") }) {
                "Setting permission '$name' failed: ${results.joinToString { it.text }}"
            }
        }
    }

    override fun clearData(config: GekkoConfig) {
        val appId = androidAppId(config)
        shell("am", "force-stop", appId)
        shell("pm", "clear", appId)
        config.android.permissions.forEach { shell("pm", "grant", appId, it) }
    }

    override fun uninstall(config: GekkoConfig): List<String> {
        val appId = androidAppId(config)
        val packages = listOf(appId, Drivers.RUNNER_PACKAGE, "${Drivers.RUNNER_PACKAGE}.test")
        val removed = mutableListOf<String>()
        for (id in packages.filter { isInstalled(it) }) {
            shell("am", "force-stop", id)
            val result = adb("uninstall", id)
            check(result.text.contains("Success")) { "Uninstalling $id failed: ${result.text}" }
            removed += id
        }
        removed += removeForwards(config)
        val left = packages.filter { isInstalled(it) }
        check(left.isEmpty()) { "Still installed after uninstalling: ${left.joinToString()}" }
        return removed
    }

    /**
     * Removes the host ports that adb mapped to the agent and the system runner of this device, including the TCP
     * forwards (7777, 7778) that gekko versions before the private sockets left behind.
     */
    private fun removeForwards(config: GekkoConfig): List<String> {
        val id = serial ?: return emptyList()
        val ours =
            setOf(
                "localabstract:${agentSocketName(androidAppId(config))}",
                "localabstract:$SYSTEM_RUNNER_SOCKET",
                "tcp:7777",
                "tcp:7778",
            )
        val forwards =
            adb("forward", "--list")
                .text
                .lines()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size == 3 && it[0] == id && it[2] in ours }
        forwards.forEach { adb("-s", id, "forward", "--remove", it[1]) }
        hostPorts.clear()
        return forwards.map { "forward ${it[1]} -> ${it[2]}" }
    }

    override fun install(config: GekkoConfig, path: String?) {
        val apk = path ?: config.android.apk ?: error("No app to install; pass a path or set android.apk in gekko.yaml")
        check(fileExists(apk)) { "App not found: $apk" }
        val result = adb("install", "-r", "-t", apk)
        check(result.text.contains("Success")) { "Installing $apk failed: ${result.text}" }
    }

    /**
     * Always installs the runner that belongs to this gekko: one left behind by another release, or signed with another
     * debug key, is removed first, so an install over it cannot fail and a stale runner is never started.
     */
    override fun startSystemRunner(config: GekkoConfig, log: (String) -> Unit) {
        // The environment wins: a CI action installs the runner for the job without touching gekko.yaml
        val apk =
            env("GEKKO_RUNNER_APK")
                ?: config.android.runnerApk?.takeUnless { it == InstalledRunners.INSTALLED }
                ?: InstalledRunners.androidApk()
                ?: error("System runner not found; run scripts/install.sh or set android.runnerApk in gekko.yaml")
        check(fileExists(apk)) { "System runner not found: $apk" }
        for (id in listOf(Drivers.RUNNER_PACKAGE, "${Drivers.RUNNER_PACKAGE}.test").filter { isInstalled(it) }) {
            shell("am", "force-stop", id)
            adb("uninstall", id)
        }
        log("Installing system runner")
        val result = adb("install", "-r", apk)
        check(result.text.contains("Success")) { "Installing the system runner failed: ${result.text}" }
        shellScript(
            "nohup am instrument -w -e class ${Drivers.RUNNER_CLASS} ${Drivers.RUNNER_PACKAGE}/androidx.test.runner.AndroidJUnitRunner " +
                ">/dev/null 2>&1 &"
        )
    }

    override fun applySettings(config: GekkoConfig, settings: DeviceSettings): () -> Unit {
        val restores = mutableListOf<() -> Unit>()
        settings.timeFormat?.let { format ->
            val previous = shell("settings", "get", "system", "time_12_24").text
            shell("settings", "put", "system", "time_12_24", if (format == "24h") "24" else "12")
            restores += {
                if (previous == "null" || previous.isEmpty()) {
                    shell("settings", "delete", "system", "time_12_24")
                } else {
                    shell("settings", "put", "system", "time_12_24", previous)
                }
            }
        }
        settings.language?.let { tag ->
            val appId = androidAppId(config)
            val previous =
                Regex("\\[(.*)]")
                    .find(shell("cmd", "locale", "get-app-locales", appId).text)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            checkCmd(
                shell("cmd", "locale", "set-app-locales", appId, "--locales", tag),
                "Setting the app language failed",
                minApi = 33,
            )
            restores += { shell("cmd", "locale", "set-app-locales", appId, "--locales", previous) }
        }
        return { restores.forEach { it() } }
    }

    private fun androidAppId(config: GekkoConfig): String {
        return config.app.android ?: throw IllegalStateException("app.android is not set in gekko.yaml")
    }

    companion object {
        /** Below this API level `adb shell` returns no exit codes and has no `cmd`, which the CLI relies on. */
        const val MIN_API = 24

        /**
         * The error for a device whose `ro.build.version.sdk` is [sdkProperty], or null when it is fine or unreadable.
         */
        fun unsupportedApi(serial: String?, sdkProperty: String): String? {
            val api = sdkProperty.trim().toIntOrNull()
            if (api == null || api >= MIN_API) {
                return null
            }
            return "${serial ?: "The device"} runs Android API $api; gekko needs an emulator or device with API " +
                "$MIN_API (Android 7.0) or newer"
        }

        /** `cmd` answers a service or command that the device does not have with exit code 0, only the text tells. */
        fun checkCmd(result: ProcessResult, failure: String, minApi: Int) {
            val text = result.text
            check(!text.contains("No shell command implementation") && !text.contains("Can't find service")) {
                "$failure: this needs Android API $minApi or newer on the device"
            }
            check(result.exitCode == 0) { "$failure: $text" }
        }

        fun listSerials(): List<String> {
            return runProcess(listOf("adb", "devices"))
                .text
                .lines()
                .drop(1)
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size == 2 && it[1] == "device" }
                .map { it[0] }
        }
    }
}

/**
 * Routes commands to the in-process agent while the app owns the focus and to the system runner (UiAutomator)
 * otherwise, e.g. for the browser or permission dialogs.
 */
class Drivers(
    private val device: Device,
    private val config: GekkoConfig,
    private val agent: CommandClient = GekkoClient(device.agentPort(config), Protocol.ROLE_AGENT, device.token),
    private val system: CommandClient = GekkoClient(device.systemPort(), Protocol.ROLE_RUNNER, device.token),
    private val log: (String) -> Unit = {},
) {
    private var systemStarted = false

    /**
     * True when [Command.WaitFor] can be used instead of reading the tree over the wire; see [Device.supportsWaitFor].
     */
    val supportsWaitFor: Boolean
        get() = device.supportsWaitFor

    suspend fun send(command: Command, forceSystem: Boolean = false): Response {
        if (forceSystem) {
            return sendToSystemRunner(command)
        }
        if (device.supportsWaitFor) {
            return sendAgentFirst(command)
        }
        val driver = pick()
        debug(if (driver === system) "system runner" else "in-app agent", command)
        return if (driver === system) sendToSystemRunner(command) else driver.send(command)
    }

    /**
     * The agent answers [Response.NotForeground] by itself when the app is not in front, so the usual case costs one
     * request instead of a foreground check plus the command.
     */
    private suspend fun sendAgentFirst(command: Command): Response {
        var response = askAgent(command)
        // A dialog or sheet opening takes the focus from the activity for a moment; starting the system runner for that
        // costs seconds, so the agent gets a short grace period. It did not run the command, so asking again is safe.
        var graceLeft = FOREGROUND_GRACE_ATTEMPTS
        while (response is Response.NotForeground && graceLeft > 0) {
            graceLeft--
            delay(FOREGROUND_GRACE_POLL_MS)
            response = askAgent(command)
        }
        if (response != null && response !is Response.NotForeground) {
            debug("in-app agent", command)
            return response
        }
        debug("system runner", command)
        return sendToSystemRunner(command)
    }

    /** Null when nothing listens (app crashed or not started): the system runner then shows what is on screen. */
    private suspend fun askAgent(command: Command): Response? {
        val budgetMs = command.budgetMs() + AGENT_ANSWER_MARGIN_MS
        return try {
            withTimeoutOrNull(budgetMs) { agent.send(command) }
                ?: Response.Error("The app did not answer within ${budgetMs}ms; is it hung?")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    private fun debug(driver: String, command: Command) {
        if (env("GEKKO_DEBUG") != null) {
            log("    [driver] $driver: ${command::class.simpleName}")
        }
    }

    /** How long a command may legitimately take on the device, before the answer is overdue. */
    private fun Command.budgetMs(): Long {
        return when (this) {
            is Command.Tap -> timeoutMs
            is Command.DoubleTap -> timeoutMs
            is Command.LongPress -> holdMs + timeoutMs
            is Command.Type -> timeoutMs
            is Command.Scroll -> timeoutMs
            is Command.WaitFor -> timeoutMs
            is Command.WaitIdle -> timeoutMs
            is Command.Swipe -> durationMs
            is Command.TapAt,
            is Command.SetOrientation,
            is Command.SetLocation,
            Command.ClearLocation,
            Command.HideKeyboard,
            Command.Hello,
            Command.Metrics,
            Command.Tree,
            Command.Foreground,
            Command.Screenshot -> 0L
        }
    }

    /** The runner can have exited since it was started (e.g. a failed XCTest assertion), so restart it once. */
    private suspend fun sendToSystemRunner(command: Command): Response {
        ensureSystemRunner()
        return try {
            system.send(command)
        } catch (error: Exception) {
            systemStarted = false
            ensureSystemRunner()
            system.send(command)
        }
    }

    suspend fun waitForAgent(timeoutMs: Long): Boolean {
        val deadline = nowMillis() + timeoutMs
        while (nowMillis() < deadline) {
            if (agent.isReachable()) {
                return true
            }
            delay(POLL_MS)
        }
        return false
    }

    /** Waits until the app owns the focus, e.g. after a cold start when the agent answers before the first window. */
    suspend fun waitForForeground(timeoutMs: Long): Boolean {
        val deadline = nowMillis() + timeoutMs
        while (nowMillis() < deadline) {
            val response = runCatching {
                withTimeoutOrNull(FOREGROUND_TIMEOUT_MS) { agent.send(Command.Foreground) }
            }
                .getOrNull()
            if (response is Response.ForegroundResult && response.appInForeground) {
                return true
            }
            delay(POLL_MS)
        }
        return false
    }

    private suspend fun pick(): CommandClient {
        val foreground = runCatching {
            withTimeoutOrNull(FOREGROUND_TIMEOUT_MS) { agent.send(Command.Foreground) }
        }
            .getOrNull()
        if (foreground is Response.ForegroundResult && foreground.appInForeground) {
            return agent
        }
        ensureSystemRunner()
        return system
    }

    private suspend fun ensureSystemRunner() {
        val reachable =
            try {
                system.isReachable()
            } catch (error: IncompatibleVersionException) {
                // A runner of another gekko release is still running: startSystemRunner replaces it
                log("Replacing the system runner: ${error.message}")
                false
            }
        if (reachable) {
            systemStarted = true
            return
        }
        systemStarted = false
        device.startSystemRunner(config, log)
        val deadline = nowMillis() + device.runnerStartTimeoutMs
        while (nowMillis() < deadline) {
            if (system.isReachable()) {
                systemStarted = true
                return
            }
            delay(POLL_MS)
        }
        throw InfrastructureException("System runner did not start")
    }

    fun close() {
        agent.close()
        system.close()
    }

    companion object {
        const val RUNNER_PACKAGE = "com.jakala.gekko.runner"
        const val RUNNER_CLASS = "com.jakala.gekko.runner.SystemRunnerTest"
        private const val POLL_MS = 100L
        private const val FOREGROUND_TIMEOUT_MS = 3_000L
        private const val AGENT_ANSWER_MARGIN_MS = 10_000L
        private const val FOREGROUND_GRACE_ATTEMPTS = 10
        private const val FOREGROUND_GRACE_POLL_MS = 100L
    }
}
