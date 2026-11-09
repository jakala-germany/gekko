package com.jakala.gekko.cli

import com.jakala.gekko.device.Gestures
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.MockRules
import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

internal val protocolJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** A command as it goes over the wire; mock rules are also handed to an app that is not running yet in this form. */
internal fun encodeCommand(command: Command): String {
    return protocolJson.encodeToString(Command.serializer(), command)
}

/** `+0100` or `-0530` (the output of `date +%z`) as minutes east of UTC; null when it is neither. */
internal fun parseUtcOffset(text: String): Int? {
    val match = Regex("([+-])(\\d{2})(\\d{2})").matchEntire(text.trim()) ?: return null
    val minutes = match.groupValues[2].toInt() * 60 + match.groupValues[3].toInt()
    return if (match.groupValues[1] == "-") -minutes else minutes
}

/** One server on a device (the in-app agent or the system runner). */
interface CommandClient {
    suspend fun send(command: Command): Response

    suspend fun isReachable(): Boolean

    fun close()
}

/**
 * Talks to one server on the device. [role] says which one ([Protocol.ROLE_AGENT] or [Protocol.ROLE_RUNNER]). Every
 * request is signed and every answer is checked with [auth], so an answer that does not come from the server of this
 * user (see [Protocol]) is an [UntrustedServerException] instead of a result.
 */
class GekkoClient(private val port: Int, private val role: String, auth: ClientAuth) : CommandClient {
    private companion object {
        const val REACHABLE_TIMEOUT_MS = 3_000L
        val HELLO_BODY = encodeCommand(Command.Hello).encodeToByteArray()
        const val ANDROID_HINT =
            " On Android the agent only answers in a debuggable build, because it gets its key through run-as."
    }

