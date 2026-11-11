package com.jakala.gekko.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.GekkoVersion
import com.jakala.gekko.protocol.Keys
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.ScrollDirection
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import com.jakala.gekko.protocol.findAll
import com.jakala.gekko.protocol.isOnScreen
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val treeJson = Json { prettyPrint = true }

fun runCli(args: List<String>) {
    val command =
        GekkoCommand()
            .subcommands(
                DevicesCommand(),
                TreeCommand(),
                TapCommand(),
                TypeCommand(),
                TypeFocusedCommand(),
                PressKeyCommand(),
                ScrollCommand(),
                AssertCommand(),
                ScreenshotCommand(),
                InstallCommand(),
                UninstallCommand(),
                LaunchCommand(),
                RunCommand(),
                CheckCommand(),
                LlmCheckCommand(),
            )
    try {
        command.main(args)
    } catch (error: Throwable) {
        command.say(error.message ?: error.toString(), err = true)
        exitProcess(ExitCode.of(error))
    }
}

private class GekkoCommand : CliktCommand(name = "gekko") {
    init {
        versionOption(GekkoVersion.NAME, names = setOf("--version"), message = { "gekko $it" })
    }

    override fun help(context: Context): String {
        return "Fast cross-platform UI testing: drives the in-process agent of a debug build."
    }

    override fun run() {}
}

private abstract class DeviceCommand(name: String) : CliktCommand(name = name) {
    val serial by
        option(
            "--serial",
            "-s",
            help =
                "adb serial, simulator UDID or web browser (chromium, firefox, webkit; -2, -3 for more) of the device",
        )
    val root by option("--project", help = "Directory containing gekko.yaml").default(".")
    private val requestedPlatform by
        option(
            "--platform",
            help = "android, ios (simulator) or web; inferred from -s when that is a simulator UDID or a browser name",
        )

    /** `--platform`, else web when `-s` names a browser, ios when it names a booted simulator, else android. */
    protected val platformName: String
        get() {
            requestedPlatform?.let {
                return it
            }
            val wanted = serial
            return when {
                wanted != null && WebSessions.isWebId(wanted) -> "web"
                wanted != null && wanted in SimctlDevice.bootedUdids() -> "ios"
                else -> "android"
            }
        }

    protected val platform: Platform
        get() {
            return when (platformName) {
                "android" -> Platform.Android
                "ios" -> Platform.Ios
                "web" -> Platform.Web
                else -> fail("--platform must be android, ios or web")
            }
        }

    protected fun device(): Device {
        return when (platform) {
            Platform.Android -> AdbDevice(androidSerial()).also { it.requireSupportedApi() }
            Platform.Ios -> SimctlDevice(serial)
            Platform.Web -> webDevice()
        }
    }

    private fun webDevice(): Device {
        val config = webConfig()
        val name = serial ?: config.web.browser
        if (!WebSessions.isWebId(name)) fail("'$name' is not a browser; use chromium, firefox or webkit with -s")
        return WebDevice(name, config) { say(it, err = true) }
    }

    /** True for a command that can do its work for the web without gekko.yaml, like installing the browsers. */
    protected open val webWithoutProject: Boolean
        get() = false

    protected fun webConfig(): GekkoConfig {
        return if (webWithoutProject && !fileExists("$root/gekko.yaml")) GekkoConfig() else Project.load(root).config
    }

    private fun androidSerial(): String {
        serial?.let {
            return it
        }
        val attached = AdbDevice.listSerials()
        return when (attached.size) {
            0 -> fail("No Android device connected", ExitCode.INFRASTRUCTURE)
            1 -> attached[0]
            else -> fail("Several devices connected (${attached.joinToString()}); choose one with -s")
        }
    }

    protected fun <T> withDrivers(block: suspend (Drivers, Device, Project) -> T): T {
        val device = device()
        val project = Project.load(root)
        val drivers = Drivers(device, project.config) { say(it, err = true) }
        return runBlocking {
            try {
                block(drivers, device, project)
            } catch (error: IllegalStateException) {
                failWith(error)
            } finally {
                drivers.close()
            }
        }
    }

