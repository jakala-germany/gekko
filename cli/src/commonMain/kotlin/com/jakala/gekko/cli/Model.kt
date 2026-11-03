package com.jakala.gekko.cli

import com.charleskorn.kaml.UnknownPropertyException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ScrollDirection
import com.jakala.gekko.protocol.Selector
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class Platform(val key: String) {
    Android("android"),
    Ios("ios"),
    Web("web"),
}

/** The platform names a test may use, as listed in messages. */
private val PLATFORM_KEYS = Platform.entries.map { it.key }

/** An element id that is either shared (`home.tab`) or per platform (`{ android: a, ios: b, web: c }`). */
@Serializable(with = PlatformIdSerializer::class)
data class PlatformId(
    val shared: String? = null,
    val android: String? = null,
    val ios: String? = null,
    val web: String? = null,
) {
    fun resolve(platform: Platform): String? {
        return when (platform) {
            Platform.Android -> android ?: shared
            Platform.Ios -> ios ?: shared
            Platform.Web -> web ?: shared
        }
    }

    /** The platforms that have no id: `{ android: a }` alone cannot be resolved on iOS. */
    fun missingPlatforms(): List<Platform> {
        return Platform.entries.filter { resolve(it) == null }
    }
}

object PlatformIdSerializer : KSerializer<PlatformId> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("PlatformId", SerialKind.CONTEXTUAL)

    override fun deserialize(decoder: Decoder): PlatformId {
        val node = (decoder as YamlInput).node
        return when (node) {
            is YamlScalar -> PlatformId(shared = node.content)
            is YamlMap -> {
                val unknown = node.entries.keys.map { it.content }.filter { it !in PLATFORM_KEYS }
                require(unknown.isEmpty()) {
                    "unknown key '${unknown.first()}' in an id at ${node.path.toHumanReadableString()}; use android, ios and web"
                }
                require(node.entries.isNotEmpty()) {
                    "an id needs android, ios or web at ${node.path.toHumanReadableString()}"
                }
                PlatformId(
                    android = (node.get<YamlScalar>("android"))?.content,
                    ios = (node.get<YamlScalar>("ios"))?.content,
                    web = (node.get<YamlScalar>("web"))?.content,
                )
            }
            else -> error("id must be a string or { android, ios, web } at ${node.path.toHumanReadableString()}")
        }
    }

    override fun serialize(encoder: Encoder, value: PlatformId) {
        encoder.encodeString(value.shared ?: value.android ?: value.ios ?: value.web.orEmpty())
    }
}

/** The platforms a step or an action is limited to: `platform: android` or `platform: [android, web]`. */
@Serializable(with = PlatformScopeSerializer::class)
data class PlatformScope(val keys: List<String>) {
    init {
        require(keys.isNotEmpty()) { "platform needs at least one of ${PLATFORM_KEYS.joinToString()}" }
        keys.forEach { key ->
            require(key in PLATFORM_KEYS) { "platform must be ${PLATFORM_KEYS.joinToString(", ")}, was '$key'" }
        }
    }

    fun includes(platform: Platform): Boolean {
        return platform.key in keys
    }

    override fun toString(): String {
        return keys.joinToString(" and ")
    }
}

object PlatformScopeSerializer : KSerializer<PlatformScope> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("PlatformScope", SerialKind.CONTEXTUAL)

    override fun deserialize(decoder: Decoder): PlatformScope {
        val node = (decoder as YamlInput).node
        return when (node) {
            is YamlScalar -> PlatformScope(listOf(node.content))
            is YamlList ->
                PlatformScope(
                    node.items.map {
                        (it as? YamlScalar)?.content
                            ?: error("platform must list names at ${it.path.toHumanReadableString()}")
                    }
                )
            else -> error("platform must be a name or a list of names at ${node.path.toHumanReadableString()}")
        }
    }

    override fun serialize(encoder: Encoder, value: PlatformScope) {
        encoder.encodeString(value.keys.joinToString(","))
    }
}

@Serializable
data class SelectorSpec(
    val id: PlatformId? = null,
    val text: String? = null,
    val textContains: String? = null,
    /** The whole text matches this regular expression (case-insensitive). */
    val textMatches: String? = null,
    val action: String? = null,
    val within: PlatformId? = null,
    val index: Int = 0,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    /** Always use the system runner (permission alerts, browser) instead of the in-app agent; waits for the element. */
    val system: Boolean = false,
    /** Taps a spot given as `x%,y%` of the screen instead of an element; only `tap` knows it. */
    val point: String? = null,
    /** Only selected (true) or unselected (false) tabs, chips and segments. */
    val selected: Boolean? = null,
) {
    init {
        require(point == null || Points.parse(point) != null) { "point must be like '50%,95%', was '$point'" }
        if (point == null) {
            requireSelectorCriterion(id, text, textContains, textMatches, action)
        }
    }

    fun toSelector(platform: Platform): Selector {
        return Selector(
            id = id?.resolve(platform),
            text = text,
            textContains = textContains,
            textMatches = textMatches,
            action = action,
            within = within?.resolve(platform),
            index = index,
            enabled = enabled,
            checked = checked,
            selected = selected,
        )
    }
}

