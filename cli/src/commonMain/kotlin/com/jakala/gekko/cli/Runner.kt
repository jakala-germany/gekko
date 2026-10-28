package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
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
)

class Project(val root: String, val config: GekkoConfig) {
    private val access: EnvAccess by lazy {
        val dotEnv = "$root/.env".takeIf { fileExists(it) }?.let { EnvAccess.parseDotEnv(readTextFile(it)) }.orEmpty()
        EnvAccess(config.env.toSet(), dotEnv).also { Masker.register(it.values()) }
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
            .distinct()
            .sorted()
    }

    fun loadTest(path: String): TestFile {
        return Yamls.parse(TestFile.serializer(), Yamls.substitute(readTextFile(path), emptyMap(), access))
    }

    fun loadFlow(name: String, params: Map<String, String>): FlowFile {
        val base = "$root/${config.flowsDir}/$name"
        val path =
            listOf("$base.yaml", "$base.yml").firstOrNull { fileExists(it) }
                ?: throw StepFailure("Flow '$name' not found in ${config.flowsDir}/")
        return Yamls.parse(FlowFile.serializer(), Yamls.substitute(readTextFile(path), params, access))
    }

    companion object {
        fun load(root: String): Project {
            val path = "$root/gekko.yaml"
            if (!fileExists(path)) {
                val hint = if (fileExists("$root/gekko.yml")) " (found gekko.yml: rename it to gekko.yaml)" else ""
                throw ConfigurationException(
                    "gekko.yaml not found in ${absolutePath(root, currentDirectory())}$hint; run gekko in your test repository or pass --project"
                )
            }
            val parsed = Yamls.parse(GekkoConfig.serializer(), readTextFile(path))
            return Project(root, parsed.withPathsRelativeTo(absolutePath(root, currentDirectory())))
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
) {
    private val heals = mutableListOf<HealRecord>()
    private var rotated = false
    private var currentTest = ""
    private var restoreSettings: () -> Unit = {}
    private val screenshots = mutableListOf<String>()
    private val tapPacer = TapPacer(project.config.tapIntervalMs)
    private var currentLanguage: String? = null
    private val variables =
        Variables(
            language = { currentLanguage },
            today = { device.today() },
            onGenerated = { placeholder, value -> log("    $placeholder = \"$value\"") },
        )
    private val settleMs = project.config.settleMs

    suspend fun run(test: TestFile, path: String): TestResult {
        heals.clear()
        screenshots.clear()
        currentTest = test.name
        variables.clear()
        val outcomes = mutableListOf<StepOutcome>()
        val started = nowMillis()
        var failure: String? = null
        var failedStep: String? = null
        val artifacts = mutableListOf<String>()
        var infrastructure = false
        var recording: Recording? = null
        try {
            val startBegin = nowMillis()
            val settings = project.config.device.overriddenBy(test.device)
            currentLanguage = settings.language
            freshStart(settings)
            log("  (fresh start ${nowMillis() - startBegin}ms)")
            recording = startRecording()
            val steps = test.uses.map { Step(say = "use $it", action = Action(use = it)) } + test.steps
            execute(steps, test.timeout ?: project.config.defaultTimeoutMs, outcomes, 0)
        } catch (error: StepFailure) {
            failure = error.message?.let(Masker::mask)
            // A step that fails because the app died is not a failure of the test: say so and count it as the device's
            if (runCatching { device.isAppRunning(project.config) }.getOrNull() == false) {
                failure = "The app is not running any more (it crashed or was stopped): $failure"
                infrastructure = true
            }
            failedStep = outcomes.lastOrNull()?.description
            artifacts += collectArtifacts(test.name, path, failure, failedStep, infrastructure)
        } catch (error: ConfigurationException) {
            // A version that does not match (or any other setting to change) is not a failure of this test: stop the
            // run
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
        if (rotated) {
            runCatching { drivers.send(Command.SetOrientation(false), forceSystem = true) }
            rotated = false
        }
        runCatching { device.terminate(project.config) }
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
        )
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
        val path = "$dir/video.mp4"
        return if (runCatching { recording.stop(if (keep) path else null) }.getOrDefault(false)) path else null
    }

    private suspend fun freshStart(settings: DeviceSettings) {
        restoreSettings = device.freshStart(project.config, settings)
        if (!drivers.waitForAgent(AGENT_START_TIMEOUT_MS)) {
            throw InfrastructureException("In-process agent did not respond; is the gekko agent part of this build?")
        }
        drivers.waitForForeground(AGENT_START_TIMEOUT_MS)
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
            val label = "  ".repeat(depth) + (step.say ?: describe(step.action))
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
                val flow = project.loadFlow(use, step.action.with)
                execute(flow.steps, timeout, outcomes, depth + 1)
            } else if (repeat != null) {
                repeatSteps(repeat, timeout, outcomes, depth)
            } else if (step.optional) {
                performOptional(step, timeout)
            } else {
                performHealing(step, step.timeout ?: timeout)
            }
            val duration = nowMillis() - started
            outcomes[outcomes.indexOfLast { it.description == label }] = StepOutcome(label, duration)
            if (use == null && repeat == null) log("    ${duration}ms")
        }
    }

