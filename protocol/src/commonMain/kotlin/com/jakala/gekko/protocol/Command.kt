package com.jakala.gekko.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementDescriptors

@Serializable
sealed interface Command {
    /** First request of a run: the server answers with its protocol number, see [Protocol]. */
    @Serializable @SerialName("hello") data object Hello : Command

    /** Display facts the CLI needs to turn dp and reading directions into pixels and finger movements. */
    @Serializable @SerialName("metrics") data object Metrics : Command

    @Serializable @SerialName("tree") data object Tree : Command

    @Serializable
    @SerialName("tap")
    data class Tap(
        val selector: Selector,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val settleMs: Long = 0,
    ) : Command

    @Serializable
    @SerialName("doubleTap")
    data class DoubleTap(
        val selector: Selector,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val settleMs: Long = 0,
    ) : Command

    /** Presses the element for [holdMs] before releasing it. */
    @Serializable
    @SerialName("longPress")
    data class LongPress(
        val selector: Selector,
        val holdMs: Long = DEFAULT_HOLD_MS,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val settleMs: Long = 0,
    ) : Command

    @Serializable
    @SerialName("type")
    data class Type(
        val selector: Selector,
        val text: String,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val settleMs: Long = 0,
    ) : Command

    /**
     * Types [text] into the element that has the input focus, at its cursor, the way a keyboard does: nothing is
     * selected or replaced first (a selection is replaced, as when a person types over it). Waits up to [timeoutMs] for
     * an element that takes text to have the focus (right after a tap the focus can still be on its way); answers
     * `error` with kind `rejected` when none has it by then (the message tells whether nothing had the focus or an
     * element that takes no text), and when the field did not change. Answers `ok` once the text of the field changed.
     * Use [Type] to replace a text.
     */
    @Serializable
    @SerialName("typeFocused")
    data class TypeFocused(val text: String, val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : Command

    /**
     * Presses [key] [times] times. [key] is one of [Keys.ENTER], [Keys.BACKSPACE] (erases to the left; the web's
     * forward `delete` is not this), [Keys.TAB], [Keys.BACK], [Keys.HOME], or an Android key code number (`"66"`). The
     * keys that go to a field (enter, backspace, tab) wait up to [timeoutMs] for an element that takes text to have the
     * focus and answer `error` with kind `rejected` when none has it by then. Answers `ok` once the presses are
     * delivered, and `error` with kind `unsupported` for a key the platform does not have (`back` on iOS, `home` inside
     * the app) or this server cannot press: the CLI then asks the system runner, which can press every key.
     */
    @Serializable
    @SerialName("pressKey")
    data class PressKey(val key: String, val times: Int = 1, val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : Command {
        init {
            require(times >= 1) { "pressKey.times must be at least 1, was $times" }
        }
    }

    /**
     * Scrolls the scrollable container of the element by [distanceDp]. [direction] is where the wanted content is (see
     * [ScrollDirection]): `down` reveals content below, `start` and `end` follow the layout direction of the container.
     */
    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val selector: Selector,
        val direction: String = ScrollDirection.DOWN,
        val distanceDp: Int = DEFAULT_SCROLL_DP,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val settleMs: Long = 0,
    ) : Command

    /** A touch at screen coordinates in pixels (points on iOS). */
    @Serializable @SerialName("tapAt") data class TapAt(val x: Int, val y: Int) : Command

    /** Closes the on-screen keyboard; the iOS agent serves it, on Android the CLI presses back over adb. */
    @Serializable @SerialName("hideKeyboard") data object HideKeyboard : Command

    /** Mocks the location of the device with test location providers; only the Android system runner serves it. */
    @Serializable
    @SerialName("setLocation")
    data class SetLocation(val latitude: Double, val longitude: Double) : Command

    /** Ends the mock location of [SetLocation]; only the Android system runner serves it. */
    @Serializable @SerialName("clearLocation") data object ClearLocation : Command

    /** Rotates the device; only the system runner serves it. */
    @Serializable @SerialName("setOrientation") data class SetOrientation(val landscape: Boolean) : Command

    /**
     * Presses at screen coordinates in pixels (points on iOS), holds still for [holdMs] and releases: a context menu on
     * a map, a chart or a canvas has no element to select.
     */
    @Serializable
    @SerialName("longPressAt")
    data class LongPressAt(val x: Int, val y: Int, val holdMs: Long = DEFAULT_HOLD_MS) : Command

    /**
     * A finger that goes from the start to the end in [durationMs]: a short one flings the content on, a long one
     * barely. Every server honours the duration, from 1.4.0 on.
     */
    @Serializable
    @SerialName("swipe")
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long = 200) : Command