/**
 * `index`, `within`, `enabled`, `checked` and `selected` only narrow down; without one of these a selector would match
 * anything.
 */
internal fun requireSelectorCriterion(
    id: PlatformId?,
    text: String?,
    textContains: String?,
    textMatches: String?,
    action: String?,
) {
    require(id != null || text != null || textContains != null || textMatches != null || action != null) {
        "a selector needs id, text, textContains, textMatches or action; index, within, enabled, checked and selected only narrow it down"
    }
}

/** A spot on the screen written as `x%,y%`. */
object Points {
    fun parse(text: String): Pair<Double, Double>? {
        val parts = text.split(',').map { it.trim().removeSuffix("%").toDoubleOrNull() }
        if (parts.size != 2) return null
        val x = parts[0] ?: return null
        val y = parts[1] ?: return null
        if (x !in 0.0..100.0 || y !in 0.0..100.0) return null
        return x / 100 to y / 100
    }
}

@Serializable
data class TypeSpec(
    val id: PlatformId? = null,
    val text: String? = null,
    val textContains: String? = null,
    val textMatches: String? = null,
    val action: String? = null,
    val within: PlatformId? = null,
    val index: Int = 0,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val value: String,
) {
    init {
        requireSelectorCriterion(id, text, textContains, textMatches, action)
    }

    fun selector(platform: Platform): Selector {
        return SelectorSpec(
                id,
                text,
                textContains,
                textMatches,
                action,
                within,
                index,
                enabled,
                checked,
                selected = selected,
            )
            .toSelector(platform)
    }
}

/**
 * Scrolls the container of the element by [distance] dp (points on iOS). [direction] is where the wanted content is:
 * `down` reveals content below, `start` and `end` follow the layout direction, `left` and `right` are physical.
 */
@Serializable
data class ScrollSpec(
    val id: PlatformId? = null,
    val text: String? = null,
    val textContains: String? = null,
    val textMatches: String? = null,
    val action: String? = null,
    val within: PlatformId? = null,
    val index: Int = 0,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val direction: String = ScrollDirection.DOWN,
    val distance: Int = Command.DEFAULT_SCROLL_DP,
) {
    init {
        requireSelectorCriterion(id, text, textContains, textMatches, action)
        requireScrollDirection("scroll", direction)
        require(distance > 0) { "scroll.distance must be positive, was $distance" }
    }

    fun selector(platform: Platform): Selector {
        return SelectorSpec(
                id,
                text,
                textContains,
                textMatches,
                action,
                within,
                index,
                enabled,
                checked,
                selected = selected,
            )
            .toSelector(platform)
    }
}

internal fun requireScrollDirection(action: String, direction: String) {
    require(direction in ScrollDirection.all) {
        "$action.direction must be one of ${ScrollDirection.all.joinToString()}, was '$direction'"
    }
}

/**
 * Swipes the screen in [direction], or from the center of the element [from] in [direction] (to the screen edge, for
 * swipe-to-confirm sliders), or between two spots [start] and [end] written as `x%,y%`.
 */
private val SWIPE_DIRECTIONS = listOf("up", "down", "left", "right")

@Serializable
data class SwipeSpec(
    val direction: String? = null,
    val from: SelectorSpec? = null,
    val start: String? = null,
    val end: String? = null,
    /** How long the finger takes; sliders that confirm a purchase need a slower swipe than a list. */
    val durationMs: Long? = null,
) {
    init {
        val byPoints = start != null || end != null
        require(byPoints != (direction != null)) { "swipe needs either direction or start and end" }
        require(direction == null || direction in SWIPE_DIRECTIONS) {
            "swipe.direction is where the finger goes: ${SWIPE_DIRECTIONS.joinToString()}, was '$direction'"
        }
        require(!byPoints || (start != null && end != null && from == null)) {
            "swipe by points needs both start and end and no from"
        }
        require(start == null || Points.parse(start) != null) { "swipe.start must be like '50%,80%', was '$start'" }
        require(end == null || Points.parse(end) != null) { "swipe.end must be like '50%,20%', was '$end'" }
    }
}