    /** Removes the app, the system runner and the port forwards; false when there was nothing to remove. */
    protected fun uninstallEverything(device: Device, project: Project): Boolean {
        val removed =
            try {
                device.uninstall(project.config)
            } catch (error: IllegalStateException) {
                failWith(error)
            }
        removed.forEach { say("removed $it") }
        return removed.isNotEmpty()
    }

    protected fun fail(message: String, code: Int = ExitCode.USAGE): Nothing {
        say(message, err = true)
        exitProcess(code)
    }

    protected fun failWith(error: Throwable): Nothing {
        fail(error.message ?: error.toString(), ExitCode.of(error))
    }

    protected fun report(response: Response) {
        if (response is Response.Error) {
            fail(response.message, ExitCode.FAILED)
        }
        say("ok")
    }
}

private abstract class SelectorCommand(name: String) : DeviceCommand(name) {
    val id by option("--id", help = "Element id (test tag / resource id)")
    val text by option("--text", help = "Visible text")
    val textContains by option("--text-contains", help = "Part of the visible text")
    val textMatches by option("--text-matches", help = "Regular expression the whole text must match")
    val action by option("--action", help = "Required action: click, setText, scroll")
    val within by option("--within", help = "Only below the element with this id")
    val system by
        option("--system", help = "Use the system runner (permission prompts, browser) instead of the in-app agent")
            .flag()
    val index by option("--index").int().default(0)
    val timeout by option("--timeout", help = "Milliseconds").long().default(Command.DEFAULT_TIMEOUT_MS)

    protected fun selector(): Selector {
        if (id == null && text == null && textContains == null && textMatches == null && action == null) {
            fail("Provide --id, --text, --text-contains, --text-matches or --action")
        }
        return Selector(
            id = id,
            text = text,
            textContains = textContains,
            textMatches = textMatches,
            action = action,
            within = within,
            index = index,
        )
    }
}

private class DevicesCommand : CliktCommand(name = "devices") {
    override fun help(context: Context): String {
        return "List connected Android devices, booted iOS simulators and running web sessions"
    }

    override fun run() {
        AdbDevice.listSerials().forEach { say("android $it") }
        SimctlDevice.bootedUdids().forEach { say("ios $it") }
        WebSessions.running().forEach { say("web $it") }
    }
}

private class TreeCommand : DeviceCommand("tree") {
    val json by option("--json", help = "Print the raw tree as JSON").flag()

    override fun help(context: Context): String {

        return "Print the UI tree of the active driver"
    }

    override fun run() {
        withDrivers { drivers, _, project ->
            val response = drivers.send(Command.Tree)
            if (response !is Response.TreeResult) fail("Unexpected response: $response", ExitCode.INFRASTRUCTURE)
            if (json) {
                say(treeJson.encodeToString(ListSerializer(UiNode.serializer()), response.windows))
                return@withDrivers
            }
            // The elements of the panes that match a line are named, and so is the pane that is on screen
            val elements = project.panes.selectors(platform)
            val label = { node: UiNode ->
                elements.filter { it.second.matches(node) }.joinToString(", ") { it.first.qualified }.ifEmpty { null }
            }
            say(TreePrinter.print(response.windows, label))
            val current = project.panes.current(response.windows, platform)
            if (current.isNotEmpty()) say("current pane: ${current.joinToString(", ")}")
        }
    }
}

private class TapCommand : SelectorCommand("tap") {
    override fun help(context: Context): String {
        return "Tap an element"
    }

    override fun run() {
        withDrivers { drivers, _, project ->
            val command = Command.Tap(selector(), timeout, project.config.settleMs)
            var response = drivers.send(command, system)
            if (response is Response.Error && response.message.contains("Tap was not handled")) {
                // The in-app agent only taps via accessibility actions; the system runner synthesizes a real touch
                response = drivers.send(command, forceSystem = true)
            }
            report(response)
        }
    }
}

private class TypeCommand : SelectorCommand("type") {
    val value by argument(name = "TEXT")

    override fun help(context: Context): String {

        return "Type text into an element"
    }

    override fun run() {
        withDrivers { drivers, _, project ->
            report(drivers.send(Command.Type(selector(), value, timeout, project.config.settleMs), system))
        }
    }
}

