package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Keys
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.ScrollDirection
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.SettleTracker
import com.jakala.gekko.protocol.UiNode
import com.jakala.gekko.protocol.findAll
import com.jakala.gekko.protocol.isOnScreen
import com.jakala.gekko.protocol.normalizedSpaces
import kotlinx.coroutines.delay

/** [inUntil] tells a failed `until` condition (the state after the action) from a failed action. */
class StepFailure(message: String, val inUntil: Boolean = false) : Exception(message)

/** `--record`: no videos, the video of failed tests only, or of every test. */
enum class RecordMode(val key: String) {
    None("none"),
    Failures("failures"),
    All("all"),
}

class StepOutcome(val description: String, val durationMs: Long)

data class TestResult(
    val name: String,
    val path: String,
    val durationMs: Long,
    val steps: List<StepOutcome>,
    val failure: String?,
    val failedStep: String?,
    val artifacts: List<String>,
    /** Id of the device the test ran on; set when several devices are used. */
    val device: String? = null,
    val healed: List<HealRecord> = emptyList(),
    /** The run failed because of the device, the agent or the runner, not because of the test. */
    val infrastructure: Boolean = false,
    /** The test did not run, because the setup of its group failed; [failure] says why. */
    val skipped: Boolean = false,
    /** Things to look at that did not fail the test, e.g. a network mock that no request used. */
    val warnings: List<String> = emptyList(),
) {
    /** True for a test that ran and failed; a skipped test is not a failure of its own. */
    val failed: Boolean
        get() = failure != null && !skipped
}

/** What a `use:` runs: a flow, or an action of a pane ([pane] is that pane, the scope of its element names). */
class Callable(val name: String, val steps: List<Step>, val pane: String?)

class Project(val root: String, val config: GekkoConfig) {
    private val access: EnvAccess by lazy { accessFor(root, config) }

    /** The screens of the app: elements, identity and actions, see [Panes]. */
    val panes: Panes by lazy {
        Panes("$root/${config.panesDir}", config.panesDir) { path, params ->
            Yamls.substitute(readTextFile(path), params, access, keepUnknown = true)
        }
    }

    /** The platforms a pane reference has to resolve on: web only for a project that tests a web app. */
    internal val panePlatforms: List<Platform>
        get() {
            return if (config.app.web != null) Platform.entries else listOf(Platform.Android, Platform.Ios)
        }

    init {
        // The values must be known to the masker before any test prints something
        access.values()
    }

    fun testFiles(filter: List<String>): List<String> {
        val dirs = if (filter.isEmpty()) listOf("$root/${config.testsDir}") else filter
        return dirs
            .flatMap { path ->
                if (path.endsWith(".yaml") || path.endsWith(".yml")) listOf(path) else listFilesRecursive(path)
            }
            .filter { it.endsWith(".yaml") || it.endsWith(".yml") }
            .filterNot { GROUP_FILES.any { name -> it == name || it.endsWith("/$name") } }
            .distinct()
            .sorted()
    }

    /**
     * The `group.yaml` that applies to a test: the one in the nearest folder above it, looking up to the project
     * folder. Null for a test that is in no group.
     */
    fun groupFileOf(testPath: String): String? {
        var directory = testPath.substringBeforeLast('/', "")
        val top = absolutePath(root, currentDirectory())
        while (directory.isNotEmpty()) {
            GROUP_FILES.map { "$directory/$it" }
                .firstOrNull { fileExists(it) }
                ?.let {
                    return it
                }
            if (absolutePath(directory, currentDirectory()) == top) return null
            val parent = directory.substringBeforeLast('/', "")
            if (parent == directory) return null
            directory = parent
        }
        return null
    }

    fun loadGroup(path: String): GroupFile {
        val group = Yamls.parse(GroupFile.serializer(), readTextFile(path))
        // The flows have to exist and (for the web) carry ids for it; a broken one is reported when the file is checked
        (group.setup + group.teardown).forEach { loadCallable(it, emptyMap()) }
        return group
    }

    fun loadTest(path: String): TestFile {
        val test = Yamls.parse(TestFile.serializer(), Yamls.substitute(readTextFile(path), emptyMap(), access))
        requireWebIds(test.steps)
        panes.validate(test.steps, null, panePlatforms)
        return test
    }

    private fun flowPath(name: String): String? {
        val base = "$root/${config.flowsDir}/$name"
        return listOf("$base.yaml", "$base.yml").firstOrNull { fileExists(it) }
    }

    fun loadFlow(name: String, params: Map<String, String>): FlowFile {
        val path =
            flowPath(name)
                ?: throw StepFailure("Flow '$name' not found in ${config.flowsDir}/${panes.unknownActionHint(name)}")
        val flow = Yamls.parse(FlowFile.serializer(), Yamls.substitute(readTextFile(path), params, access))
        requireWebIds(flow.steps)
        panes.validate(flow.steps, null, panePlatforms)
        return flow
    }

    /**
     * What `use: [name]` runs: a flow (`flows/<name>.yaml`) or an action of a pane (`<pane>.<action>`, or just
     * `<action>` inside an action of that pane, the [scope]). A name that could be both is an error.
     */
    fun loadCallable(name: String, params: Map<String, String>, scope: String? = null): Callable {
        val own = scope?.takeIf { panes.hasAction(it, name) }
        val qualified = panes.splitRef(name)?.takeIf { panes.hasAction(it.first, it.second) }
        val matches =
            listOfNotNull(
                flowPath(name)?.let { "the flow '$name'" },
                own?.let { "the action '$name' of the pane '$it'" },
                qualified?.let { "the action '${it.second}' of the pane '${it.first}'" },
            )
        require(matches.size <= 1) { "'$name' is ambiguous: ${matches.joinToString(" and ")}; rename one of them" }
        val pane = own ?: qualified?.first
        val action = if (own != null) name else qualified?.second
        if (pane == null || action == null) {
            return Callable(name, loadFlow(name, params).steps, null)
        }
        val steps = panes.action(pane, action, params)
        requireWebIds(steps)
        panes.validate(steps, pane, panePlatforms)
        return Callable("$pane.$action", steps, pane)
    }

    /** Puts the declared `${env.NAME}` variables into the text of a mock file; [escape] fits a value to the file. */
    fun fillEnv(text: String, escape: (String) -> String): String {
        return ENV_PLACEHOLDER.replace(text) { match -> escape(access.value(match.groupValues[1])) }
    }

    /**
     * The text of a file of the mocks directory. A file that is no UTF-8 text (an image, say) is refused: it would
     * reach the app as something else than what is in the file.
     */
    fun readMockFile(file: String): String {
        val text = readTextFile("$root/${config.mocksDir}/$file")
        require('�' !in text) { "is not a UTF-8 text file; mock files are text only" }
        return text
    }

    /**
     * The text of a mock file with its `${env.NAME}` variables and the runtime placeholders of [resolve] filled in. The
     * values from the environment go in last: a password that looks like a placeholder stays what it is.
     */
    fun renderMock(text: String, escape: (String) -> String, resolve: (String) -> String): String {
        val values = mutableListOf<String>()
        val marked =
            fillEnv(text) { value ->
                values += escape(value)
                "$ENV_MARK_START${values.size - 1}$ENV_MARK_END"
            }
        return ENV_MARK.replace(resolve(marked)) { match ->
            values.getOrNull(match.groupValues[1].toInt()) ?: match.value
        }
    }

    /** A project that tests a web app needs an id for the web on every step that runs there. */
    private fun requireWebIds(steps: List<Step>) {
        if (config.app.web == null) return
        for (step in steps) {
            step.requireIdsFor(Platform.Web)
            step.action.repeat?.let { requireWebIds(it.steps) }
        }
    }

    companion object {
        private val GROUP_FILES = listOf("group.yaml", "group.yml")
        private val ENV_PLACEHOLDER = Regex("""\$\{env\.([A-Za-z0-9_]+)}""")

        /** Stand in for an environment value while the runtime placeholders are filled in (private use characters). */
        private const val ENV_MARK_START = ''
        private const val ENV_MARK_END = ''
        private val ENV_MARK = Regex("$ENV_MARK_START(\\d+)$ENV_MARK_END")

        /** The `${env.NAME}` variables of a project: the names `gekko.yaml` declares, with the values it can find. */
        private fun accessFor(root: String, config: GekkoConfig): EnvAccess {
            val dotEnv =
                "$root/.env".takeIf { fileExists(it) }?.let { EnvAccess.parseDotEnv(readTextFile(it)) }.orEmpty()
            return EnvAccess(config.env.toSet(), dotEnv).also { Masker.register(it.values()) }
        }

        fun load(root: String): Project {
            val path = "$root/gekko.yaml"
            if (!fileExists(path)) {
                val hint = if (fileExists("$root/gekko.yml")) " (found gekko.yml: rename it to gekko.yaml)" else ""
                throw ConfigurationException(
                    "gekko.yaml not found in ${absolutePath(root, currentDirectory())}$hint; run gekko in your test repository or pass --project"
                )
            }
            val read = Yamls.parse(GekkoConfig.serializer(), readTextFile(path))
            return Project(
                root,
                read.withEnvInWebApp(accessFor(root, read)).withPathsRelativeTo(absolutePath(root, currentDirectory())),
            )
        }
    }
}