/**
 * Scrolls until the element is completely visible. [direction] is where the wanted content is (`down` shows content
 * below; `start` and `end` follow the layout direction). Without [container] the whole screen is swiped, with it only
 * that element (a carousel, a nested list). It gives up after [maxScrolls] swipes or when a swipe changes nothing.
 */
@Serializable
data class ScrollUntilSpec(
    val id: PlatformId? = null,
    val text: String? = null,
    val textContains: String? = null,
    val textMatches: String? = null,
    val action: String? = null,
    val within: PlatformId? = null,
    val index: Int = 0,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val direction: String = ScrollDirection.DOWN,
    val maxScrolls: Int = 10,
    @SerialName("in") val container: SelectorSpec? = null,
) {
    init {
        requireSelectorCriterion(id, text, textContains, textMatches, action)
        requireScrollDirection("scrollUntilVisible", direction)
        require(maxScrolls >= 1) { "scrollUntilVisible.maxScrolls must be at least 1, was $maxScrolls" }
    }

    fun selector(platform: Platform): Selector {
        return SelectorSpec(
                id,
                text,
                textContains,
                textMatches,
                action,
                within,
                index,
                enabled,
                checked,
                selected = selected,
            )
            .toSelector(platform)
    }
}

/** A long press: the element is held for [holdMs]. [system] sends it to the system runner like `tap`. */
@Serializable
data class LongPressSpec(
    val id: PlatformId? = null,
    val text: String? = null,
    val textContains: String? = null,
    val textMatches: String? = null,
    val action: String? = null,
    val within: PlatformId? = null,
    val index: Int = 0,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val holdMs: Long = Command.DEFAULT_HOLD_MS,
    val system: Boolean = false,
) {
    init {
        requireSelectorCriterion(id, text, textContains, textMatches, action)
        require(holdMs > 0) { "longPress.holdMs must be positive, was $holdMs" }
    }

    fun toSelector(platform: Platform): Selector {
        return SelectorSpec(
                id,
                text,
                textContains,
                textMatches,
                action,
                within,
                index,
                enabled,
                checked,
                selected = selected,
            )
            .toSelector(platform)
    }
}

/** Starts the app (again); [arguments] are given to it: Android intent extras, iOS launch arguments. */
@Serializable
data class LaunchSpec(val clearState: Boolean = false, val arguments: Map<String, String> = emptyMap()) {
    init {
        arguments.keys.forEach { key ->
            require(Regex("[A-Za-z][A-Za-z0-9_.]*").matches(key)) {
                "launchApp.arguments: '$key' must be letters, digits, _ or ."
            }
        }
    }
}

@Serializable data class LocationSpec(val latitude: Double, val longitude: Double)

/**
 * Answers the requests that match [url] (a glob over the whole URL, `*` is any text), [method] and [bodyContains] with
 * the content of the mock [file] (from `mocksDir`) instead of asking the backend. Without a file the body is empty.
 */
@Serializable
data class MockSpec(
    val url: String,
    val method: String? = null,
    val bodyContains: String? = null,
    val file: String? = null,
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val delayMs: Long = 0,
) {
    init {
        require(url.isNotBlank()) { "mock.url must not be empty" }
        require(method == null || method.uppercase() in MOCK_METHODS) {
            "mock.method must be one of ${MOCK_METHODS.joinToString()}, was '$method'"
        }
        require(bodyContains == null || bodyContains.isNotEmpty()) { "mock.bodyContains must not be empty" }
        require(file == null || file.isNotBlank()) { "mock.file must not be empty" }
        require(file == null || isInsideFolder(file)) { "mock.file '$file' must stay inside the mocks folder" }
        require(status in 100..599) { "mock.status must be between 100 and 599, was $status" }
        require(delayMs in 0..MAX_MOCK_DELAY_MS) {
            "mock.delayMs must be between 0 and $MAX_MOCK_DELAY_MS, was $delayMs"
        }
        for ((name, value) in headers) {
            require(name.isNotEmpty() && name.all { it.isHeaderNameChar() }) {
                "mock.headers: invalid header name '$name': a header name is letters, digits and $HEADER_NAME_SYMBOLS only"
            }
            require(value.all { it == '\t' || it in ' '..'~' }) {
                "mock.headers: the value of '$name' may only have printable ASCII characters, no line breaks or umlauts"
            }
        }
    }

    /** A character a header name may have (RFC 9110 token): the HTTP clients refuse a response with any other. */
    private fun Char.isHeaderNameChar(): Boolean {
        return this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in HEADER_NAME_SYMBOLS
    }

    private fun isInsideFolder(path: String): Boolean {
        return !path.startsWith("/") && path.split('/', '\\').none { it == ".." }
    }
}

