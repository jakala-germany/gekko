package com.jakala.gekko.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

    @Serializable
    @SerialName("swipe")
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long = 200) : Command

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

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val DEFAULT_HOLD_MS = 700L
        const val DEFAULT_SCROLL_DP = 400
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