    private fun resolve(step: Step): Step {
        return step.copy(
            say = step.say?.let(variables::resolve),
            action = step.action.resolved(variables::resolve),
            until = step.until?.resolved(variables::resolve),
            condition = step.condition?.resolved(variables::resolve),
        )
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
        val resolved = condition.resolved(variables::resolve)
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
        action.type?.let {
            return it.selector(platform)
        }
        action.scroll?.let {
            return it.selector(platform)
        }
        action.longPress?.let {
            return it.toSelector(platform)
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
        val tree = TreePrinter.print(windows)
        val kind =
            when {
                action.tap != null -> "tap"
                action.type != null -> "type"
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
        heals += HealRecord(currentTest, say, missing, applied, reason)
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
        when {
            action.tap != null -> tap(action.tap, timeout)
            action.type != null ->
                expectOk(
                    drivers.send(Command.Type(action.type.selector(platform), action.type.value, timeout, settleMs))
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
            action.assertVisible != null ->
                assertVisible(action.assertVisible.toSelector(platform), timeout, true, settle)
            action.assertNotVisible != null ->
                assertVisible(action.assertNotVisible.toSelector(platform), timeout, false, settle)
            action.wait != null -> delay(action.wait)
            action.pressKey != null -> onDevice { device.pressKey(action.pressKey) }
            action.doubleTap != null ->
                touchGesture(
                    Command.DoubleTap(action.doubleTap.toSelector(platform), timeout, settleMs),
                    action.doubleTap.system,
                )
            action.longPress != null ->
                touchGesture(
                    Command.LongPress(
                        action.longPress.toSelector(platform),
                        action.longPress.holdMs,
                        timeout,
                        settleMs,
                    ),
                    action.longPress.system,
                )
            action.back == true -> onDevice { device.pressKey("back") }
            action.openLink != null -> onDevice { device.openLink(action.openLink) }
            action.scrollUntilVisible != null -> scrollUntilVisible(action.scrollUntilVisible, timeout)
            action.eraseText != null ->
                expectOk(drivers.send(Command.Type(action.eraseText.toSelector(platform), "", timeout, settleMs)))
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
            action.setLocation != null ->
                onDevice { device.setLocation(action.setLocation.latitude, action.setLocation.longitude) }
            action.setPermissions.isNotEmpty() ->
                onDevice {
                    device.setPermissions(
                        project.config,
                        action.setPermissions.mapValues { PermissionNames.isGranted(it.value) },
                    )
                }
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
            device.launch(project.config, spec.arguments)
        }
        if (!drivers.waitForAgent(AGENT_START_TIMEOUT_MS)) {
            throw StepFailure("In-process agent did not respond after launching the app")
        }
        drivers.waitForForeground(AGENT_START_TIMEOUT_MS)
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

    /** Swipes inside [area] from one side of its middle half to the other; a larger [divider] makes a shorter swipe. */
    private suspend fun swipeWithin(area: Bounds, finger: String, divider: Int) {
        val cx = area.centerX
        val cy = area.centerY
        val dx = (area.right - area.left) / divider
        val dy = (area.bottom - area.top) / divider
        val command =
            when (finger) {
                "up" -> Command.Swipe(cx, cy + dy, cx, cy - dy)
                "down" -> Command.Swipe(cx, cy - dy, cx, cy + dy)
                "left" -> Command.Swipe(cx + dx, cy, cx - dx, cy)
                "right" -> Command.Swipe(cx - dx, cy, cx + dx, cy)
                else -> throw StepFailure("Unknown swipe direction '$finger'")
            }
        expectOk(drivers.send(command))
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
                    val selector = from.toSelector(platform)
                    assertVisible(selector, timeout, true)
                    val windows = (drivers.send(Command.Tree) as? Response.TreeResult)?.windows.orEmpty()
                    val node =
                        windows
                            .flatMap { window -> window.findAll(selector).filter { it.isOnScreen(window.bounds) } }
                            .getOrNull(selector.index) ?: throw StepFailure("Element not found: $selector")
                    val cx = node.bounds.centerX
                    val cy = node.bounds.centerY
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
        expectOk(drivers.send(if (duration != null) command.copy(durationMs = duration) else command))
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
        writeTextFile(
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
            writeTextFile(
                "$dir/tree.txt",
                Masker.mask(TreePrinter.print((tree as? Response.TreeResult)?.windows.orEmpty())),
            )
            artifacts += "$dir/tree.txt"
        }
        return artifacts
    }

    private fun describe(action: Action): String {
        return when {
            action.tap != null -> "tap ${action.tap.point?.let { "point $it" } ?: action.tap.toSelector(platform)}"
            action.type != null -> "type into ${action.type.id ?: action.type.text}"
            action.scroll != null -> "scroll"
            action.swipe != null -> "swipe ${action.swipe.direction ?: "between points"}"
            action.assertVisible != null -> "assert visible ${action.assertVisible.toSelector(platform)}"
            action.assertNotVisible != null -> "assert not visible ${action.assertNotVisible.toSelector(platform)}"
            action.wait != null -> "wait ${action.wait}ms"
            action.pressKey != null -> "press ${action.pressKey}"
            action.openLink != null -> "open ${action.openLink}"
            action.use != null -> "use ${action.use}"
            action.doubleTap != null -> "double tap ${action.doubleTap.id ?: action.doubleTap.text}"
            action.longPress != null -> "long press ${action.longPress.id ?: action.longPress.text}"
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
    fun print(windows: List<UiNode>): String {
        val builder = StringBuilder()
        windows.forEach { append(builder, it, 0) }
        return builder.toString()
    }

    private fun append(builder: StringBuilder, node: UiNode, depth: Int) {
        val interesting =
            node.id != null ||
                !node.text.isNullOrEmpty() ||
                !node.description.isNullOrEmpty() ||
                node.actions.isNotEmpty()
        val nextDepth =
            if (interesting) {
                builder.append("  ".repeat(depth))
                builder.append(node.role ?: "node")
                node.id?.let { builder.append(" id=").append(it) }
                node.text?.takeIf { it.isNotEmpty() }?.let { builder.append(" text=\"").append(it).append('"') }
                node.description
                    ?.takeIf { it.isNotEmpty() && it != node.text }
                    ?.let { builder.append(" desc=\"").append(it).append('"') }
                if (node.actions.isNotEmpty()) builder.append(" actions=").append(node.actions.joinToString(","))
                if (!node.enabled) builder.append(" disabled")
                builder.append('\n')
                depth + 1
            } else {
                depth
            }
        node.children.forEach { append(builder, it, nextDepth) }
    }
}