private val MOCK_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private const val MAX_MOCK_DELAY_MS = 120_000L

private const val HEADER_NAME_SYMBOLS = "!#\$%&'*+-.^_`|~"

/** A check of the screen: exactly one of [visible] and [notVisible]. */
@Serializable
data class Condition(val visible: SelectorSpec? = null, val notVisible: SelectorSpec? = null) {
    init {
        require((visible == null) != (notVisible == null)) { "a condition needs exactly one of visible and notVisible" }
    }
}

/**
 * Runs [steps] [times] times in a row, or as long as the [whileCondition] holds (at most [max] rounds; a condition that
 * still holds after that fails the step).
 */
@Serializable
data class RepeatSpec(
    val times: Int? = null,
    @SerialName("while") val whileCondition: Condition? = null,
    val max: Int = 10,
    val steps: List<Step>,
) {
    init {
        require((times == null) != (whileCondition == null)) { "repeat needs exactly one of times and while" }
        require(times == null || times >= 1) { "repeat.times must be at least 1, was $times" }
        require(max >= 1) { "repeat.max must be at least 1, was $max" }
    }
}

/**
 * Reads the text of [from] into the variable [name] for later steps (`${var.name}`). With [match] the variable holds
 * the first group of that regular expression, or the whole match when it has no group.
 */
@Serializable
data class CopyTextSpec(val from: SelectorSpec, val match: String? = null, @SerialName("as") val name: String) {
    init {
        require(Regex("[A-Za-z0-9_]+").matches(name)) { "copyText.as must be letters, digits or _, was '$name'" }
    }
}