private class TypeFocusedCommand : DeviceCommand("type-focused") {
    val value by argument(name = "TEXT")
    val system by
        option("--system", help = "Use the system runner (permission prompts, browser) instead of the in-app agent")
            .flag()
    val timeout by
        option("--timeout", help = "Milliseconds to wait for a field with the focus")
            .long()
            .default(Command.DEFAULT_TIMEOUT_MS)

    override fun help(context: Context): String {
        return "Type text at the cursor of the field that has the focus, e.g. after a tap"
    }

    override fun run() {
        withDrivers { drivers, _, _ -> report(drivers.send(Command.TypeFocused(value, timeout), system)) }
    }
}

private class PressKeyCommand : DeviceCommand("press-key") {
    val key by argument(name = "KEY", help = "${Keys.all.joinToString()}, or an Android key code number")
    val times by option("--times", help = "How often to press it").int().default(1)
    val system by
        option("--system", help = "Use the system runner (permission prompts, browser) instead of the in-app agent")
            .flag()
    val timeout by
        option("--timeout", help = "Milliseconds to wait for a field with the focus")
            .long()
            .default(Command.DEFAULT_TIMEOUT_MS)

    override fun help(context: Context): String {
        return "Press a key: enter, backspace and tab go to the field that has the focus"
    }

    override fun run() {
        withDrivers { drivers, _, _ ->
            report(drivers.sendWithRunnerFallback(Command.PressKey(key, times, timeout), system))
        }
    }
}

private class ScrollCommand : SelectorCommand("scroll") {
    val direction by
        option("--direction", help = "Where the wanted content is: ${ScrollDirection.all.joinToString()}")
            .choice(*ScrollDirection.all.toTypedArray())
            .default(ScrollDirection.DOWN)
    val distance by option("--distance", help = "dp (points on iOS)").int().default(Command.DEFAULT_SCROLL_DP)

    override fun help(context: Context): String {
        return "Scroll the container of an element"
    }

    override fun run() {
        withDrivers { drivers, _, project ->
            report(drivers.send(Command.Scroll(selector(), direction, distance, timeout, project.config.settleMs)))
        }
    }
}

private class AssertCommand : SelectorCommand("assert") {
    val gone by option("--gone", help = "Assert the element is NOT visible").flag()

    override fun help(context: Context): String {

        return "Assert that an element is visible"
    }

    override fun run() {
        withDrivers { drivers, _, _ ->
            val selector = selector()
            val deadline = nowMillis() + timeout
            while (true) {
                val tree = drivers.send(Command.Tree) as? Response.TreeResult
                val found =
                    tree?.windows?.any { window -> window.findAll(selector).any { it.isOnScreen(window.bounds) } } ==
                        true
                if (found != gone) {
                    say("ok")
                    return@withDrivers
                }
                if (nowMillis() > deadline) {
                    fail(if (gone) "Still visible: $selector" else "Not visible: $selector", ExitCode.FAILED)
                }
                delay(POLL_MS)
            }
        }
    }

    private companion object {
        const val POLL_MS = 50L
    }
}

private class ScreenshotCommand : DeviceCommand("screenshot") {
    val output by argument(name = "FILE")

    override fun help(context: Context): String {

        return "Save a screenshot as PNG"
    }

    override fun run() {
        val bytes = device().screenshot()
        writeFile(output, bytes)
        say("saved $output")
    }
}

private class InstallCommand : DeviceCommand("install") {
    override val webWithoutProject: Boolean
        get() = true

    val path by
        argument(
                name = "APP",
                help = "An .app, .apk or .zip with an .app (default: ios.app / android.apk of gekko.yaml)",
            )
            .optional()

    val withDeps by
        option(
                "--with-deps",
                help = "Web, Linux: also install the system libraries the browser needs (uses sudo)",
            )
            .flag()

    override fun help(context: Context): String {

        return "Install the app under test, keeping its data; for web: Playwright and the browser"
    }

    override fun run() {
        val device = device()
        val config = if (platform == Platform.Web) webConfig() else Project.load(root).config
        (device as? WebDevice)?.withDeps = withDeps
        try {
            device.install(config, path)
        } catch (error: IllegalStateException) {
            failWith(error)
        }
        say("installed")
    }
}