    /**
     * Moves the content under the start to the end and lets go without momentum, so that nothing keeps scrolling:
     * reorder a list, move a slider, scroll by an exact distance. The answer `ok` comes when the finger is up.
     */
    @Serializable
    @SerialName("drag")
    data class Drag(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) : Command

    /**
     * A mouse-wheel turn at screen coordinates by [dx], [dy] pixels (positive scrolls right and down); only web serves
     * it.
     */
    @Serializable @SerialName("wheel") data class Wheel(val x: Int, val y: Int, val dx: Int, val dy: Int) : Command

    @Serializable @SerialName("waitIdle") data class WaitIdle(val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : Command

    /**
     * Waits until the element is on screen ([visible] true) or gone and answers `ok`; answers `error` with "Expected
     * visible: ..." / "Expected not visible: ..." when that did not happen in time. The agent does the polling, so one
     * request replaces a tree read over the wire every few milliseconds.
     */
    @Serializable
    @SerialName("waitFor")
    data class WaitFor(
        val selector: Selector,
        val visible: Boolean = true,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val settleMs: Long = 0,
    ) : Command

    @Serializable @SerialName("foreground") data object Foreground : Command

    @Serializable @SerialName("screenshot") data object Screenshot : Command

    /**
     * Replaces all network mocks of the app with [rules] (see [MockRule]); the agent answers matching requests itself
     * from then on. An empty list switches mocking off. Served by the in-app agent, also while the app is not in front.
     */
    @Serializable @SerialName("setMocks") data class SetMocks(val rules: List<MockRule>) : Command

    /** Removes all network mocks of the app. */
    @Serializable @SerialName("clearMocks") data object ClearMocks : Command

    /** The requests the mocks answered since the last time this was asked ([Response.MockHitsResult]). */
    @Serializable @SerialName("mockHits") data object MockHits : Command

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val DEFAULT_HOLD_MS = 700L
        const val DEFAULT_SCROLL_DP = 400

        /** The wire names of all commands: what `hello` lists of a server that serves every one of them. */
        @OptIn(ExperimentalSerializationApi::class)
        fun names(): List<String> {
            return serializer().descriptor.getElementDescriptor(1).elementDescriptors.map { it.serialName }
        }
    }
}

/** Where the wanted content is when a container scrolls; `start` and `end` follow the layout direction. */
object ScrollDirection {
    const val DOWN = "down"
    const val UP = "up"
    const val START = "start"
    const val END = "end"
    const val LEFT = "left"
    const val RIGHT = "right"

    val all = listOf(DOWN, UP, START, END, LEFT, RIGHT)
}

/** The named keys of [Command.PressKey]; a key code number (`"66"`) is the Android alternative. */
object Keys {
    const val ENTER = "enter"
    const val BACKSPACE = "backspace"
    const val TAB = "tab"
    const val BACK = "back"
    const val HOME = "home"

    /** The keys that go to the field that has the input focus. */
    val field = listOf(ENTER, BACKSPACE, TAB)

    val all = listOf(ENTER, BACKSPACE, TAB, BACK, HOME)
}