@Serializable
data class Action(
    val tap: SelectorSpec? = null,
    val type: TypeSpec? = null,
    val scroll: ScrollSpec? = null,
    val swipe: SwipeSpec? = null,
    val assertVisible: SelectorSpec? = null,
    val assertNotVisible: SelectorSpec? = null,
    val wait: Long? = null,
    val pressKey: String? = null,
    val openLink: String? = null,
    val use: String? = null,
    val with: Map<String, String> = emptyMap(),
    /** Two quick touches on the element. */
    val doubleTap: SelectorSpec? = null,
    /** Holds the element pressed, for context menus and drag handles. */
    val longPress: LongPressSpec? = null,
    /** Swipes until the element shows up, for lists longer than the screen. */
    val scrollUntilVisible: ScrollUntilSpec? = null,
    /** Clears a text field. */
    val eraseText: SelectorSpec? = null,
    /** Presses the system back button (Android). */
    val back: Boolean? = null,
    val repeat: RepeatSpec? = null,
    /** Starts the app (again), optionally from cleared data. */
    val launchApp: LaunchSpec? = null,
    /** Quits the app, keeping its data. */
    val stopApp: Boolean? = null,
    /** Clears the app data and quits the app; follow it with `launchApp`. */
    val clearState: Boolean? = null,
    /** Saves a screenshot as `<name>.png` into the results of the test. */
    val takeScreenshot: String? = null,
    /** Waits until the screen stopped changing (no fixed pause). */
    val waitForAnimationToEnd: Boolean? = null,
    /** Switches the device to dark or light mode; restored when the test ends. */
    val setDarkMode: Boolean? = null,
    /** Switches airplane mode (Android); restored when the test ends. */
    val setAirplaneMode: Boolean? = null,
    /** Rotates the device to `portrait` or `landscape`; restored when the test ends. */
    val setOrientation: String? = null,
    /** Sets the GPS location (Android emulators, iOS simulators). */
    val setLocation: LocationSpec? = null,
    /** Keeps the text of an element in a variable: `copyText: { from: { id: x }, match: "\\d+", as: price }`. */
    val copyText: CopyTextSpec? = null,
    /** Closes the on-screen keyboard if it is open. */
    val hideKeyboard: Boolean? = null,
    /** Starts another app by its package name, e.g. the calendar (Android). */
    val launchPackage: String? = null,
    /** Force-stops another app by its package name, e.g. the browser (Android). */
    val stopPackage: String? = null,
    /** Grants or revokes permissions: `{ location: allow, notifications: deny }`. */
    val setPermissions: Map<String, String> = emptyMap(),
    /** Answers matching network requests from a file instead of the backend; undone when the test ends. */
    val mock: MockSpec? = null,
    /** Removes every network mock again. */
    val clearMocks: Boolean? = null,
    /**
     * Only on this platform, or these (android, ios, web). A `do` for another platform skips the step, an `until` for
     * another platform is dropped, so one step can differ per platform without being written twice.
     */
    val platform: PlatformScope? = null,
) {
    init {
        require(with.isEmpty() || use != null) { "with: only goes with use:" }
        mapOf(
                "back" to back,
                "stopApp" to stopApp,
                "clearState" to clearState,
                "waitForAnimationToEnd" to waitForAnimationToEnd,
                "hideKeyboard" to hideKeyboard,
                "clearMocks" to clearMocks,
            )
            .forEach { (name, value) -> require(value != false) { "$name: false is not an action; remove the step" } }
        require(setOrientation == null || setOrientation in ORIENTATIONS) {
            "setOrientation must be portrait or landscape, was '$setOrientation'"
        }
        val chosen = names()
        require(chosen.isNotEmpty()) { "a step needs an action, for example do: { tap: { id: Login.Button } }" }
        require(chosen.size == 1) {
            "do holds exactly one action but has ${chosen.size} (${chosen.joinToString()}); write one step per action"
        }
    }

    /** The actions this `do` or `until` has set; a valid one has exactly one. */
    fun names(): List<String> {
        return listOfNotNull(
            "tap".takeIf { tap != null },
            "type".takeIf { type != null },
            "scroll".takeIf { scroll != null },
            "swipe".takeIf { swipe != null },
            "assertVisible".takeIf { assertVisible != null },
            "assertNotVisible".takeIf { assertNotVisible != null },
            "wait".takeIf { wait != null },
            "pressKey".takeIf { pressKey != null },
            "openLink".takeIf { openLink != null },
            "use".takeIf { use != null },
            "doubleTap".takeIf { doubleTap != null },
            "longPress".takeIf { longPress != null },
            "scrollUntilVisible".takeIf { scrollUntilVisible != null },
            "eraseText".takeIf { eraseText != null },
            "back".takeIf { back != null },
            "repeat".takeIf { repeat != null },
            "launchApp".takeIf { launchApp != null },
            "stopApp".takeIf { stopApp != null },
            "clearState".takeIf { clearState != null },
            "takeScreenshot".takeIf { takeScreenshot != null },
            "waitForAnimationToEnd".takeIf { waitForAnimationToEnd != null },
            "setDarkMode".takeIf { setDarkMode != null },
            "setAirplaneMode".takeIf { setAirplaneMode != null },
            "setOrientation".takeIf { setOrientation != null },
            "setLocation".takeIf { setLocation != null },
            "copyText".takeIf { copyText != null },
            "hideKeyboard".takeIf { hideKeyboard != null },
            "launchPackage".takeIf { launchPackage != null },
            "stopPackage".takeIf { stopPackage != null },
            "setPermissions".takeIf { setPermissions.isNotEmpty() },
            "mock".takeIf { mock != null },
            "clearMocks".takeIf { clearMocks != null },
        )
    }

    /** Every element id this action refers to, shared or per platform. */
    fun platformIds(): List<PlatformId> {
        val selectors =
            listOfNotNull(
                tap?.let { listOfNotNull(it.id, it.within) },
                type?.let { listOfNotNull(it.id, it.within) },
                scroll?.let { listOfNotNull(it.id, it.within) },
                swipe?.from?.let { listOfNotNull(it.id, it.within) },
                assertVisible?.let { listOfNotNull(it.id, it.within) },
                assertNotVisible?.let { listOfNotNull(it.id, it.within) },
                doubleTap?.let { listOfNotNull(it.id, it.within) },
                longPress?.let { listOfNotNull(it.id, it.within) },
                scrollUntilVisible?.let {
                    listOfNotNull(it.id, it.within) + listOfNotNull(it.container?.id, it.container?.within)
                },
                eraseText?.let { listOfNotNull(it.id, it.within) },
                copyText?.from?.let { listOfNotNull(it.id, it.within) },
            )
        return selectors.flatten()
    }

    fun runsOn(current: Platform): Boolean {
        return platform == null || platform.includes(current)
    }
}

private val ORIENTATIONS = setOf("portrait", "landscape")