private class UninstallCommand : DeviceCommand("uninstall") {
    override fun help(context: Context): String {
        return "Remove the app under test, the system runner and the port forwards"
    }

    override fun run() {
        val device = device()
        val project = Project.load(root)
        if (!uninstallEverything(device, project)) say("nothing to remove")
    }
}

private class LaunchCommand : DeviceCommand("launch") {
    val fresh by option("--fresh", help = "Clear app data first").flag()

    override fun help(context: Context): String {

        return "Launch the app from gekko.yaml"
    }

    override fun run() {
        withDrivers { drivers, device, project ->
            if (fresh) device.clearData(project.config)
            device.launch(project.config)
            if (!drivers.waitForAgent(AGENT_TIMEOUT_MS)) fail("Agent did not respond", ExitCode.INFRASTRUCTURE)
            say("ok")
        }
    }

    private companion object {
        const val AGENT_TIMEOUT_MS = 20_000L
    }
}

private class RunCommand : DeviceCommand("run") {
    val paths by argument(name = "PATH", help = "Test files or directories (default: tests dir)").multiple()
    val tags by option("--tags", help = "Comma separated tags; a test runs if it has any of them")
    val excludeTags by
        option("--exclude-tags", help = "Comma separated tags; a test with any of them does not run (after --tags)")
    val record by
        option(
                "--record",
                help = "Keep a video of the screen: failures (failed tests only), all, or none",
            )
            .choice(RecordMode.entries.associateBy { it.key })
            .default(RecordMode.None)
    val shard by option("--shard", help = "i/n, e.g. 1/3")
    val junit by option("--junit", help = "JUnit XML output file")
    val junitHealed by
        option(
            "--junit-healed",
            help = "JUnit XML file with one failed testcase per healed step (empty without --heal or heals)",
        )
    val output by option("--output", help = "Directory for failure artifacts").default("gekko-results")
    val heal by
        option(
                "--heal",
                metavar = "claude|opencode|api",
                help =
                    "When an id is not found, ask a model which element fits the step's `say`; healed steps only warn. " +
                        "claude and opencode use the local CLI, api uses the OpenAI-compatible endpoint through curl (GEKKO_LLM_BASE_URL, GEKKO_LLM_MODEL, GEKKO_LLM_API_KEY, GEKKO_LLM_CA_CERT for a private CA)",
            )
            .choice(HealSource.entries.associateBy { it.key })
    val uninstall by
        option(
                "--uninstall",
                help = "Remove the app, the system runner and the port forwards when the run is done",
            )
            .flag()
    val install by
        option("--install", help = "Install the app of gekko.yaml (ios.app / android.apk) before the tests").flag()
    val apps by
        option(
                "--app",
                help =
                    "Install this build before the tests (implies --install); repeat it for several platforms: " +
                        ".apk goes to Android devices, .app or .zip to iOS simulators",
            )
            .multiple()
    val devices by
        option(
            "--devices",
            help =
                "Comma separated adb serials / simulator UDIDs, or `all` for every attached device and booted simulator; " +
                    "the first free device of each platform takes the next test",
        )
    val everyDevice by
        option(
                "--every-device",
                help =
                    "With --devices: every device runs every test; the run passes only when every test passes on every device",
            )
            .flag()

    override fun help(context: Context): String {

        return "Run YAML tests on a device"
    }