/** Keeps taps at least [intervalMs] apart; a script must not out-tap what the app lets a person do. */
class TapPacer(
    private val intervalMs: Long,
    private val clock: () -> Long = ::nowMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private var lastTapAt: Long? = null

    suspend fun beforeTap() {
        val last = lastTapAt
        if (last != null && intervalMs > 0) {
            val wait = last + intervalMs - clock()
            if (wait > 0) {
                pause(wait)
            }
        }
        lastTapAt = clock()
    }
}

class TestRunner(
    private val project: Project,
    private val platform: Platform,
    private val device: Device,
    private val drivers: Drivers,
    private val outputDir: String,
    private val log: (String) -> Unit,
    private val healer: Healer? = null,
    /** Without `--heal` a missing element fails with the closest ids on screen in the message. */
    private val suggestIds: Boolean = false,
    /** Which tests keep a video of the screen. */
    private val record: RecordMode = RecordMode.None,
    /** How long a started app may take to answer. */
    private val agentStartTimeoutMs: Long = AGENT_START_TIMEOUT_MS,
) {
    private val heals = mutableListOf<HealRecord>()
    private var rotated = false
    private var locationMocked = false
    private var currentTest = ""
    private var restoreSettings: () -> Unit = {}
    private val mocks = MockSet()
    private var startupMocksDelivered = false
    private val screenshots = mutableListOf<String>()
    private val tapPacer = TapPacer(project.config.tapIntervalMs)
    private var currentLanguage: String? = null
    private val variables =
        Variables(
            language = { currentLanguage },
            today = { device.today() },
            onGenerated = { placeholder, value -> log("    $placeholder = \"$value\"") },
            clock = { device.deviceTime() },
        )
    private val settleMs = project.config.settleMs
    private val focus = FocusWaiter(drivers, settleMs)

    /** The pane whose action is running: its element names are written without the pane. */
    private var scope: String? = null

    /** The pane elements the step that is running refers to, as `pane.element (file)`, for the heal report. */
    private var stepElements: List<String> = emptyList()

    /**
     * Runs one test. A test of a group runs with [keepState] (the app data of the test before stays, a browser keeps
     * its context) and, when another one follows, with [keepSession].
     */
    suspend fun run(
        test: TestFile,
        path: String,
        keepState: Boolean = false,
        keepSession: Boolean = false,
    ): TestResult {
        heals.clear()
        screenshots.clear()
        currentTest = test.name
        variables.clear()
        mocks.reset()
        startupMocksDelivered = false
        val outcomes = mutableListOf<StepOutcome>()
        val started = nowMillis()
        var failure: String? = null
        var failedStep: String? = null
        val artifacts = mutableListOf<String>()
        var infrastructure = false
        var recording: Recording? = null
        var warnings = emptyList<String>()
        try {
            val startBegin = nowMillis()
            val settings = project.config.device.overriddenBy(test.device)
            currentLanguage = settings.language
            val steps = test.uses.map { Step(say = "use $it", action = Action(use = it)) } + test.steps
            freshStart(settings, keepState) { applyLeadingMocks(steps) }
            log("  (${if (keepState) "start in the group" else "fresh start"} ${nowMillis() - startBegin}ms)")
            recording = if (keepState || keepSession) null else startRecording()
            execute(steps, test.timeout ?: project.config.defaultTimeoutMs, outcomes, 0)
        } catch (error: StepFailure) {
            failure = error.message?.let(Masker::mask)?.let { it + currentPanesNote() }
            // A step that fails because the app died is not a failure of the test: say so and count it as the device's
            if (runCatching { device.isAppRunning(project.config) }.getOrNull() == false) {
                failure = "The app is not running any more (it crashed or was stopped): $failure"
                infrastructure = true
            }
            failedStep = outcomes.lastOrNull()?.description
            artifacts += collectArtifacts(test.name, path, failure, failedStep, infrastructure)
        } catch (error: ConfigurationException) {
            // A version that does not match (or any other setting to change) is not a failure of this test: stop the
            // run. The mocks of the test do not stay in the app.
            runCatching { endMocks() }
            throw error
        } catch (error: Exception) {
            failure = Masker.mask(error.message ?: error.toString())
            infrastructure = true
            artifacts += collectArtifacts(test.name, path, failure, failedStep, infrastructure)
        }
        artifacts += screenshots
        // Artifacts are collected first; afterwards the app is quit so no test leaves it running
        val video = finishRecording(recording, test.name, failure != null)
        if (video != null) artifacts += video
        // Whatever the test mocked is undone, however the test ended
        warnings = endMocks()
        if (rotated) {
            runCatching { drivers.send(Command.SetOrientation(false), forceSystem = true) }
            rotated = false
        }
        if (locationMocked) {
            runCatching { drivers.send(Command.ClearLocation, forceSystem = true) }
            locationMocked = false
        }
        runCatching { device.endTest(project.config, keepSession) }
        runCatching { restoreSettings() }
        restoreSettings = {}
        return TestResult(
            test.name,
            path,
            nowMillis() - started,
            outcomes,
            failure,
            failedStep,
            artifacts,
            healed = heals.toList(),
            infrastructure = infrastructure,
            warnings = warnings,
        )
    }

    /**
     * Runs the tests of a unit. A test on its own is a normal fresh start. A group starts with its setup flows in a
     * fresh start; when they pass, the tests run one after the other without clearing the app data (a browser keeps its
     * context, with the login in it), then the teardown flows. When the setup fails the tests are skipped with the
     * reason, nothing is retried. [started] is told the name before each run, [finished] gets every result.
     */
    suspend fun runUnit(
        unit: RunUnit,
        started: suspend (String) -> Unit,
        finished: suspend (TestResult) -> Unit,
    ): List<TestResult> {
        val results = mutableListOf<TestResult>()
        suspend fun record(result: TestResult) {
            results += result
            finished(result)
        }
        val group = unit.group
        if (group == null) {
            for ((path, test) in unit.tests) {
                started(test.name)
                record(run(test, path))
            }
            return results
        }
        val setup = flowsAsTest("${group.name} (setup)", group.file.setup)
        started(setup.name)
        val setupResult = run(setup, group.path, keepSession = true)
        record(setupResult)
        if (setupResult.failure != null) {
            // The session was kept for the tests that will not run
            runCatching { device.endTest(project.config, keepSession = false) }
            val reason = "skipped: the setup of group '${group.name}' failed: ${setupResult.failure}"
            for ((path, test) in unit.tests) {
                record(TestResult(test.name, path, 0, emptyList(), reason, null, emptyList(), skipped = true))
            }
            return results
        }
        val teardown = group.file.teardown
        unit.tests.forEachIndexed { index, (path, test) ->
            started(test.name)
            val more = index < unit.tests.lastIndex || teardown.isNotEmpty()
            record(run(test, path, keepState = true, keepSession = more))
        }
        if (teardown.isNotEmpty()) {
            val finish = flowsAsTest("${group.name} (teardown)", teardown)
            started(finish.name)
            record(run(finish, group.path, keepState = true, keepSession = false))
        }
        return results
    }

    private fun flowsAsTest(name: String, flows: List<String>): TestFile {
        return TestFile(name = name, steps = flows.map { Step(say = "use $it", action = Action(use = it)) })
    }

    private fun startRecording(): Recording? {
        if (record == RecordMode.None) return null
        return try {
            device.startRecording()
        } catch (error: IllegalStateException) {
            log("  (recording is not available: ${error.message})")
            null
        }
    }

    /** Saves the video of a test when the run option asks for it (every test or failed tests); null when none. */
    private fun finishRecording(recording: Recording?, testName: String, failed: Boolean): String? {
        recording ?: return null
        val keep = record == RecordMode.All || (record == RecordMode.Failures && failed)
        val dir = "$outputDir/${testName.replace(Regex("[^A-Za-z0-9_.-]"), "_")}"
        if (keep) makeDirs(dir)
        val path = "$dir/video.${recording.extension}"
        return if (runCatching { recording.stop(if (keep) path else null) }.getOrDefault(false)) path else null
    }

    private suspend fun freshStart(settings: DeviceSettings, keepState: Boolean, beforeLaunch: () -> Unit) {
        restoreSettings = device.freshStart(project.config, settings, keepState, beforeLaunch)
        if (!drivers.waitForAgent(agentStartTimeoutMs) && !startAppAgain()) {
            throw InfrastructureException("In-process agent did not respond; is the gekko agent part of this build?")
        }
        drivers.waitForForeground(agentStartTimeoutMs)
    }

    /**
     * An app process that is killed right after its start never answers (on Android the late end of the `pm clear`
     * before it did that), so an app that is gone is started once more. False when the app still runs, or when the
     * device cannot tell, or when it does not answer the second time either.
     */
    private suspend fun startAppAgain(): Boolean {
        if (runCatching { device.isAppRunning(project.config) }.getOrNull() != false) return false
        log("  (the app is not running, starting it again)")
        onDevice {
            // A new app process knows nothing of the mocks: it gets them again before it starts
            if (mocks.isActive) handOverMocks()
            device.launch(project.config)
        }
        return drivers.waitForAgent(agentStartTimeoutMs)
    }

    private suspend fun execute(steps: List<Step>, timeout: Long, outcomes: MutableList<StepOutcome>, depth: Int) {
        for (declared in steps) {
            if (!declared.runsOn(platform) || !declared.action.runsOn(platform)) {
                logSkipped(declared, depth)
                continue
            }
            val resolved = resolve(declared)
            val step =
                if (resolved.until?.runsOn(platform) == false) resolved.copy(until = null, attempts = 1) else resolved
            val named = declared.elementReferences().isNotEmpty()
            val label = "  ".repeat(depth) + (step.say ?: describe(if (named) declared.action else step.action))
            val condition = step.condition
            if (condition != null && !holds(condition)) {
                log("  $label (skipped, condition not met)")
                continue
            }
            val started = nowMillis()
            outcomes += StepOutcome(label, 0)
            log("  $label")
            val use = step.action.use
            val repeat = step.action.repeat
            if (use != null) {
                val callable = project.loadCallable(use, step.action.with, scope)
                val outer = scope
                scope = callable.pane
                try {
                    callable.pane?.let { requireOnPane(it, timeout) }
                    execute(callable.steps, timeout, outcomes, depth + 1)
                } finally {
                    scope = outer
                }
            } else if (repeat != null) {
                repeatSteps(repeat, timeout, outcomes, depth)
            } else {
                stepElements = qualifiedElements(declared)
                try {
                    if (step.optional) performOptional(step, timeout) else performHealing(step, step.timeout ?: timeout)
                } catch (failure: StepFailure) {
                    throw naming(failure, declared)
                }
            }
            if (use == null && repeat == null && mocks.isActive) pullMockHits()
            val duration = nowMillis() - started
            outcomes[outcomes.indexOfLast { it.description == label }] = StepOutcome(label, duration)
            if (use == null && repeat == null) log("    ${duration}ms")
        }
    }

    private fun resolve(step: Step): Step {
        val expanded = PaneExpander(project.panes, platform, scope).step(step)
        return expanded.copy(
            say = expanded.say?.let(variables::resolve),
            action = expanded.action.resolved(variables::resolve),
            until = expanded.until?.resolved(variables::resolve),
            condition = expanded.condition?.resolved(variables::resolve),
        )
    }

    /** The panes whose identity is on screen now, as a line for a failure message; empty without panes. */
    private suspend fun currentPanesNote(): String {
        val current = currentPanes(null)
        return if (current.isEmpty()) "" else "\n    current pane: ${current.joinToString(", ")}"
    }

    private suspend fun currentPanes(known: List<UiNode>?): List<String> {
        if (project.panes.isEmpty) return emptyList()
        val windows = known ?: runCatching { (drivers.send(Command.Tree) as? Response.TreeResult)?.windows }.getOrNull()
        return windows?.let { project.panes.current(it, platform) }.orEmpty()
    }

    /** `pane.element (panes/pane.yaml)` for every pane element a step refers to. */
    private fun qualifiedElements(declared: Step): List<String> {
        return declared.elementReferences().distinct().map { reference ->
            val found = runCatching { project.panes.resolveElement(reference, scope) }.getOrNull()
            if (found == null) reference
            else "${found.qualified} (${project.panes.pathOf(found.pane).removePrefix("${project.root}/")})"
        }
    }

    /** A failure of a step that used pane elements says which, and what they stand for on this platform. */
    private fun naming(failure: StepFailure, declared: Step): StepFailure {
        val expander = PaneExpander(project.panes, platform, scope)
        val lines =
            declared.elementReferences().distinct().mapNotNull { reference ->
                val found =
                    runCatching { project.panes.resolveElement(reference, scope) }.getOrNull() ?: return@mapNotNull null
                val selector = runCatching { expander.elementSpec(found).toSelector(platform) }.getOrNull()
                "    element ${found.qualified} = ${selector ?: "?"}"
            }
        if (lines.isEmpty()) return failure
        return StepFailure("${failure.message}\n${lines.joinToString("\n")}", failure.inUntil)
    }

    /** An action of a pane starts on that pane: when it has an identity the action waits for it first. */
    private suspend fun requireOnPane(pane: String, timeout: Long) {
        if (project.panes.pane(pane).identity.isEmpty()) return
        assertPane(pane, timeout, settle = true)
    }

    private suspend fun assertPane(pane: String, timeout: Long, settle: Boolean) {
        for (selector in project.panes.identity(pane, platform)) {
            try {
                assertVisible(selector, timeout, true, settle)
            } catch (failure: StepFailure) {
                throw StepFailure("not on pane '$pane': ${failure.message}")
            }
        }
    }

    private suspend fun repeatSteps(repeat: RepeatSpec, timeout: Long, outcomes: MutableList<StepOutcome>, depth: Int) {
        val indent = "  ".repeat(depth + 1)
        val times = repeat.times
        if (times != null) {
            repeat(times) { round ->
                log("  $indent" + "round ${round + 1}/$times")
                execute(repeat.steps, timeout, outcomes, depth + 1)
            }
            return
        }
        val condition = repeat.whileCondition ?: throw StepFailure("repeat needs times or while")
        var rounds = 0
        while (holds(condition)) {
            if (rounds >= repeat.max) {
                throw StepFailure("repeat while: still true after ${repeat.max} rounds")
            }
            rounds++
            log("  $indent" + "round $rounds")
            execute(repeat.steps, timeout, outcomes, depth + 1)
        }
    }

    /** Looks at the screen briefly: a condition is about something that may well not be there. */
    private suspend fun holds(condition: Condition): Boolean {
        val expanded = PaneExpander(project.panes, platform, scope).condition(condition)
        val resolved = expanded.resolved(variables::resolve)
        val pane = resolved.pane
        if (pane != null) {
            return project.panes.identity(pane, platform).all { glimpse(it, true) }
        }
        val spec = resolved.visible ?: resolved.notVisible ?: return false
        return glimpse(spec.toSelector(platform), resolved.visible != null)
    }

    /** True when the element is (or, with [visible] false, is not) on screen within `optionalTimeoutMs`. */
    private suspend fun glimpse(selector: Selector, visible: Boolean, timeout: Long? = null): Boolean {
        return try {
            assertVisible(selector, timeout ?: project.config.optionalTimeoutMs, visible, settle = false)
            true
        } catch (failure: StepFailure) {
            false
        }
    }

    /**
     * An optional step that finds nothing to act on is skipped after a short look, not after the full timeout. Once the
     * element is there the step is a normal step: only an element that vanished between the look and the action is
     * skipped, a failed `until` or a rejected action still fails.
     */
    private suspend fun performOptional(step: Step, timeout: Long) {
        val selector = selectorOf(step.action)?.takeUnless { it.isEmpty }
        if (selector != null && !glimpse(selector, true, step.timeout)) {
            log("    skipped, optional: not on screen: $selector")
            return
        }
        // An assertion only looked for the element, which is all it does
        if (step.action.assertVisible != null) return
        try {
            performWithRetries(step, step.timeout ?: timeout)
        } catch (failure: StepFailure) {
            if (failure.inUntil || !isMissingElement(failure)) throw failure
            log("    skipped, optional: ${failure.message}")
        }
    }

    private fun isMissingElement(failure: StepFailure): Boolean {
        val reason = failure.message.orEmpty()
        return reason.contains("not found") || reason.startsWith("Expected visible")
    }

    /** Steps for the other platform are not part of the run; with GEKKO_DEBUG the log still says so. */
    private fun logSkipped(step: Step, depth: Int) {
        if (env("GEKKO_DEBUG") != null) {
            val only = step.platform ?: step.action.platform
            log(
                "  ".repeat(depth) +
                    "  (skipped on ${platform.key}, only on $only) ${step.say ?: describe(step.action)}"
            )
        }
    }

    /**
     * With --heal a missing id does not fail the run: the step's `say` is used to find the element on screen. Both
     * parts of a step are healed, the action (`do`) and the state it must reach (`until`): a healed action is followed
     * by its `until` check, and a missing id in that check is healed like an assertion.
     */
    private suspend fun performHealing(step: Step, timeout: Long) {
        try {
            performWithRetries(step, timeout)
        } catch (failure: StepFailure) {
            val activeHealer = healer ?: throw withSuggestions(step, failure)
            val say = step.say ?: describe(step.action)
            val untilFailure =
                if (failure.inUntil) {
                    failure
                } else {
                    if (!canHeal(failure) || !heal(activeHealer, say, step.action, failure, timeout)) throw failure
                    checkUntil(step, timeout) ?: return
                }
            val until = step.until ?: throw untilFailure
            if (!canHeal(untilFailure) || !heal(activeHealer, say, until, untilFailure, timeout)) throw untilFailure
        }
    }

    /** Only a missing element is healed, within the heal budget of the test; any other failure is a real failure. */
    private fun canHeal(failure: StepFailure): Boolean {
        return isMissingElement(failure) && heals.size < MAX_HEALS_PER_TEST
    }

    /** The failure of the `until` condition after a healed action, or null when the condition holds. */
    private suspend fun checkUntil(step: Step, timeout: Long): StepFailure? {
        val until = step.until ?: return null
        return try {
            perform(until, timeout)
            null
        } catch (failure: StepFailure) {
            failure
        }
    }

    /** Adds the closest elements on screen to a missing-element failure, when `--heal` is not used. */
    private suspend fun withSuggestions(step: Step, failure: StepFailure): StepFailure {
        val message = failure.message.orEmpty()
        if (!suggestIds || !(message.contains("not found") || message.startsWith("Expected visible"))) {
            return failure
        }
        val wanted = missingSelector(step, message) ?: return failure
        val windows = (drivers.send(Command.Tree) as? Response.TreeResult)?.windows ?: return failure
        val closest = Suggester.closest(wanted, step.say ?: describe(step.action), windows)
        if (closest.isEmpty()) {
            return failure
        }
        return StepFailure("$message\n    closest on screen: ${closest.joinToString("; ")}")
    }

    /** The selector of the step that the failure names (the action's or the `until` condition's). */
    private fun missingSelector(step: Step, message: String): Selector? {
        val candidates = listOfNotNull(step.action, step.until).mapNotNull { selectorOf(it) }
        return candidates.firstOrNull { message.contains(it.toString()) } ?: candidates.firstOrNull()
    }

    private fun selectorOf(action: Action): Selector? {
        action.tap?.let {
            return it.toSelector(platform)
        }
        action.assertVisible?.let {
            return it.toSelector(platform)
        }
        action.assertFocused?.let {
            return it.toSelector(platform)
        }
        action.pressKey?.until?.let {
            return it.toSelector(platform)
        }
        action.type?.let {
            return it.selector(platform)
        }
        action.scroll?.let {
            return it.selector(platform)
        }
        action.longPress?.let {
            return if (it.namesElement()) it.toSelector(platform) else null
        }
        action.doubleTap?.let {
            return it.toSelector(platform)
        }
        action.eraseText?.let {
            return it.toSelector(platform)
        }
        return null
    }

    private suspend fun heal(
        healer: Healer,
        say: String,
        action: Action,
        failure: StepFailure,
        timeout: Long,
    ): Boolean {
        val message = failure.message.orEmpty()
        val missing = describeMissing(action) ?: return false
        if (action.tap?.system == true) {
            // A system prompt that did not appear (already answered, or not asked) has nothing to press; the app's own
            // screen must not be guessed at
            record(say, missing, "skipped (no system prompt found to answer)", "")
            return true
        }
        val windows = (drivers.send(Command.Tree) as? Response.TreeResult)?.windows ?: return false
        val paneLine =
            currentPanes(windows).takeIf { it.isNotEmpty() }?.let { "Current pane: ${it.joinToString(", ")}\n" }
        val tree = paneLine.orEmpty() + TreePrinter.print(windows)
        val kind =
            when {
                action.tap != null -> "tap"
                action.type != null -> "type"
                action.typeFocused != null -> "type into the focused field"
                action.scroll != null -> "scroll"
                action.assertVisible != null -> "assert"
                else -> return false
            }
        if (kind == "assert") {
            if (!message.startsWith("Expected visible")) return false
            val spec = action.assertVisible ?: return false
            if (!assertionMayBeHealed(spec, tree, windows)) return false
            val (holds, reason) =
                try {
                    healer.checkAssertion(say, "assertVisible $missing", tree)
                } catch (error: Exception) {
                    log("    heal unavailable: ${error.message}")
                    return false
                }
            if (!holds) {
                log("    not healed, the model does not see it on screen: $reason")
                return false
            }
            record(say, missing, "confirmed from the screen (no id check)", reason)
            return true
        }
        if (!message.startsWith("Element not found") && !message.contains("not found")) return false
        val proposal =
            try {
                healer.proposeAction(say, "$kind $missing", tree)
            } catch (error: Exception) {
                log("    heal unavailable: ${error.message}")
                return false
            }
        if (proposal == null) {
            log("    not healed, the model found no fitting element")
            return false
        }
        // A healed step does what the test said, only on another element: the model cannot turn a tap into typing
        if (proposal.kind != kind) {
            log("    not healed, the model proposed a ${proposal.kind} for a $kind step")
            return false
        }
        val spec =
            SelectorSpec(
                id = proposal.selector.id?.let { PlatformId(shared = it) },
                text = proposal.selector.text,
                textContains = proposal.selector.textContains,
                index = proposal.selector.index,
            )
        when (proposal.kind) {
            // The model often picks a text label inside a row; accessibility activation does nothing there on iOS, a
            // real touch does
            "tap" ->
                if (platform == Platform.Ios) {
                    tapPacer.beforeTap()
                    expectOk(
                        drivers.send(Command.Tap(spec.toSelector(platform), timeout, settleMs), forceSystem = true)
                    )
                } else {
                    tap(spec, timeout)
                }
            "type" -> {
                // The text to type belongs to the test, never to the model
                val value = action.type?.value ?: proposal.value.orEmpty()
                expectOk(drivers.send(Command.Type(spec.toSelector(platform), value, timeout, settleMs)))
            }
            "scroll" ->
                expectOk(
                    drivers.send(
                        Command.Scroll(
                            spec.toSelector(platform),
                            ScrollDirection.DOWN,
                            Command.DEFAULT_SCROLL_DP,
                            timeout,
                            settleMs,
                        )
                    )
                )
        }
        val typed = if (proposal.kind == "type") " \"${action.type?.value.orEmpty()}\"" else ""
        record(say, missing, "${proposal.kind} ${describeSelector(proposal.selector)}$typed", proposal.reason)
        return true
    }

    /**
     * An assertion is healed only when its id is nowhere in the tree (a renamed or missing id). When an element with
     * that id exists but is off screen, disabled or in another state the screen is not what the test expects, and the
     * texts and states the assertion names must hold on a node of the screen, checked here and not by the model.
     */
    private fun assertionMayBeHealed(spec: SelectorSpec, tree: String, windows: List<UiNode>): Boolean {
        val id = spec.id?.resolve(platform) ?: return false
        if (windows.any { window -> window.findAll(Selector(id = id)).isNotEmpty() }) {
            log("    not healed: $id exists but the expected state is not shown")
            return false
        }
        val narrowed = spec.toSelector(platform).copy(id = null, index = 0, within = null)
        if (narrowed.isEmpty) {
            // Only the id was asserted, or only a state that cannot be looked for without the id
            return narrowed.enabled == null && narrowed.checked == null && narrowed.selected == null
        }
        val holds = windows.any { window -> window.findAll(narrowed).any { it.isOnScreen(window.bounds) } }
        if (!holds) log("    not healed: the texts and states of the assertion are not on the screen")
        return holds
    }

    private fun record(say: String, missing: String, applied: String, reason: String) {
        log("    healed: $applied")
        heals += HealRecord(currentTest, say, missing, applied, reason, stepElements)
    }

    private fun describeMissing(action: Action): String? {
        val spec = action.tap ?: action.assertVisible
        if (spec != null) return describeSelector(spec.toSelector(platform))
        action.type?.let {
            return describeSelector(it.selector(platform))
        }
        action.scroll?.let {
            return describeSelector(it.selector(platform))
        }
        return null
    }

    private fun describeSelector(selector: Selector): String {
        return listOfNotNull(
                selector.id?.let { "id=$it" },
                selector.text?.let { "text=\"$it\"" },
                selector.textContains?.let { "textContains=\"$it\"" },
                selector.action?.let { "action=$it" },
            )
            .joinToString(" ")
            .ifEmpty { "element" }
    }

    private suspend fun performWithRetries(step: Step, timeout: Long) {
        val until = step.until
        if (until == null) {
            perform(step.action, timeout)
            return
        }
        val attempts = step.attempts.coerceAtLeast(1)
        // Every attempt but the last only waits briefly, so a swallowed action is repeated quickly.
        val attemptTimeout = if (attempts > 1) minOf(timeout, RETRY_CHECK_TIMEOUT_MS) else timeout
        for (attempt in 1..attempts) {
            // The previous attempt may have worked after all (slow transition): then repeating it would act on the
            // wrong screen
            if (attempt > 1 && reached(until)) {
                return
            }
            // An accessibility tap on iOS can report success without effect (SwiftUI tap gestures); retries there use a
            // real touch. The Android agent falls back to a real touch by itself and keeps the whole selector.
            val action =
                if (attempt > 1 && platform == Platform.Ios && step.action.tap != null) {
                    step.action.copy(tap = step.action.tap.copy(system = true))
                } else {
                    step.action
                }
            perform(action, timeout)
            val isLast = attempt == attempts
            try {
                // A transition can look finished while the next screen is still being built, so this check always gets
                // its whole window
                perform(until, if (isLast) timeout else attemptTimeout, settle = false)
                return
            } catch (failure: StepFailure) {
                if (isLast) throw StepFailure(failure.message.orEmpty(), inUntil = true)
                log("    retry ${attempt + 1}/$attempts: ${failure.message}")
            }
        }
    }

    private suspend fun reached(condition: Action): Boolean {
        return try {
            perform(condition, REACHED_CHECK_MS, settle = false)
            true
        } catch (failure: StepFailure) {
            false
        }
    }

    private suspend fun perform(action: Action, timeout: Long, settle: Boolean = true) {
        if (drivers.isTv) {
            TvRules.touchAction(action)?.let { throw StepFailure(it) }
        }
        when {
            action.tap != null -> tap(action.tap, timeout)
            action.type != null -> {
                val selector = action.type.selector(platform)
                requireFocusOnTv(selector, timeout, settle)
                expectOk(drivers.send(Command.Type(selector, action.type.value, timeout, settleMs)))
            }
            action.typeFocused != null ->
                expectOk(
                    drivers.send(Command.TypeFocused(action.typeFocused.value, timeout), action.typeFocused.system)
                )
            action.scroll != null ->
                expectOk(
                    drivers.send(
                        Command.Scroll(
                            action.scroll.selector(platform),
                            action.scroll.direction,
                            action.scroll.distance,
                            timeout,
                            settleMs,
                        )
                    )
                )
            action.swipe != null -> swipe(action.swipe, timeout)
            action.drag != null -> drag(action.drag, timeout)
            action.assertVisible != null ->
                assertVisible(action.assertVisible.toSelector(platform), timeout, true, settle)
            action.assertNotVisible != null ->
                assertVisible(action.assertNotVisible.toSelector(platform), timeout, false, settle)
            action.assertFocused != null -> assertFocused(action.assertFocused.toSelector(platform), timeout, settle)
            action.wait != null -> delay(action.wait)
            action.pressKey != null -> pressKey(action.pressKey, timeout)
            action.doubleTap != null ->
                touchGesture(
                    Command.DoubleTap(action.doubleTap.toSelector(platform), timeout, settleMs),
                    action.doubleTap.system,
                )
            action.longPress != null -> longPress(action.longPress, timeout)
            action.back == true -> pressKey(PressKeySpec(Keys.BACK), timeout)
            action.openLink != null -> onDevice { device.openLink(action.openLink) }
            action.scrollUntilVisible != null -> scrollUntilVisible(action.scrollUntilVisible, timeout)
            action.eraseText != null -> {
                val selector = action.eraseText.toSelector(platform)
                requireFocusOnTv(selector, timeout, settle)
                expectOk(drivers.send(Command.Type(selector, "", timeout, settleMs)))
            }
            action.waitForAnimationToEnd == true -> waitForAnimationToEnd(timeout)
            action.takeScreenshot != null -> takeScreenshot(action.takeScreenshot)
            action.copyText != null -> copyText(action.copyText, timeout)
            action.hideKeyboard == true -> hideKeyboard()
            action.launchPackage != null -> onDevice { device.launchPackage(action.launchPackage) }
            action.stopPackage != null -> onDevice { device.stopPackage(action.stopPackage) }
            action.launchApp != null -> launchApp(action.launchApp)
            action.stopApp == true -> onDevice { device.terminate(project.config) }
            action.clearState == true -> onDevice { device.clearData(project.config) }
            action.setDarkMode != null -> onDevice { restoreLater(device.setDarkMode(action.setDarkMode)) }
            action.setAirplaneMode != null -> onDevice { restoreLater(device.setAirplaneMode(action.setAirplaneMode)) }
            action.setOrientation != null -> setOrientation(action.setOrientation == "landscape")
            action.setLocation != null -> setLocation(action.setLocation.latitude, action.setLocation.longitude)
            action.setPermissions.isNotEmpty() ->
                onDevice {
                    device.setPermissions(
                        project.config,
                        action.setPermissions.mapValues { PermissionNames.isGranted(it.value) },
                    )
                }
            action.mock != null -> setMock(action.mock)
            action.clearMocks == true -> clearMocks()
            action.assertPane != null -> assertPane(action.assertPane, timeout, settle)
            else -> throw StepFailure("Step has no action")
        }
    }

    /**
     * The in-app agent taps through accessibility actions; if the element does not react to them (e.g. a SwiftUI tap
     * gesture) the system runner synthesizes a real touch.
     */
    private suspend fun tap(spec: SelectorSpec, timeout: Long) {
        val point = spec.point
        if (point != null) {
            tapPoint(point)
            return
        }
        val command = Command.Tap(spec.toSelector(platform), timeout, settleMs)
        tapPacer.beforeTap()
        val response = drivers.send(command, spec.system)
        if (response is Response.Error && response.message.contains("Tap was not handled") && !spec.system) {
            tapPacer.beforeTap()
            expectOk(drivers.send(command, forceSystem = true))
            return
        }
        expectOk(response)
    }

    /**
     * The in-app agent presses the keys it can inside its own windows; for the others (`home`, a key code number, a key
     * of an app that is not in front) it answers `unsupported`, and the system runner, which presses any key, gets it.
     */
    private suspend fun pressKey(spec: PressKeySpec, timeout: Long) {
        val until = spec.until
        if (until == null) {
            expectOk(drivers.sendWithRunnerFallback(Command.PressKey(spec.key, spec.count, timeout), spec.system))
            return
        }
        focus.pressUntil(spec.key, until.toSelector(platform), spec.limit, timeout, spec.system)?.let {
            throw StepFailure(it)
        }
    }

    /** On a TV a field takes text only when the remote moved the focus to it; elsewhere nothing is asked. */
    private suspend fun requireFocusOnTv(selector: Selector, timeout: Long, settle: Boolean) {
        if (!drivers.isTv) {
            return
        }
        focus.awaitFieldFocus(selector, timeout, settle)?.let { throw StepFailure(it) }
    }

    /** Passes once the element has the focus, which a remote moves; the failure names where the focus is. */
    private suspend fun assertFocused(selector: Selector, timeout: Long, settle: Boolean) {
        focus.awaitFocus(selector, timeout, settle)?.let { throw StepFailure(it) }
    }

    /** Device commands report problems as IllegalStateException; a test sees them as a failed step. */
    private fun onDevice(block: () -> Unit) {
        try {
            block()
        } catch (error: IllegalStateException) {
            throw StepFailure(error.message ?: error.toString())
        }
    }

    /** On iOS the agent resigns the first responder, on Android the keyboard is closed over adb. */
    private suspend fun hideKeyboard() {
        if (platform == Platform.Ios) {
            expectOk(drivers.send(Command.HideKeyboard))
            awaitQuiet()
        } else {
            onDevice { device.hideKeyboard() }
        }
    }

    /** Only the system runner can rotate the device; it is put back to portrait when the test ends. */
    private suspend fun setOrientation(landscape: Boolean) {
        expectOk(drivers.send(Command.SetOrientation(landscape), forceSystem = true))
        rotated = landscape
    }

    /**
     * An emulator or a simulator takes the location from its console. Any other Android device gets it from the system
     * runner, which feeds Android's test location providers once the CLI has switched it on as the mock location app;
     * it is cleared when the test ends. `GEKKO_MOCK_LOCATION=1` takes that way on an emulator too.
     */
    private suspend fun setLocation(latitude: Double, longitude: Double) {
        if (env("GEKKO_MOCK_LOCATION") != "1") {
            try {
                device.setLocation(latitude, longitude)
                return
            } catch (error: IllegalStateException) {
                if (platform != Platform.Android) {
                    throw StepFailure(error.message ?: error.toString())
                }
            }
        }
        if (!locationMocked) {
            // The runner has to be installed before appops can name it
            drivers.send(Command.Hello, forceSystem = true)
            onDevice { device.allowMockLocation() }
        }
        expectOk(drivers.send(Command.SetLocation(latitude, longitude), forceSystem = true))
        locationMocked = true
    }

    /** Settings changed during a test are put back when it ends, like the ones from `device:`. */
    private fun restoreLater(restore: () -> Unit) {
        val previous = restoreSettings
        restoreSettings = {
            restore()
            previous()
        }
    }

    private suspend fun launchApp(spec: LaunchSpec) {
        onDevice {
            if (spec.clearState) {
                device.clearData(project.config)
            }
            // A new app process knows nothing of the mocks: it gets them again before it starts
            if (mocks.isActive) handOverMocks()
            device.launch(project.config, spec.arguments)
        }
        if (!drivers.waitForAgent(agentStartTimeoutMs)) {
            throw StepFailure("In-process agent did not respond after launching the app")
        }
        drivers.waitForForeground(agentStartTimeoutMs)
    }

    private suspend fun takeScreenshot(name: String) {
        val safeTest = currentTest.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val safeName = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val dir = "$outputDir/$safeTest"
        makeDirs(dir)
        onDevice { writeFile("$dir/$safeName.png", device.screenshot()) }
        screenshots += "$dir/$safeName.png"
        log("    saved $dir/$safeName.png")
    }

    private suspend fun isOnScreen(selector: Selector): Boolean {
        val tree = drivers.send(Command.Tree) as? Response.TreeResult
        return tree?.windows?.any { window -> window.findAll(selector).any { it.isOnScreen(window.bounds) } } == true
    }

    /**
     * Scrolls until the element is completely inside its scroll container. Every swipe is followed by a short wait for
     * the list to stop; a swipe that changes nothing means the end of the list.
     */
    private suspend fun scrollUntilVisible(spec: ScrollUntilSpec, timeout: Long) {
        val selector = spec.selector(platform)
        val container = spec.container?.toSelector(platform)
        val rtl = layoutIsRtl()
        val finger = fingerDirection(spec.direction, rtl)
        // The element may already be there, e.g. after a screen change that is still being built
        try {
            assertVisible(selector, minOf(timeout, REACHED_CHECK_MS), true, settle = false)
            if (nudgeIntoView(selector, container, finger)) return
        } catch (failure: StepFailure) {
            // not yet: scroll
        }
        var scrolls = 0
        var signature = treeSignature()
        while (scrolls < spec.maxScrolls) {
            swipeWithin(viewportOf(container), finger, SWIPE_FRACTION)
            scrolls++
            awaitQuiet()
            if (isOnScreen(selector) && nudgeIntoView(selector, container, finger)) {
                return
            }
            val now = treeSignature()
            if (now == signature) {
                throw StepFailure("Element not found: $selector (end of the list reached after $scrolls scrolls)")
            }
            signature = now
        }
        throw StepFailure("Element not found after ${spec.maxScrolls} scrolls: $selector")
    }

    /**
     * True when the element is completely inside its scroll container; a few short swipes bring a partly hidden element
     * into view (a row under a sticky bar). An element bigger than the container counts as in view.
     */
    private suspend fun nudgeIntoView(selector: Selector, container: Selector?, finger: String): Boolean {
        repeat(MAX_NUDGES + 1) { round ->
            val windows = treeWindows()
            val match = windows.firstNotNullOfOrNull { window -> onScreenMatch(window, selector) } ?: return false
            val viewport = viewportOf(match.second, container, windows)
            val bounds = match.first.bounds
            val fits =
                bounds.right - bounds.left <= viewport.right - viewport.left &&
                    bounds.bottom - bounds.top <= viewport.bottom - viewport.top
            val inside =
                bounds.left >= viewport.left &&
                    bounds.top >= viewport.top &&
                    bounds.right <= viewport.right &&
                    bounds.bottom <= viewport.bottom
            if (inside || !fits) return true
            if (round == MAX_NUDGES) return true
            swipeWithin(viewport, finger, SWIPE_FRACTION * NUDGE_FRACTION)
            awaitQuiet()
        }
        return true
    }

    /** The first matching node on screen of [window] with the nodes above it, outermost first. */
    private fun onScreenMatch(window: UiNode, selector: Selector): Pair<UiNode, List<UiNode>>? {
        val node = window.findAll(selector).firstOrNull { it.isOnScreen(window.bounds) } ?: return null
        return node to (pathTo(window, node) ?: listOf(window))
    }

    private fun pathTo(root: UiNode, target: UiNode): List<UiNode>? {
        if (root === target) return listOf(root)
        for (child in root.children) {
            pathTo(child, target)?.let {
                return listOf(root) + it
            }
        }
        return null
    }

    /** The area a swipe stays in: the container, or the whole screen. */
    private suspend fun viewportOf(container: Selector?): Bounds {
        val windows = treeWindows()
        return viewportOf(emptyList(), container, windows)
    }

    private fun viewportOf(path: List<UiNode>, container: Selector?, windows: List<UiNode>): Bounds {
        if (container != null) {
            windows
                .firstNotNullOfOrNull { window ->
                    window.findAll(container).firstOrNull { it.isOnScreen(window.bounds) }
                }
                ?.let {
                    return it.bounds
                }
            throw StepFailure("Element not found: $container (the container to scroll)")
        }
        val scrollable = path.dropLast(1).lastOrNull { "scroll" in it.actions }
        return scrollable?.bounds ?: windows.firstOrNull()?.bounds ?: throw StepFailure("No window")
    }

    /** The finger moves opposite to the content: wanted content below means the finger goes up. */
    private fun fingerDirection(direction: String, rtl: Boolean): String {
        return when (direction) {
            ScrollDirection.DOWN -> "up"
            ScrollDirection.UP -> "down"
            ScrollDirection.LEFT -> "right"
            ScrollDirection.RIGHT -> "left"
            ScrollDirection.END -> if (rtl) "right" else "left"
            ScrollDirection.START -> if (rtl) "left" else "right"
            else -> throw StepFailure("Unknown scroll direction '$direction'")
        }
    }

    private suspend fun layoutIsRtl(): Boolean {
        return (drivers.send(Command.Metrics) as? Response.MetricsResult)?.rtl ?: false
    }

    /**
     * Swipes inside [area] from one side of its middle half to the other; a larger [divider] makes a shorter swipe. In
     * a browser the same stroke is a turn of the mouse wheel: a drag with the mouse does not scroll a page.
     */
    private suspend fun swipeWithin(area: Bounds, finger: String, divider: Int) {
        val cx = area.centerX
        val cy = area.centerY
        val dx = (area.right - area.left) / divider
        val dy = (area.bottom - area.top) / divider
        if (platform == Platform.Web) {
            // The finger moves opposite to the content: a finger going up reveals what is below
            val wheel =
                when (finger) {
                    "up" -> Command.Wheel(cx, cy, 0, 2 * dy)
                    "down" -> Command.Wheel(cx, cy, 0, -2 * dy)
                    "left" -> Command.Wheel(cx, cy, 2 * dx, 0)
                    "right" -> Command.Wheel(cx, cy, -2 * dx, 0)
                    else -> throw StepFailure("Unknown swipe direction '$finger'")
                }
            expectOk(drivers.send(wheel))
            return
        }
        // A drag moves the content by exactly the distance and lets it rest: a fling would carry it past the element
        val command =
            when (finger) {
                "up" -> Command.Drag(cx, cy + dy, cx, cy - dy)
                "down" -> Command.Drag(cx, cy - dy, cx, cy + dy)
                "left" -> Command.Drag(cx + dx, cy, cx - dx, cy)
                "right" -> Command.Drag(cx - dx, cy, cx + dx, cy)
                else -> throw StepFailure("Unknown swipe direction '$finger'")
            }
        sendDrag(command, forceSystem = false)
    }

    private suspend fun treeWindows(): List<UiNode> {
        return (drivers.send(Command.Tree) as? Response.TreeResult)?.windows.orEmpty()
    }

    private suspend fun treeSignature(): Int {
        return treeWindows().fold(17) { hash, window -> 31 * hash + window.signature() }
    }

    /** Waits until two reads of the screen, [QUIET_MS] apart, are identical (or [QUIET_TIMEOUT_MS] ran out). */
    private suspend fun awaitQuiet() {
        val deadline = nowMillis() + QUIET_TIMEOUT_MS
        var last: Int? = null
        while (nowMillis() < deadline) {
            val windows = (drivers.send(Command.Tree) as? Response.TreeResult)?.windows.orEmpty()
            val signature = windows.fold(17) { hash, window -> 31 * hash + window.signature() }
            if (signature == last) {
                return
            }
            last = signature
            delay(QUIET_MS)
        }
    }

    /** Same notion of "done" as for missing elements: no change for settleMs and no progress indicator. */
    private suspend fun waitForAnimationToEnd(timeout: Long) {
        val tracker = SettleTracker(settleMs.coerceAtLeast(QUIET_MS), nowMillis())
        val deadline = nowMillis() + timeout
        while (true) {
            val windows = (drivers.send(Command.Tree) as? Response.TreeResult)?.windows.orEmpty()
            tracker.observe(
                signature = windows.fold(17) { hash, window -> 31 * hash + window.signature() },
                busy = windows.any { it.hasBusyNode() },
                now = nowMillis(),
            )
            if (tracker.isSettled(nowMillis())) {
                return
            }
            if (nowMillis() > deadline) {
                throw StepFailure("The screen did not stop changing within ${timeout}ms")
            }
            delay(POLL_MS)
        }
    }

    /**
     * Double taps and long presses are real touches; the iOS agent works through accessibility and cannot make them.
     */
    private suspend fun touchGesture(command: Command, forceSystem: Boolean) {
        tapPacer.beforeTap()
        expectOk(drivers.send(command, forceSystem || platform == Platform.Ios))
    }

    private suspend fun screenBounds(): Bounds {
        val tree = drivers.send(Command.Tree) as? Response.TreeResult ?: throw StepFailure("No tree")
        return tree.windows.firstOrNull()?.bounds ?: throw StepFailure("No window")
    }

    private fun pointOf(screen: Bounds, text: String): Pair<Int, Int> {
        val (fx, fy) = Points.parse(text) ?: throw StepFailure("Not a point: '$text'")
        return screen.left + ((screen.right - screen.left) * fx).toInt() to
            screen.top + ((screen.bottom - screen.top) * fy).toInt()
    }

    private suspend fun tapPoint(text: String) {
        val (x, y) = pointOf(screenBounds(), text)
        tapPacer.beforeTap()
        onDevice { device.tap(x, y) }
    }

    /** Swipes the screen up/down/left/right (the finger moves that way) with the default speed. */
    private suspend fun swipe(direction: String) {
        swipe(SwipeSpec(direction = direction), project.config.defaultTimeoutMs)
    }

    private suspend fun swipe(spec: SwipeSpec, timeout: Long) {
        val screen = screenBounds()
        val duration = spec.durationMs
        val start = spec.start
        val end = spec.end
        val from = spec.from
        val direction = spec.direction
        val command =
            when {
                start != null && end != null -> {
                    val (sx, sy) = pointOf(screen, start)
                    val (ex, ey) = pointOf(screen, end)
                    Command.Swipe(sx, sy, ex, ey)
                }
                from != null && direction != null -> {
                    val (cx, cy) = centerOf(from, timeout)
                    // The finger leaves the element and goes on to the screen's edge
                    when (direction.lowercase()) {
                        "right" -> Command.Swipe(cx, cy, screen.right - 1, cy)
                        "left" -> Command.Swipe(cx, cy, screen.left + 1, cy)
                        "up" -> Command.Swipe(cx, cy, cx, screen.top + 1)
                        "down" -> Command.Swipe(cx, cy, cx, screen.bottom - 1)
                        else -> throw StepFailure("Unknown swipe direction '$direction'")
                    }
                }
                direction != null -> {
                    val cx = screen.centerX
                    val cy = screen.centerY
                    val dx = screen.right / SWIPE_FRACTION
                    val dy = screen.bottom / SWIPE_FRACTION
                    when (direction.lowercase()) {
                        "up" -> Command.Swipe(cx, cy + dy, cx, cy - dy)
                        "down" -> Command.Swipe(cx, cy - dy, cx, cy + dy)
                        "left" -> Command.Swipe(cx + dx, cy, cx - dx, cy)
                        "right" -> Command.Swipe(cx - dx, cy, cx + dx, cy)
                        else -> throw StepFailure("Unknown swipe direction '$direction'")
                    }
                }
                else -> throw StepFailure("swipe needs a direction or start and end")
            }
        // The iOS agent works through accessibility and cannot make a fling: a swipe is a real touch of the runner
        val forceSystem = spec.system || platform == Platform.Ios
        expectOk(drivers.send(if (duration != null) command.copy(durationMs = duration) else command, forceSystem))
    }

    /** Moves the content under the start to the end, see [DragSpec]. */
    private suspend fun drag(spec: DragSpec, timeout: Long) {
        val screen = screenBounds()
        val from = spec.from
        val start = spec.start
        val (sx, sy) =
            when {
                start != null -> pointOf(screen, start)
                from != null -> centerOf(from, timeout)
                else -> throw StepFailure("drag needs a start or an element to start from")
            }
        val (ex, ey) = pointOf(screen, spec.end)
        sendDrag(Command.Drag(sx, sy, ex, ey), spec.system)
    }

    /**
     * The iOS agent drags by moving the offset of the scroll view under the start, and answers `unsupported` when there
     * is none (a slider, a reorder handle): the system runner then makes a real touch.
     */
    private suspend fun sendDrag(command: Command.Drag, forceSystem: Boolean) {
        val response = drivers.send(command, forceSystem)
        val refused = response is Response.Error && response.kind == ErrorKind.UNSUPPORTED
        if (refused && !forceSystem && platform == Platform.Ios) {
            expectOk(drivers.send(command, forceSystem = true))
            return
        }
        expectOk(response)
    }

    /** Where the element is on screen, once it is visible: the bounds of the first one the selector means. */
    private suspend fun boundsOf(selector: Selector, timeout: Long): Bounds {
        assertVisible(selector, timeout, true)
        val windows = (drivers.send(Command.Tree) as? Response.TreeResult)?.windows.orEmpty()
        val node =
            windows
                .flatMap { window -> window.findAll(selector).filter { it.isOnScreen(window.bounds) } }
                .getOrNull(selector.index) ?: throw StepFailure("Element not found: $selector")
        return node.bounds
    }

    private suspend fun centerOf(from: SelectorSpec, timeout: Long): Pair<Int, Int> {
        val bounds = boundsOf(from.toSelector(platform), timeout)
        return bounds.centerX to bounds.centerY
    }

    /** A long press of an element, of a spot of an element, or of a spot of the screen. */
    private suspend fun longPress(spec: LongPressSpec, timeout: Long) {
        val point = spec.point
        if (point == null) {
            touchGesture(Command.LongPress(spec.toSelector(platform), spec.holdMs, timeout, settleMs), spec.system)
            return
        }
        val area = if (spec.namesElement()) boundsOf(spec.toSelector(platform), timeout) else screenBounds()
        val (x, y) = pointOf(area, point)
        touchGesture(Command.LongPressAt(x, y, spec.holdMs), spec.system)
    }

    /** Keeps the text of an element (or the part of it that matches) in a variable for later steps. */
    private suspend fun copyText(spec: CopyTextSpec, timeout: Long) {
        val selector = spec.from.toSelector(platform)
        assertVisible(selector, timeout, true)
        val windows = (drivers.send(Command.Tree) as? Response.TreeResult)?.windows.orEmpty()
        val node =
            windows
                .flatMap { window -> window.findAll(selector).filter { it.isOnScreen(window.bounds) } }
                .getOrNull(selector.index) ?: throw StepFailure("Element not found: $selector")
        val text = node.text.orEmpty().normalizedSpaces()
        val pattern = spec.match
        val value =
            if (pattern == null) {
                text
            } else {
                val found =
                    Regex(pattern).find(text)
                        ?: throw StepFailure("copyText: '$pattern' does not match \"$text\" of $selector")
                if (found.groupValues.size > 1) found.groupValues[1] else found.value
            }
        variables.set(spec.name, value)
        log("    ${spec.name} = \"$value\"")
    }

    private suspend fun assertVisible(selector: Selector, timeout: Long, visible: Boolean, settle: Boolean = true) {
        if (drivers.supportsWaitFor) {
            // The agent polls next to the app: one request instead of a tree over the wire every 50 ms
            expectOk(drivers.send(Command.WaitFor(selector, visible, timeout, if (settle) settleMs else 0)))
            return
        }
        val deadline = nowMillis() + timeout
        val tracker = SettleTracker(if (settle) settleMs else 0, nowMillis())
        while (true) {
            val tree = drivers.send(Command.Tree) as? Response.TreeResult
            val found =
                tree?.windows?.any { window -> window.findAll(selector).any { it.isOnScreen(window.bounds) } } == true
            if (found == visible) {
                return
            }
            val windows = tree?.windows.orEmpty()
            tracker.observe(
                signature = windows.fold(17) { hash, window -> 31 * hash + window.signature() },
                busy = windows.any { it.hasBusyNode() },
                now = nowMillis(),
            )
            // Past the point where the screen is done a wait cannot help; only a loading screen gets the full timeout
            if (tracker.isSettled(nowMillis()) || nowMillis() > deadline) {
                throw StepFailure(if (visible) "Expected visible: $selector" else "Expected not visible: $selector")
            }
            delay(POLL_MS)
        }
    }

    private fun expectOk(response: Response) {
        if (response is Response.Error) {
            throw StepFailure(response.message)
        }
    }

    private suspend fun collectArtifacts(
        testName: String,
        path: String,
        failure: String?,
        failedStep: String?,
        infrastructure: Boolean,
    ): List<String> {
        val safe = testName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val dir = "$outputDir/$safe"
        makeDirs(dir)
        val artifacts = mutableListOf<String>()
        writeReportFile(
            "$dir/failure.json",
            Masker.mask(
                FailureInfo.encode(
                    FailureInfo(testName, path, failedStep, failure, if (infrastructure) "infrastructure" else "test")
                )
            ),
        )
        runCatching {
            writeFile("$dir/screenshot.png", device.screenshot())
            artifacts += "$dir/screenshot.png"
        }
        runCatching {
            val tree = drivers.send(Command.Tree)
            writeReportFile(
                "$dir/tree.txt",
                Masker.mask(TreePrinter.print((tree as? Response.TreeResult)?.windows.orEmpty())),
            )
            artifacts += "$dir/tree.txt"
        }
        runCatching {
            val console = device.consoleLog()
            if (!console.isNullOrBlank()) {
                writeReportFile("$dir/console.txt", Masker.mask(console))
                artifacts += "$dir/console.txt"
            }
        }
        return artifacts
    }

    /**
     * The `mock` steps a test starts with, also inside the flows it calls first. They are applied before gekko starts
     * the app, so that requests made while the app starts are answered too. A step that needs the screen (or is
     * conditional) ends the run of leading steps; a step for another platform is not part of it.
     */
    private fun leadingMockSteps(steps: List<Step>, inScope: String? = null): Pair<List<Step>, Boolean> {
        val found = mutableListOf<Step>()
        for (declared in steps) {
            if (!declared.runsOn(platform) || !declared.action.runsOn(platform)) continue
            if (declared.condition != null) return found to false
            // Nothing else is looked at: a test without leading mocks does not generate values or ask the device here
            if (declared.action.mock == null && declared.action.use == null) return found to false
            val step =
                try {
                    resolve(declared)
                } catch (failure: StepFailure) {
                    return found to false
                }
            val use = step.action.use
            if (step.action.mock != null) {
                found += declared
            } else if (use != null) {
                val callable = project.loadCallable(use, step.action.with, inScope)
                val (inner, more) = leadingMockSteps(callable.steps, callable.pane)
                found += inner
                if (!more) return found to false
            } else {
                return found to false
            }
        }
        return found to true
    }

    /** Runs before the app is started: sets the leading mocks and hands them to the app. */
    private fun applyLeadingMocks(steps: List<Step>) {
        val leading = leadingMockSteps(steps).first
        if (leading.isEmpty()) return
        for (declared in leading) {
            val spec = resolve(declared).action.mock ?: continue
            mocks.add(spec, mockBody(spec))
        }
        onDevice { handOverMocks() }
    }

    /** The content of the mock file with its placeholders filled in, or an empty body when the step has no file. */
    private fun mockBody(spec: MockSpec): String {
        val file = spec.file ?: return ""
        if (!fileExists("${project.root}/${project.config.mocksDir}/$file")) {
            throw StepFailure("Mock file does not exist: ${project.config.mocksDir}/$file")
        }
        // A value inside a .json file must not break it; other files are used as they are
        val json = file.endsWith(".json", ignoreCase = true)
        val escape = { value: String -> if (json) jsonEscape(value) else value }
        try {
            return project.renderMock(project.readMockFile(file), escape) { variables.resolve(it, escape) }
        } catch (error: IllegalArgumentException) {
            throw StepFailure("${project.config.mocksDir}/$file: ${error.message}")
        }
    }

    /** Gives the mocks to the app that is about to start. */
    private fun handOverMocks() {
        device.setStartupMocks(project.config, encodeCommand(Command.SetMocks(mocks.rules())))
        startupMocksDelivered = true
    }

    private suspend fun setMock(spec: MockSpec) {
        val entry = mocks.add(spec, mockBody(spec))
        log("    mock ${entry.label} → ${entry.answer()}")
        syncMocks()
    }

    /** The answers the mocks gave so far are counted first: the mocks that are removed now are not asked again. */
    private suspend fun clearMocks() {
        pullMockHits()
        mocks.clear()
        syncMocks()
    }

    /**
     * Tells the running app what is mocked now. An app that does not run picks the mocks up when it starts, and an app
     * that cannot mock says so.
     */
    private suspend fun syncMocks() {
        val response = drivers.sendToAgent(if (mocks.isActive) Command.SetMocks(mocks.rules()) else Command.ClearMocks)
        if (response is Response.Error) {
            throw StepFailure(
                "The app cannot mock network requests: ${response.message} " +
                    "(is the gekko agent of this release part of the build, with network mocks switched on?)"
            )
        }
        if (response == null) {
            onDevice {
                if (mocks.isActive) {
                    handOverMocks()
                } else if (startupMocksDelivered) {
                    device.setStartupMocks(project.config, null)
                }
            }
        }
    }

    /** Logs the requests that mocks answered since the last time; the app may be gone, then there is nothing to ask. */
    private suspend fun pullMockHits() {
        val answer = drivers.sendToAgent(Command.MockHits) as? Response.MockHitsResult ?: return
        mocks.record(answer.hits).forEach { log("    $it") }
    }

    /**
     * The end of a test: the last answers are counted, then the app is told to forget every mock and what was handed
     * over for a start that never came is taken back. Returns a warning for each mock that no request used (a typo in
     * the URL, or a client gekko cannot reach).
     */
    private suspend fun endMocks(): List<String> {
        if (!mocks.hasBeenSet) return emptyList()
        runCatching { pullMockHits() }
        val warnings = mocks.unused().map { "network mock was never used: $it" }
        runCatching { drivers.sendToAgent(Command.ClearMocks) }
        if (startupMocksDelivered) runCatching { device.setStartupMocks(project.config, null) }
        mocks.reset()
        startupMocksDelivered = false
        warnings.forEach { log("  warning: $it") }
        return warnings
    }

    private fun describe(action: Action): String {
        return when {
            action.tap != null ->
                "tap ${action.tap.point?.let { "point $it" } ?: action.tap.element ?: action.tap.toSelector(platform)}"
            action.type != null -> "type into ${action.type.element ?: action.type.id ?: action.type.text}"
            action.scroll != null -> "scroll"
            action.swipe != null -> "swipe ${action.swipe.direction ?: "between points"}"
            action.drag != null -> "drag to ${action.drag.end}"
            action.assertVisible != null ->
                "assert visible ${action.assertVisible.element ?: action.assertVisible.toSelector(platform)}"
            action.assertNotVisible != null ->
                "assert not visible ${action.assertNotVisible.element ?: action.assertNotVisible.toSelector(platform)}"
            action.assertFocused != null ->
                "assert focused ${action.assertFocused.element ?: action.assertFocused.toSelector(platform)}"
            action.assertPane != null -> "assert pane ${action.assertPane}"
            action.wait != null -> "wait ${action.wait}ms"
            action.pressKey != null -> "press ${action.pressKey.describe()}"
            action.openLink != null -> "open ${action.openLink}"
            action.use != null -> "use ${action.use}"
            action.doubleTap != null ->
                "double tap ${action.doubleTap.element ?: action.doubleTap.id ?: action.doubleTap.text}"
            action.longPress != null -> {
                val target = action.longPress
                val name = target.element ?: target.id ?: target.text
                val at = target.point
                "long press ${if (at == null) name else if (name == null) "point $at" else "$name at $at"}"
            }
            action.scrollUntilVisible != null -> "scroll until visible"
            action.eraseText != null -> "erase text"
            action.back == true -> "press back"
            action.repeat != null -> "repeat ${action.repeat.times?.let { "$it times" } ?: "while"}"
            action.launchApp != null -> "launch the app"
            action.stopApp == true -> "stop the app"
            action.clearState == true -> "clear the app state"
            action.takeScreenshot != null -> "screenshot ${action.takeScreenshot}"
            action.waitForAnimationToEnd == true -> "wait for the screen to stop changing"
            action.setDarkMode != null -> "dark mode ${if (action.setDarkMode) "on" else "off"}"
            action.setAirplaneMode != null -> "airplane mode ${if (action.setAirplaneMode) "on" else "off"}"
            action.setOrientation != null -> "orientation ${action.setOrientation}"
            action.setLocation != null -> "set location"
            action.setPermissions.isNotEmpty() -> "set permissions"
            action.copyText != null -> "copy text into ${action.copyText.name}"
            action.hideKeyboard == true -> "hide the keyboard"
            action.launchPackage != null -> "launch ${action.launchPackage}"
            action.stopPackage != null -> "stop ${action.stopPackage}"
            action.mock != null -> "mock ${listOfNotNull(action.mock.method, action.mock.url).joinToString(" ")}"
            action.clearMocks == true -> "remove the network mocks"
            else -> "step"
        }
    }

    private companion object {
        const val AGENT_START_TIMEOUT_MS = 20_000L
        const val POLL_MS = 50L
        const val RETRY_CHECK_TIMEOUT_MS = 4_000L
        const val REACHED_CHECK_MS = 1_000L
        const val MAX_HEALS_PER_TEST = 15
        const val NUDGE_FRACTION = 4
        const val MAX_NUDGES = 3
        const val SWIPE_FRACTION = 4
        const val QUIET_MS = 300L
        const val QUIET_TIMEOUT_MS = 3_000L
    }
}