@Serializable
data class Step(
    val say: String? = null,
    @SerialName("do") val action: Action,
    val timeout: Long? = null,
    /** Run this step only on the given platform or platforms (android, ios, web); for UI that differs per platform. */
    val platform: PlatformScope? = null,
    /** State the step must reach, e.g. the next screen; if it is not reached the action is repeated. */
    val until: Action? = null,
    /** Total tries of `do` + `until`; for UI that swallows input while content reloads. */
    val attempts: Int = 1,
    /** A step that finds nothing to act on is skipped instead of failing, e.g. a banner that is not always there. */
    val optional: Boolean = false,
    /** The step runs only when the screen shows (or does not show) this, e.g. a tooltip. */
    @SerialName("if") val condition: Condition? = null,
) {
    init {
        require(!optional || (action.use == null && action.repeat == null)) {
            "optional works on a single action; put the flow behind an `if` instead"
        }
        val mocking = action.mock != null || action.clearMocks != null
        require(!mocking || (until == null && !optional && timeout == null)) {
            "until, optional and timeout do not work with mock and clearMocks; they change what the app is told, not what it shows"
        }
        require(attempts >= 1) { "attempts must be at least 1, was $attempts" }
        require(attempts == 1 || until != null) { "attempts needs an until: the state the step has to reach" }
        val container = action.use != null || action.repeat != null
        require(!container || until == null) {
            "until does not work with use or repeat; put it on a step inside the flow"
        }
        require(!container || timeout == null) {
            "timeout does not work with use or repeat; put it on a step inside the flow"
        }
        // Web is only checked for projects that test a web app, see requireIdsFor
        Platform.entries.filter { it != Platform.Web }.forEach { requireIdsFor(it) }
    }

    /**
     * `{ id: { android: a } }` cannot be resolved on iOS: such a step has to say that it only runs on Android. Checked
     * for Android and iOS when a step is read and for web when `app.web` is set.
     */
    fun requireIdsFor(current: Platform) {
        for (part in listOfNotNull(action, until)) {
            if (!runsOn(current) || !part.runsOn(current)) continue
            val gap = part.platformIds().firstOrNull { it.resolve(current) == null } ?: continue
            error(
                "an id has no value for ${current.key} (${gap.shared ?: gap.android ?: gap.ios ?: gap.web}); add ${current.key}: or limit the step with platform:"
            )
        }
    }

    fun runsOn(current: Platform): Boolean {
        return platform == null || platform.includes(current)
    }
}

/** Device state a test needs, applied before the app starts and restored afterwards. */
@Serializable
data class DeviceSettings(
    /** App language as a BCP 47 tag, e.g. `de-DE` or `en-US`. */
    val language: String? = null,
    /** `12h` or `24h`; decides whether time pickers take 20 or 8 PM. */
    val timeFormat: String? = null,
) {
    init {
        require(timeFormat == null || timeFormat in setOf("12h", "24h")) { "device.timeFormat must be 12h or 24h" }
    }

    fun overriddenBy(other: DeviceSettings): DeviceSettings {
        return DeviceSettings(other.language ?: language, other.timeFormat ?: timeFormat)
    }

    val isEmpty: Boolean
        get() = language == null && timeFormat == null
}

@Serializable
data class TestFile(
    val name: String,
    val tags: List<String> = emptyList(),
    val uses: List<String> = emptyList(),
    val timeout: Long? = null,
    /** Overrides the `device:` defaults of gekko.yaml for this test. */
    val device: DeviceSettings = DeviceSettings(),
    val steps: List<Step>,
)

/**
 * `group.yaml` in a folder of tests: the tests below it form a group that shares one login (or any other start state).
 * The `setup` flows run once before the first test of the group, in a fresh start; the tests then run one after the
 * other without clearing the app data (a browser keeps its context), and the `teardown` flows run once after the last
 * one. When the setup fails the tests of the group are skipped, not run and failed one by one.
 */
@Serializable
data class GroupFile(
    val name: String? = null,
    /**
     * The platforms the group runs on, `web` or `[android, web]`; left out, it runs everywhere. On another platform the
     * group is not part of the run at all (no setup, no tests), so its tests and flows need no `platform:` of their
     * own.
     */
    val platform: PlatformScope? = null,
    val setup: List<String>,
    val teardown: List<String> = emptyList(),
) {
    init {
        require(setup.isNotEmpty()) { "group.yaml needs a setup: a list of flows, for example [account-login-sign-in]" }
    }
}

@Serializable
data class FlowFile(
    val name: String? = null,
    val params: List<String> = emptyList(),
    val steps: List<Step>,
)

@Serializable
data class AppIds(
    val android: String? = null,
    val ios: String? = null,
    /** Fully qualified launcher activity; needed when several launcher activities exist (e.g. LeakCanary). */
    val androidActivity: String? = null,
    /** The web app under test: an http(s) URL, or an HTML file or directory that gekko serves on 127.0.0.1. */
    val web: String? = null,
)