    override fun run() {
        if (everyDevice && devices == null) fail("--every-device needs --devices")
        val wanted = tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        excludedTags = excludeTags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        devices?.let {
            runOnDevices(it.split(',').map(String::trim).filter(String::isNotEmpty), wanted)
            return
        }
        withDrivers { drivers, device, project ->
            installIfAsked(device, project, platform, strict = true)
            val selected = selectTests(project, wanted) ?: return@withDrivers
            val units = RunUnits.forPlatform(selected, platform)
            if (units.isEmpty()) {
                fail("No tests for ${platform.key}: every selected group is limited to other platforms", ExitCode.USAGE)
            }
            (device as? WebDevice)?.recordVideo = record != RecordMode.None
            val healer = healer()
            val runner =
                TestRunner(
                    project,
                    platform,
                    device,
                    drivers,
                    output,
                    { say(Masker.mask(it)) },
                    healer,
                    suggestIds = healer == null,
                    record = record,
                )
            val results = mutableListOf<TestResult>()
            try {
                units.forEach { unit ->
                    runner.runUnit(unit, started = { say("RUN  $it") }) { result ->
                        results += result
                        // The report is complete after every test, so a run that is killed still leaves one
                        junit?.let { writeReportFile(it, Reporter.junit("gekko", results)) }
                        say(resultLine(result, ""))
                    }
                }
            } finally {
                // A browser this run started is not left behind, not even when the run aborts; one that was running
                // before stays
                (device as? WebDevice)?.takeIf { it.startedHere && !uninstall }?.stopSession()
            }
            reportHeals(results)
            junit?.let { writeReportFile(it, Reporter.junit("gekko", results)) }
            junitHealed?.let { writeReportFile(it, Reporter.junitHealed("gekko-healed", results)) }
            say(summary(results))
            if (uninstall) uninstallEverything(device, project)
            exitWith(results)
        }
    }

    private fun resultLine(result: TestResult, prefix: String): String {
        val healedNote = if (result.healed.isEmpty()) "" else ", ${result.healed.size} step(s) healed"
        return when {
            result.skipped -> "${prefix}SKIP ${result.name}: ${result.failure}"
            result.failure == null -> "${prefix}PASS ${result.name} (${result.durationMs}ms$healedNote)"
            else -> "${prefix}FAIL ${result.name}: ${result.failure}"
        }
    }

    private fun summary(results: List<TestResult>, suffix: String = ""): String {
        val skipped = results.count { it.skipped }
        val note = if (skipped > 0) ", $skipped skipped" else ""
        return "${results.count { it.failure == null }}/${results.size} passed$note$suffix"
    }

    /** The build of [apps] that fits [platform]: `.apk` for Android, `.app` or `.zip` for an iOS simulator. */
    private fun appFor(platform: Platform): String? {
        val wanted =
            when (platform) {
                Platform.Android -> listOf(".apk")
                Platform.Ios -> listOf(".app", ".zip", ".app/")
                Platform.Web -> emptyList()
            }
        return apps.firstOrNull { path -> wanted.any { path.endsWith(it) } }
    }

    private fun installIfAsked(device: Device, project: Project, platform: Platform, strict: Boolean) {
        if (!install && apps.isEmpty()) return
        val mine = appFor(platform)
        if (apps.isNotEmpty() && mine == null) {
            // With several platforms a build for only one of them is fine; the others keep what is installed
            if (strict)
                fail("--app: no build for ${platform.key} (.apk for Android, .app or .zip for iOS; none for web)")
            return
        }
        try {
            device.install(project.config, mine?.let { absolutePath(it, currentDirectory()) })
        } catch (error: IllegalStateException) {
            failWith(error)
        }
    }

    /** Null without `--heal`; with it the named source must be usable on this machine. */
    private fun healer(): Healer? {
        val source = heal ?: return null
        val client =
            try {
                LlmClients.create(source)
            } catch (error: IllegalStateException) {
                fail("--heal ${source.key}: ${error.message}")
            }
        return Healer(client)
    }