    private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 60_000 } }
    private val peer =
        SignedPeer(
            http,
            port,
            auth,
            Compatibility.describe(role),
            Compatibility.fix(role, olderPeer = true, peerVersion = null) +
                ANDROID_HINT.takeIf { role == Protocol.ROLE_AGENT }.orEmpty(),
        )

    override suspend fun send(command: Command): Response {
        val body = encodeCommand(command).encodeToByteArray()
        val reply = peer.send(body, command is Command.Hello, HELLO_BODY) { isStale(it) }
        return decode(reply)
    }

    private fun decode(reply: SignedPeer.Reply): Response {
        return protocolJson.decodeFromString(Response.serializer(), reply.body.decodeToString())
    }

    /** A signed refusal that names another session: the app was restarted since the last hello. */
    private fun isStale(reply: SignedPeer.Reply): Boolean {
        val answer = runCatching { decode(reply) }.getOrNull()
        return answer is Response.Error && answer.kind == ErrorKind.SESSION
    }

    /**
     * True when the server answers `hello`; throws [IncompatibleVersionException] when it speaks another protocol and
     * [UntrustedServerException] when something else answers in its place.
     */
    override suspend fun isReachable(): Boolean {
        val answer =
            try {
                withTimeoutOrNull(REACHABLE_TIMEOUT_MS) { send(Command.Hello) }
            } catch (error: UntrustedServerException) {
                throw error
            } catch (error: Exception) {
                null
            } ?: return false
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
    /** The file extension of the video: `mp4` on the mobile platforms, `webm` in a browser. */
    val extension: String
        get() = "mp4"

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

    /**
     * The client for the in-app agent; unless a device has another way, it speaks the gekko protocol to [agentPort].
     */
    fun agentClient(config: GekkoConfig): CommandClient {
        return GekkoClient(agentPort(config), Protocol.ROLE_AGENT, agentAuth(config))
    }

    /** The client for the system runner; unless a device has another way, it speaks the gekko protocol. */
    fun systemClient(config: GekkoConfig): CommandClient {
        return GekkoClient(systemPort(), Protocol.ROLE_RUNNER, systemAuth())
    }

    /** Host port (on 127.0.0.1) that reaches the system runner of this device. */
    fun systemPort(): Int

    /** What the CLI signs its requests to the in-app agent with and checks its answers against; see [Protocol]. */
    fun agentAuth(config: GekkoConfig): ClientAuth

    /** The same for the system runner. */
    fun systemAuth(): ClientAuth

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

    /** What the app printed to its console since the last call (web); null when this device has no such log. */
    fun consoleLog(): String? {
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

    /** The clock of the device for `${now(...)}`; unless a device has a clock of its own, the one of this computer. */
    fun deviceTime(): DeviceTime {
        val offset = runCatching { parseUtcOffset(runProcess(listOf("date", "+%z")).text) }.getOrNull() ?: 0
        return DeviceTime(nowMillis(), offset)
    }

    /**
     * Hands the network mocks to the app before it starts, so that requests made while it starts are answered too:
     * [mocks] is the JSON of a [Command.SetMocks]; null takes back what was handed over and not picked up.
     */
    fun setStartupMocks(config: GekkoConfig, mocks: String?) {
        error("network mocks are not supported on this device")
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
     * because clearing also wipes per-app state such as the app language. With [keepState] (a test of a group after the
     * first) the data stays. [beforeLaunch] runs when the device is ready and the app is not started yet (the place to
     * hand it its network mocks). Returns the function that restores the settings.
     */
    fun freshStart(
        config: GekkoConfig,
        settings: DeviceSettings = DeviceSettings(),
        keepState: Boolean = false,
        beforeLaunch: () -> Unit = {},
    ): () -> Unit {
        stopOtherApps(config)
        if (!keepState) clearData(config)
        val restore = applySettings(config, settings)
        beforeLaunch()
        launch(config)
        return restore
    }

    /**
     * The end of a test: the app is quit. [keepSession] says that another test of the same group follows, so a device
     * that holds more than the app data (a browser context with its login) keeps it.
     */
    fun endTest(config: GekkoConfig, keepSession: Boolean) {
        terminate(config)
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

    override fun agentAuth(config: GekkoConfig): ClientAuth {
        return ClientAuth.ofAndroid(agentSocketName(androidAppId(config)))
    }

    override fun systemAuth(): ClientAuth {
        return ClientAuth.ofAndroid(SYSTEM_RUNNER_SOCKET)
    }

    override fun agentClient(config: GekkoConfig): CommandClient {
        // An app that gekko did not start (by hand, or by an earlier run that cleared its data) needs the key as well
        runCatching { writeAgentKey(androidAppId(config)) }
        return super.agentClient(config)
    }

    /**
     * Leaves the key of the agent in the private directory of the app, where the agent reads it with every request. A
     * debuggable build only lets `run-as` in, and that is the point: no other app on the device can read the key, so
     * none can pass for the agent. Returns what `run-as` said when it failed, null when the key is in place. The file
     * is renamed into place, so the agent, which reads it with every request, never sees it half written.
     */
    private fun writeAgentKey(appId: String): String? {
        val key = ClientAuth.androidKey(UserToken.value, agentSocketName(appId))
        val file = Protocol.AGENT_KEY_FILE
        val result = shell("run-as", appId, "sh", "-c", "umask 077; printf %s $key > $file.tmp && mv $file.tmp $file")
        return if (result.exitCode == 0) null else result.text.trim()
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

    /** The clock of the device, not the one of this computer: an emulator's clock is often off. */
    override fun deviceTime(): DeviceTime {
        val text = shell("date", "+%s%z").text.trim()
        val seconds = text.dropLast(5).toLongOrNull()
        val offset = parseUtcOffset(text.takeLast(5))
        if (seconds == null || offset == null) return super.deviceTime()
        return DeviceTime(seconds * 1000, offset)
    }

    /**
     * Writes the mocks into the private directory of the app, where the agent reads them while the app starts and
     * deletes them. The app cannot read what `adb push` leaves in the shared directory; `run-as` (a debuggable build
     * only, which is what the agent is in) copies it in.
     */
    override fun setStartupMocks(config: GekkoConfig, mocks: String?) {
        val appId = androidAppId(config)
        if (mocks == null) {
            shell("run-as", appId, "rm", "-f", MockRules.STARTUP_FILE)
            return
        }
        // The mocks can hold secrets (`${env.*}`): they only live in directories nobody else can enter or guess
        val localDirectory = createPrivateDirectory("gekko-mocks")
        val local = "$localDirectory/mocks.json"
        // run-as has to read the pushed file as the app, so the file is readable for everyone, but the directory above
        // it can only be entered (not listed) and has a name that no other app on the device can know
        val remoteDirectory = "/data/local/tmp/gekko-mocks-${randomHex(8)}"
        val remote = "$remoteDirectory/mocks.json"
        try {
            writeTextFile(local, mocks)
            restrictToOwner(local)
            val made = shell("mkdir", "-m", "711", remoteDirectory)
            check(made.exitCode == 0) { "Preparing the network mocks on the device failed: ${made.text}" }
            val pushed = adb("push", local, remote)
            check(pushed.exitCode == 0) { "Copying the network mocks to the device failed: ${pushed.text}" }
            shell("chmod", "644", remote)
            val copied = shell("run-as", appId, "cp", remote, MockRules.STARTUP_FILE)
            check(copied.exitCode == 0) {
                "Network mocks need a debuggable build of $appId (run-as failed: ${copied.text})"
            }
        } finally {
            shell("rm", "-rf", remoteDirectory)
            runProcess(listOf("rm", "-rf", localDirectory))
        }
    }

    override fun launch(config: GekkoConfig, arguments: Map<String, String>) {
        val appId = androidAppId(config)
        if (!isInstalled(appId)) {
            throw InfrastructureException(
                "$appId is not installed on ${serial ?: "the device"}; install it (gekko install, --app)"
            )
        }
        writeAgentKey(appId)?.let { failure ->
            throw ConfigurationException(
                "The gekko agent needs a debuggable build of $appId: gekko hands it its key with run-as, which refuses " +
                    "any other build ($failure). Build a debug variant, and list only debug variants in gekko { variants }."
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
        // The clear took the key with it; an app that something else starts (a link) would not find it
        runCatching { writeAgentKey(appId) }
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
            "nohup am instrument -w -e class ${Drivers.RUNNER_CLASS} " +
                "-e ${Protocol.RUNNER_KEY_ARGUMENT} ${ClientAuth.androidKey(UserToken.value, SYSTEM_RUNNER_SOCKET)} " +
                "${Drivers.RUNNER_PACKAGE}/androidx.test.runner.AndroidJUnitRunner >/dev/null 2>&1 &"
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
    private val agent: CommandClient = device.agentClient(config),
    private val system: CommandClient = device.systemClient(config),
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

    /**
     * Asks the in-app agent and nobody else, whether or not the app is in front. Null when nothing listens (the app is
     * not running).
     */
    suspend fun sendToAgent(command: Command): Response? {
        debug("in-app agent", command)
        return askAgent(command)
    }

    /**
     * Null when nothing listens (app crashed or not started): the system runner then shows what is on screen. Something
     * that listens but does not prove that it is the agent is not "nothing": the run stops instead of asking the system
     * runner as if the agent were not there.
     */
    private suspend fun askAgent(command: Command): Response? {
        val budgetMs = command.budgetMs() + AGENT_ANSWER_MARGIN_MS
        return try {
            withTimeoutOrNull(budgetMs) { agent.send(command) }
                ?: Response.Error("The app did not answer within ${budgetMs}ms; is it hung?")
        } catch (error: CancellationException) {
            throw error
        } catch (error: UntrustedServerException) {
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
            is Command.LongPressAt -> holdMs
            is Command.Swipe -> durationMs
            is Command.Drag -> Gestures.DRAG_MOVE_MS + Gestures.DRAG_REST_MS
            is Command.Wheel,
            is Command.TapAt,
            is Command.SetOrientation,
            is Command.SetLocation,
            Command.ClearLocation,
            is Command.SetMocks,
            Command.ClearMocks,
            Command.MockHits,
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
            val response = askForeground()
            if (response is Response.ForegroundResult && response.appInForeground) {
                return true
            }
            delay(POLL_MS)
        }
        return false
    }

    /**
     * The answer of the agent to "is the app in front", null while it does not answer. A server that answers without
     * proving that it is the agent is no reason to fall back to the system runner: the run stops.
     */
    private suspend fun askForeground(): Response? {
        return try {
            withTimeoutOrNull(FOREGROUND_TIMEOUT_MS) { agent.send(Command.Foreground) }
        } catch (error: UntrustedServerException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    private suspend fun pick(): CommandClient {
        val foreground = askForeground()
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
                // A runner of another gekko release is still running (or one that cannot sign): startSystemRunner
                // replaces it; whatever else holds the socket is reported when the new one does not come up
                log("The system runner does not answer as expected, starting it again: ${error.message}")
                false
            }
        if (reachable) {
            systemStarted = true
            return
        }
        systemStarted = false
        device.startSystemRunner(config, log)
        val deadline = nowMillis() + device.runnerStartTimeoutMs
        // The runner that was just replaced can still hold the socket for a moment; whatever keeps it is reported
        var untrusted: IncompatibleVersionException? = null
        while (nowMillis() < deadline) {
            try {
                if (system.isReachable()) {
                    systemStarted = true
                    return
                }
            } catch (error: IncompatibleVersionException) {
                untrusted = error
            }
            delay(POLL_MS)
        }
        throw untrusted ?: InfrastructureException("System runner did not start")
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