@Serializable
data class AndroidConfig(
    val permissions: List<String> = emptyList(),
    val runnerApk: String? = null,
    /** The app under test as an `.apk`, installed by `gekko install` and `gekko run --install`. */
    val apk: String? = null,
    /**
     * Other apps that are force-stopped before every test and when it ends, e.g. the browser that shows a login page: a
     * failed test must not leave it in front of the next one.
     */
    val stopApps: List<String> = emptyList(),
)

@Serializable
data class IosConfig(
    /** simctl privacy services to grant, e.g. location, photos, camera. */
    val permissions: List<String> = emptyList(),
    /**
     * `GekkoAgent.framework` built for the simulator (`gekko-ios-agent`). The CLI injects it into the app at launch
     * with DYLD_INSERT_LIBRARIES, so the app project needs no changes and no agent ships in any app build.
     */
    val agentFramework: String? = null,
    /**
     * The app under test for simulators: an `.app` directory or a `.zip` that contains one (e.g. the Xcode build
     * products archive of a CI build). Installed by `gekko install` and `gekko run --install`.
     */
    val app: String? = null,
    /**
     * Directory containing the built system runner (`gekko-ios-runner`): GekkoRunner.xctestrun + Debug-iphonesimulator.
     */
    val runnerDir: String? = null,
)

@Serializable
data class WebConfig(
    /** The browser engine for `--platform web` without `-s`: chromium, firefox or webkit. */
    val browser: String = "chromium",
    /**
     * `fresh` (default) starts every test in a new browser context. `shared` keeps one context for all tests of a run
     * (cookies, storage and a login stay in memory, nothing is written to disk): log in once, tests depend on their
     * order. `GEKKO_WEB_SESSION` overrides it.
     */
    val session: String = WEB_SESSION_FRESH,
    /** The attributes that name an element, the first one present wins; the HTML `id` is not used. */
    val idAttributes: List<String> = listOf("data-testid", "flt-semantics-identifier"),
    /** Size of the browser window in CSS pixels, `1280x800`. */
    val viewport: String = "1280x800",
    /** A Playwright device name (`iPhone 15`) to emulate instead of a plain desktop browser. */
    val device: String? = null,
    /** False shows the browser window. */
    val headless: Boolean = true,
    /** Permissions granted before every fresh start: geolocation, notifications, camera, ... */
    val permissions: List<String> = emptyList(),
    /** A directory with Playwright in node_modules; `installed` (default) uses the one gekko installed. */
    val playwright: String? = null,
) {
    init {
        require(browser in WEB_BROWSERS) { "web.browser must be one of ${WEB_BROWSERS.joinToString()}, was '$browser'" }
        require(session in WEB_SESSIONS) { "web.session must be one of ${WEB_SESSIONS.joinToString()}, was '$session'" }
        require(idAttributes.isNotEmpty()) { "web.idAttributes needs at least one attribute" }
        require(Regex("\\d+x\\d+").matches(viewport)) { "web.viewport must look like 1280x800, was '$viewport'" }
    }
}

/** The engines Playwright drives. */
val WEB_BROWSERS = listOf("chromium", "firefox", "webkit")

const val WEB_SESSION_FRESH = "fresh"
const val WEB_SESSION_SHARED = "shared"

val WEB_SESSIONS = listOf(WEB_SESSION_FRESH, WEB_SESSION_SHARED)

@Serializable
data class GekkoConfig(
    val app: AppIds = AppIds(),
    val android: AndroidConfig = AndroidConfig(),
    val ios: IosConfig = IosConfig(),
    val web: WebConfig = WebConfig(),
    /** Language and time format for every test, e.g. `{ language: de-DE, timeFormat: 24h }`. */
    val device: DeviceSettings = DeviceSettings(),
    val defaultTimeoutMs: Long = 10_000,
    /**
     * A screen counts as done when nothing changed for this long and no progress indicator is shown. The timeout only
     * applies while the screen is still loading; once it is done a missing element fails (or heals) at once. 0 always
     * waits the full timeout.
     */
    val settleMs: Long = 2_500,
    /**
     * How long an `optional` step or an `if` / `repeat while` condition looks for its element before it counts as not
     * there. Short on purpose: the element is either on screen already or it is not coming.
     */
    val optionalTimeoutMs: Long = 500,
    /** Minimum pause between taps. Off by default; set it for apps that throttle clicks (e.g. a 500 ms debounce). */
    val tapIntervalMs: Long = 0,
    val testsDir: String = "tests",
    val flowsDir: String = "flows",
    /** The folder of the files that `mock` steps answer with. */
    val mocksDir: String = "mocks",
    /**
     * The environment variables tests may read with `${env.NAME}` (test accounts, passwords). Values come from the
     * process environment or a `.env` file next to this file and are masked in everything gekko prints or writes.
     */
    val env: List<String> = emptyList(),
)