    /**
     * A platform without an app id in gekko.yaml cannot run tests: with `all` its devices are left out (and named), a
     * device chosen by id on such a platform is an error. The same id twice is an error too.
     */
    private fun configuredDevices(devices: List<DeviceRef>, project: Project, all: Boolean): List<DeviceRef> {
        val duplicates = devices.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) fail("--devices names ${duplicates.joinToString()} more than once")
        val configured = devices.filter { ref ->
            val set =
                when (ref.platform) {
                    Platform.Android -> project.config.app.android
                    Platform.Ios -> project.config.app.ios
                    Platform.Web -> project.config.app.web
                }
            if (set == null) {
                val key = "app.${ref.platform.key}"
                if (!all) fail("$key is not set in gekko.yaml but ${ref.id} is a ${ref.platform.key} device")
                say("Skipping ${ref.platform.key} device ${ref.id}: $key is not set in gekko.yaml")
            }
            set != null
        }
        if (configured.isEmpty()) fail("No device left to run on")
        return configured
    }

    private fun reportHeals(results: List<TestResult>) {
        val records = results.flatMap { it.healed }.distinct()
        if (records.isEmpty()) return
        say(HealReport.warnings(records))
        writeReportFile("$output/gekko-heal-report.md", HealReport.markdown(records))
        say("Heal report: $output/gekko-heal-report.md")
    }

    private fun runOnDevices(ids: List<String>, wanted: List<String>) {
        if (serial != null) fail("Use either --devices or --serial")
        val project = Project.load(root)
        val units = selectTests(project, wanted) ?: return
        val resolved =
            try {
                DeviceResolver.resolve(ids, AdbDevice.listSerials(), SimctlDevice.bootedUdids(), WebSessions.running())
            } catch (error: IllegalStateException) {
                failWith(error)
            }
        val refs = configuredDevices(resolved, project, all = ids == listOf(DeviceResolver.ALL))
        if (refs.none { RunUnits.forPlatform(units, it.platform).isNotEmpty() }) {
            fail("No tests for these devices: every selected group is limited to other platforms", ExitCode.USAGE)
        }
        val healer = healer()
        val lock = Mutex()
        val results = mutableListOf<TestResult>()
        runBlocking(Dispatchers.Default) {
            coroutineScope {
                refs
                    .groupBy { it.group }
                    .forEach { (_, group) ->
                        val platform = group.first().platform
                        // A group limited to other platforms is not part of this platform's run
                        val mine = RunUnits.forPlatform(units, platform)
                        if (mine.isEmpty()) {
                            say("No selected tests for ${platform.key}; ${group.joinToString { it.id }} is left out")
                            return@forEach
                        }
                        launch {
                            val sessions = group.map { ref ->
                                val device = ref.create(project.config)
                                (device as? WebDevice)?.recordVideo = record != RecordMode.None
                                installIfAsked(device, project, platform, strict = false)
                                val prefix = "[${ref.id}]"
                                val drivers = Drivers(device, project.config) { say("$prefix $it") }
                                Session(
                                    ref,
                                    TestRunner(
                                        project,
                                        platform,
                                        device,
                                        drivers,
                                        "$output/${ref.id}",
                                        { say(Masker.mask("$prefix $it")) },
                                        healer,
                                        suggestIds = healer == null,
                                        record = record,
                                    ),
                                    drivers,
                                    device,
                                )
                            }
                            try {
                                // The tests of a group stay together: one unit goes to one device
                                val work: suspend (Int, RunUnit) -> Unit = { worker, unit ->
                                    val ref = sessions[worker].ref
                                    sessions[worker].runner.runUnit(unit, started = { say("[${ref.id}] RUN  $it") }) {
                                        val result = it.copy(device = ref.id)
                                        say(resultLine(result, "[${ref.id}] "))
                                        lock.withLock { results += result }
                                    }
                                }
                                if (everyDevice) {
                                    replicate(mine, sessions.size, work)
                                } else {
                                    distribute(mine, sessions.size, work)
                                }
                            } finally {
                                sessions.forEach {
                                    // A browser this run started is not left behind; it is stopped before its client
                                    // closes
                                    (it.device as? WebDevice)
                                        ?.takeIf { web -> web.startedHere && !uninstall }
                                        ?.stopSession()
                                    it.drivers.close()
                                }
                            }
                        }
                    }
            }
        }
        reportHeals(results)
        junit?.let { writeReportFile(it, Reporter.junit("gekko", results)) }
        junitHealed?.let { writeReportFile(it, Reporter.junitHealed("gekko-healed", results)) }
        if (everyDevice) {
            refs.forEach { ref -> say("[${ref.id}] " + summary(results.filter { it.device == ref.id })) }
        }
        say(summary(results, " on ${refs.size} device(s)"))
        if (uninstall) refs.forEach { uninstallEverything(it.create(project.config), project) }
        exitWith(results)
    }

    /** 0 when every test passed, 3 when the device or agent failed in any test, else 1. */
    private fun exitWith(results: List<TestResult>) {
        val code =
            when {
                results.any { it.infrastructure } -> ExitCode.INFRASTRUCTURE
                results.any { it.failed } -> ExitCode.FAILED
                else -> ExitCode.OK
            }
        if (code != ExitCode.OK) exitProcess(code)
    }

    /**
     * The tests to run: validated like `gekko check` (a broken file or flow is an error before anything starts), then
     * filtered by tag and only then split into the shard. Null when this shard has no test: nothing to do, not an
     * error.
     */
    private fun selectTests(project: Project, wanted: List<String>): List<RunUnit>? {
        val problems = Checker.check(project, paths)
        if (problems.isNotEmpty()) {
            problems.forEach { say(it, err = true) }
            fail("${problems.size} problem(s) in the tests; fix them or run gekko check", ExitCode.USAGE)
        }
        val selected =
            project
                .testFiles(paths)
                .map { it to project.loadTest(it) }
                .filter { (_, test) -> wanted.isEmpty() || test.tags.any { it in wanted } }
                .filter { (_, test) -> test.tags.none { it in excludedTags } }
        if (selected.isEmpty()) fail("No tests found", ExitCode.USAGE)
        // A group is one unit: its tests are never split over shards or devices
        val units = RunUnits.of(project, selected)
        val mine = shardOf(units)
        if (mine.isEmpty()) {
            say("Shard $shard has no tests (${selected.size} tests in total); nothing to run")
            junit?.let { writeReportFile(it, Reporter.junit("gekko", emptyList())) }
            junitHealed?.let { writeReportFile(it, Reporter.junitHealed("gekko-healed", emptyList())) }
            return null
        }
        return mine
    }

    private var excludedTags: List<String> = emptyList()

    private fun <T> shardOf(items: List<T>): List<T> {
        val spec = shard ?: return items
        val parts = spec.split('/')
        val index = parts.getOrNull(0)?.toIntOrNull()
        val count = parts.getOrNull(1)?.toIntOrNull()
        if (index == null || count == null || index !in 1..count) fail("--shard must look like 1/3")
        return items.filterIndexed { position, _ -> position % count == index - 1 }
    }
}