object TreePrinter {
    /**
     * One line per element that has an id, a text or an action. [label] adds a note to a line, e.g. the pane element.
     */
    fun print(windows: List<UiNode>, label: (UiNode) -> String? = { null }): String {
        val builder = StringBuilder()
        windows.forEach { append(builder, it, 0, label) }
        return builder.toString()
    }

    private fun append(builder: StringBuilder, node: UiNode, depth: Int, label: (UiNode) -> String?) {
        val interesting =
            node.id != null ||
                !node.text.isNullOrEmpty() ||
                !node.description.isNullOrEmpty() ||
                node.actions.isNotEmpty() ||
                node.focused
        val nextDepth =
            if (interesting) {
                // Everything here comes from the app: a value must not be able to end its line or start an escape
                // sequence
                builder.append("  ".repeat(depth))
                builder.append(Terminal.inline(node.role ?: "node"))
                node.id?.let { builder.append(" id=").append(Terminal.inline(it)) }
                node.text
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { builder.append(" text=\"").append(Terminal.inline(it)).append('"') }
                node.description
                    ?.takeIf { it.isNotEmpty() && it != node.text }
                    ?.let { builder.append(" desc=\"").append(Terminal.inline(it)).append('"') }
                if (node.actions.isNotEmpty()) {
                    builder.append(" actions=").append(Terminal.inline(node.actions.joinToString(",")))
                }
                if (!node.enabled) builder.append(" disabled")
                if (node.focused) builder.append(" focused")
                label(node)?.let { builder.append("  <- ").append(it) }
                builder.append('\n')
                depth + 1
            } else {
                depth
            }
        node.children.forEach { append(builder, it, nextDepth, label) }
    }
}