private fun String.relativeTo(root: String): String {
    return if (startsWith("/") || this == InstalledRunners.INSTALLED) this else "$root/$this"
}

/** Paths handed to other processes (e.g. DYLD_INSERT_LIBRARIES) must not depend on that process's working directory. */
fun absolutePath(path: String, currentDirectory: String): String {
    if (path.startsWith("/")) return path
    val relative = path.removePrefix("./").trimEnd('/')
    return if (relative.isEmpty() || relative == ".") currentDirectory else "$currentDirectory/$relative"
}

/**
 * `app.web` may name a variable, `https://app-${env.STAGE}.example.com/`, so one project serves every environment; it
 * follows the rules of `env:` (declared names only, from the process environment or `.env`).
 */
fun GekkoConfig.withEnvInWebApp(access: EnvAccess): GekkoConfig {
    val web = app.web ?: return this
    if (!web.contains("\${env.")) return this
    return copy(app = app.copy(web = Yamls.substitute(web, emptyMap(), access)))
}

fun GekkoConfig.withPathsRelativeTo(root: String): GekkoConfig {
    return copy(
        // A URL stays as it is, a file or directory is looked up from the project
        app = app.copy(web = app.web?.let { if (it.contains("://")) it else it.relativeTo(root) }),
        web = web.copy(playwright = web.playwright?.relativeTo(root)),
        android = android.copy(runnerApk = android.runnerApk?.relativeTo(root), apk = android.apk?.relativeTo(root)),
        ios =
            ios.copy(
                app = ios.app?.relativeTo(root),
                runnerDir = ios.runnerDir?.relativeTo(root),
                agentFramework = ios.agentFramework?.relativeTo(root),
            ),
    )
}

object Yamls {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    fun <T> parse(serializer: kotlinx.serialization.DeserializationStrategy<T>, text: String): T {
        try {
            return yaml.decodeFromString(serializer, text)
        } catch (error: YamlException) {
            // A key the format does not know is reported with a way out; every other problem keeps its own message
            val unknown =
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<UnknownPropertyException>()
                    .firstOrNull() ?: throw error
            val renamed = RENAMED_KEYS[unknown.propertyName]
            val hint =
                renamed?.let { "it was renamed to '$it'" }
                    ?: closest(unknown.propertyName, unknown.validPropertyNames)?.let { "did you mean '$it'?" }
                    ?: "valid keys here: ${unknown.validPropertyNames.sorted().joinToString()}"
            throw IllegalArgumentException(
                "Unknown key '${unknown.propertyName}' at line ${unknown.line}, column ${unknown.column}: $hint",
                error,
            )
        }
    }

    private val RENAMED_KEYS =
        mapOf(
            "doubleTapOn" to "doubleTap",
            "longPressOn" to "longPress",
            "dy" to "direction and distance (scroll: { id: list, direction: down, distance: 400 })",
        )

    /** The valid key that differs from [name] by at most two edits, for a typo like `tapp`. */
    private fun closest(name: String, valid: Set<String>): String? {
        return valid
            .map { it to editDistance(name.lowercase(), it.lowercase()) }
            .filter { it.second <= 2 }
            .minByOrNull { it.second }
            ?.first
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + substitution)
            }
            previous = current
        }
        return previous[b.length]
    }

    /**
     * Values go into YAML text, where placeholders sit in double quotes: backslashes (regexes) and quotes stay literal.
     */
    private fun quoted(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    /**
     * Replaces `${key}` with params and `${env.KEY}` with a declared environment variable; `${var.x}`, `${date(...)}`
     * and `${random.x}` stay for the moment the step runs. Comment lines are left alone.
     */
    fun substitute(text: String, params: Map<String, String>, access: EnvAccess = EnvAccess.NONE): String {
        val pattern = Regex("""\$\{([A-Za-z0-9_.]+)}""")
        return text.lines().joinToString("\n") { line ->
            if (line.trimStart().startsWith("#")) {
                line
            } else {
                pattern.replace(line) { match ->
                    val key = match.groupValues[1]
                    when {
                        // Runtime variables are filled in while the test runs
                        key.startsWith("var.") || key.startsWith("random.") -> match.value
                        key.startsWith("env.") -> quoted(access.value(key.removePrefix("env.")))
                        else -> params[key]?.let(::quoted) ?: error("Missing parameter '$key'")
                    }
                }
            }
        }
    }
}