/** One device of a parallel run with the runner and the drivers that work on it. */
private class Session(val ref: DeviceRef, val runner: TestRunner, val drivers: Drivers, val device: Device)

/** Asks the model of `--heal` one short question, so `GEKKO_LLM_*` (or the local CLI) can be tried without a device. */
private class LlmCheckCommand : CliktCommand(name = "llm-check") {
    val source by
        option("--heal", metavar = "claude|opencode|api", help = "The model to try (default: api)")
            .choice(HealSource.entries.associateBy { it.key })
            .default(HealSource.Api)

    override fun help(context: Context): String {
        return "Ask the model of --heal one short question and print the answer: checks GEKKO_LLM_BASE_URL, GEKKO_LLM_MODEL, " +
            "GEKKO_LLM_API_KEY and GEKKO_LLM_CA_CERT (or the local CLI) without a device; exit 3 when the model does not answer"
    }

    override fun run() {
        val client =
            try {
                LlmClients.create(source)
            } catch (error: IllegalStateException) {
                say("--heal ${source.key}: ${error.message}", err = true)
                exitProcess(ExitCode.USAGE)
            }
        val answer = runBlocking { client.complete("Connectivity check. Answer with the single word: pong", "ping") }
        check(answer.isNotBlank()) { "the model answered with nothing" }
        say("${source.key}: ok, the model answered: ${answer.trim().take(200)}")
    }
}

private class CheckCommand : CliktCommand(name = "check") {
    val root by option("--project", help = "Directory containing gekko.yaml").default(".")
    val files by argument(name = "FILE", help = "Tests or folders to check (default: all tests)").multiple()

    override fun help(context: Context): String {
        return "Read all tests and the flows they use without a device: broken YAML, unknown keys, missing flows or parameters"
    }

    override fun run() {
        val project = Project.load(root)
        val report = Checker.report(project, files)
        if (report.problems.isEmpty()) {
            say("${project.testFiles(files).size} tests ok")
            report.warnings.forEach { say("warning: $it", err = true) }
            return
        }
        report.problems.forEach { say(it, err = true) }
        report.warnings.forEach { say("warning: $it", err = true) }
        exitProcess(ExitCode.USAGE)
    }
}
