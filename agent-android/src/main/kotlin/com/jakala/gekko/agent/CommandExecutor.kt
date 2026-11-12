package com.jakala.gekko.agent

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.jakala.gekko.device.ElementLookup
import com.jakala.gekko.device.Frame
import com.jakala.gekko.device.Gestures
import com.jakala.gekko.device.Scrolling
import com.jakala.gekko.device.TouchPoint
import com.jakala.gekko.device.Waiting
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.GekkoVersion
import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.SettleTracker
import com.jakala.gekko.protocol.UiNode

internal class CommandExecutor(private val application: Application, private val tracker: ActivityTracker) {
    private val reader = TreeReader { WindowRoots.all() }
    private val idle = IdleWaiter { snapshotNodes() }

    fun execute(command: Command): Response {
        // The app is not in front (permission dialog, browser): the CLI asks the system runner instead
        if (!command.isServedInBackground() && !isForeground()) {
            return Response.NotForeground
        }
        return when (command) {
            Command.Hello -> hello()
            Command.Metrics -> metrics()
            Command.Tree -> Response.TreeResult(snapshotNodes())
            Command.Foreground -> Response.ForegroundResult(isForeground())
            Command.Screenshot -> unsupported("Screenshot is handled by the host CLI")
            is Command.SetMocks -> setMocks(command)
            Command.ClearMocks -> {
                GekkoMocks.clear()
                Response.Ok
            }
            Command.MockHits -> Response.MockHitsResult(GekkoMocks.drainHits())
            is Command.SetOrientation -> unsupported("Rotating the device is served by the system runner")
            Command.HideKeyboard -> unsupported("The CLI closes the keyboard over adb")
            is Command.SetLocation,
            Command.ClearLocation -> unsupported("Mock locations are served by the system runner")
            is Command.Wheel -> unsupported("A mouse wheel only exists in a browser")
            is Command.WaitIdle -> waitIdle(command.timeoutMs)
            is Command.WaitFor -> waitFor(command)
            is Command.Tap -> tap(command)
            is Command.TapAt -> touchAt(command.x, command.y, TAP_MS)
            is Command.LongPressAt -> touchAt(command.x, command.y, command.holdMs)
            is Command.DoubleTap -> doubleTap(command)
            is Command.LongPress -> longPress(command)
            is Command.Type -> type(command)
            is Command.TypeFocused -> typeFocused(command)
            is Command.PressKey -> pressKey(command)
            is Command.Scroll -> scroll(command)
            is Command.Swipe -> swipe(command)
            is Command.Drag -> drag(command)
        }
    }

    private fun hello(): Response {
        val commands = Command.names() - UNSUPPORTED_COMMANDS
        return Response.HelloResult(Protocol.VERSION, GekkoVersion.NAME, Protocol.ROLE_AGENT, commands)
    }

    /** Hello and metrics are facts about the agent and the display, and the CLI asks them before anything else. */
    private fun Command.isServedInBackground(): Boolean {
        // Mocks are state of the app, not something on its screen
        return this == Command.Foreground ||
            this == Command.Hello ||
            this == Command.Metrics ||
            this is Command.SetOrientation ||
            this is Command.SetMocks ||
            this == Command.ClearMocks ||
            this == Command.MockHits
    }

    private fun setMocks(command: Command.SetMocks): Response {
        return try {
            GekkoMocks.set(command.rules)
            Response.Ok
        } catch (error: Exception) {
            Response.Error("A network mock cannot be used: ${error.message}", ErrorKind.UNSUPPORTED)
        }
    }

    private fun unsupported(message: String): Response {
        return Response.Error(message, ErrorKind.UNSUPPORTED)
    }

    private fun metrics(): Response {
        val resources = (tracker.resumedActivity ?: application).resources
        return Response.MetricsResult(
            resources.displayMetrics.density,
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
    }

    private fun waitIdle(timeoutMs: Long): Response {
        return if (idle.await(timeoutMs)) {
            Response.Ok
        } else {
            Response.Error("App did not become idle in ${timeoutMs}ms", ErrorKind.TIMEOUT)
        }
    }

    private fun waitFor(command: Command.WaitFor): Response {
        return Waiting.visibility(command, now = { SystemClock.uptimeMillis() }, pause = { Thread.sleep(it) }) {
            MainThread.run {
                val snapshot = reader.read()
                Frame(snapshot.windows, snapshot::clipOf)
            }
        }
    }

    private fun snapshotNodes(): List<UiNode> {
        return MainThread.run { reader.read().windows }
    }

    private fun isForeground(): Boolean {
        // Dialogs and popups of the app are separate windows that take the focus from the activity.
        return MainThread.run {
            tracker.resumedActivity != null && WindowRoots.all().any { it.hasWindowFocus() && it.isAttachedToWindow }
        }
    }

    private fun tap(command: Command.Tap): Response {
        pendingTouch = null
        val response = tapSemantic(command)
        val touch = pendingTouch
        if (response is Response.Ok && touch != null) {
            Thread.sleep(TAP_MS)
            MainThread.run { touchUp(touch) }
        }
        pendingTouch = null
        return response
    }

    /** A touch at screen coordinates, in the topmost window that contains them, released after [holdMs]. */
    private fun touchAt(x: Int, y: Int, holdMs: Long): Response {
        pendingTouch = null
        MainThread.run {
            val root = windowAt(x, y)
            if (root != null) {
                val location = IntArray(2).also { root.getLocationOnScreen(it) }
                press(root, (x - location[0]).toFloat(), (y - location[1]).toFloat())
            }
        }
        val touch = pendingTouch ?: return Response.Error("No window to touch at $x,$y")
        Thread.sleep(holdMs)
        MainThread.run { touchUp(touch) }
        pendingTouch = null
        return Response.Ok
    }

    private fun windowAt(x: Int, y: Int): View? {
        val windows = reader.windows()
        return windows.lastOrNull { window ->
            val location = IntArray(2).also { window.getLocationOnScreen(it) }
            x in location[0] until location[0] + window.width && y in location[1] until location[1] + window.height
        } ?: windows.lastOrNull()
    }

    /** Two quick touches at the same spot, like a finger; there is no accessibility action for it. */
    private fun doubleTap(command: Command.DoubleTap): Response {
        return gesture(command.selector, command.timeoutMs, command.settleMs, "Double tap") { first ->
            Thread.sleep(TAP_MS)
            MainThread.run { touchUp(first) }
            Thread.sleep(DOUBLE_TAP_GAP_MS)
            MainThread.run { press(first.root, first.x, first.y) }
            val second = pendingTouch
            Thread.sleep(TAP_MS)
            if (second != null) {
                MainThread.run { touchUp(second) }
            }
        }
    }

    private fun longPress(command: Command.LongPress): Response {
        return gesture(command.selector, command.timeoutMs, command.settleMs, "Long press") { touch ->
            Thread.sleep(command.holdMs)
            MainThread.run { touchUp(touch) }
        }
    }

    /** Presses the element on the main thread, then lets [finish] run off it so the app can react in between. */
    private fun gesture(
        selector: Selector,
        timeoutMs: Long,
        settleMs: Long,
        name: String,
        finish: (PendingTouch) -> Unit,
    ): Response {
        pendingTouch = null
        val response =
            withElement(selector, timeoutMs, settleMs) { element ->
                if (touchDown(element)) Response.Ok else rejected("$name was not handled by $selector")
            }
        val touch = pendingTouch
        if (response is Response.Ok && touch != null) {
            finish(touch)
        }
        pendingTouch = null
        return response
    }

    private fun tapSemantic(command: Command.Tap): Response {
        return withElement(command.selector, command.timeoutMs, command.settleMs) { element ->
            val performed = element.click?.invoke() ?: touchDown(element)
            if (performed) Response.Ok else rejected("Tap was not handled by ${command.selector}")
        }
    }

    private fun type(command: Command.Type): Response {
        return withElement(command.selector, command.timeoutMs, command.settleMs) { element ->
            val setter = element.setText ?: element.descendantWithText()?.setText
            if (setter?.invoke(command.text) == true) Response.Ok else rejected("Element is not editable")
        }
    }

    /** Waits for an element with the input focus, then types at its cursor and sees whether the field took it. */
    private fun typeFocused(command: Command.TypeFocused): Response {
        return withFocusedField(command.timeoutMs, needsText = command.text.isNotEmpty()) { focus ->
            typeInto(focus, command.text)
        }
    }

    /**
     * Waits up to [timeoutMs] for an element with the input focus (right after a tap it can still be on its way), then
     * runs [action] on it. Rejects when none has it by then: [needsText] tells whether an element that takes no text
     * counts as none.
     */
    private fun withFocusedField(timeoutMs: Long, needsText: Boolean, action: (Element) -> Response): Response {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        idle.await(minOf(timeoutMs, IDLE_WAIT_MS))
        var withoutText = false
        while (true) {
            val focus = MainThread.run { focusedElement() }
            if (focus != null && (focus.textInput != null || !needsText)) {
                return action(focus)
            }
            withoutText = withoutText || focus != null
            if (SystemClock.uptimeMillis() > deadline) {
                return rejected(
                    if (withoutText) "The focused element does not take text" else "Nothing has the input focus"
                )
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun pressKey(command: Command.PressKey): Response {
        val code = KeyPresses.fieldKeyCode(command.key)
        return when {
            code != null ->
                withFocusedField(command.timeoutMs, needsText = true) { focus ->
                    pressTimes(command.times, settle = false) { KeyPresses.press(focus.rootView, code) }
                }
            KeyPresses.isBack(command.key) -> pressBack(command)
            // The buttons of a TV remote too: they have to go through the input pipeline of the window to move the
            // focus, and only the system can inject there (an app needs a permission that only the instrumentation of
            // the system runner has); a key event handed to the root view would pass without moving anything
            else -> unsupported("The in-app agent cannot press '${command.key}'; the system runner can")
        }
    }

    /**
     * From Android 13 an app takes back through callbacks (predictive back) that a key event handed to its window does
     * not reach: the key is handled and nothing happens. The system runner presses the real key there.
     */
    private fun pressBack(command: Command.PressKey): Response {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return unsupported(
                "Back reaches an app through callbacks on Android 13 and newer; the system runner presses it"
            )
        }
        idle.await(minOf(command.timeoutMs, IDLE_WAIT_MS))
        return pressTimes(command.times, settle = true) {
            val window = MainThread.run {
                WindowRoots.all().lastOrNull { it.hasWindowFocus() && it.isAttachedToWindow }
            }
            window != null && KeyPresses.back(window)
        }
    }

    /**
     * Presses [times] times, letting the app settle between two presses when [settle] (a back can change the screen). A
     * press nothing took (the app left the front) answers `unsupported` before the first, so the CLI asks the system
     * runner, and `rejected` after some were delivered.
     */
    private fun pressTimes(times: Int, settle: Boolean, press: () -> Boolean): Response {
        repeat(times) { done ->
            if (done > 0 && settle) {
                idle.await(IDLE_WAIT_MS)
            }
            if (!press()) {
                return if (done == 0) {
                    unsupported("The app did not take the key; the system runner can press it")
                } else {
                    rejected("The app did not take press ${done + 1} of $times")
                }
            }
        }
        return Response.Ok
    }

    /** The deepest element that has the focus in the windows a person can reach. */
    private fun focusedElement(): Element? {
        return reader.read().flat.lastOrNull { it.toUiNode().focused }
    }

    private fun typeInto(focus: Element, text: String): Response {
        if (text.isEmpty()) {
            return Response.Ok
        }
        val input = focus.textInput ?: return rejected("The focused element does not take text")
        val before = MainThread.run { input.current() }
        if (!MainThread.run { input.insert(text) }) {
            return rejected("The focused element did not accept the text")
        }
        // A field that cannot be read cannot be checked: it got the text
        before ?: return Response.Ok
        val deadline = SystemClock.uptimeMillis() + CHANGE_WAIT_MS
        while (MainThread.run { input.current() } == before) {
            if (SystemClock.uptimeMillis() > deadline) {
                return rejected("The focused field did not take the text")
            }
            Thread.sleep(POLL_MS)
        }
        return Response.Ok
    }

    private fun rejected(message: String): Response {
        return Response.Error(message, ErrorKind.REJECTED)
    }

    private fun scroll(command: Command.Scroll): Response {
        if (Scrolling.reveal(command.direction, rtl = false) == null) {
            return unsupported("Unknown scroll direction: ${command.direction}")
        }
        return withElement(command.selector, command.timeoutMs, command.settleMs) { element ->
            val distance = command.distanceDp * element.rootView.resources.displayMetrics.density
            // The nearest container that scrolls along the wanted axis does it; start and end follow its layout
            val scrolled =
                generateSequence(element) { it.parent }
                    .any { container ->
                        val scroller = container.scrollBy
                        val reveal = Scrolling.reveal(command.direction, container.rtl)
                        scroller != null && reveal != null && scroller(reveal.x * distance, reveal.y * distance)
                    }
            if (scrolled) Response.Ok else rejected("Nothing scrollable at ${describe(element.node)}")
        }
    }

    private fun describe(node: UiNode): String {
        return node.id?.let { "id=$it" } ?: node.text?.let { "text=\"$it\"" } ?: "a ${node.role ?: "node"}"
    }

    private fun swipe(command: Command.Swipe): Response {
        val moves = Gestures.path(command.fromX, command.fromY, command.toX, command.toY, command.durationMs)
        return dispatchGesture(command.fromX, command.fromY, moves, liftAtMs = command.durationMs.coerceAtLeast(0))
    }

    /** The finger goes the touch slop beyond the end and rests there, so the content stops at the end. */
    private fun drag(command: Command.Drag): Response {
        val slop = MainThread.run {
            windowAt(command.fromX, command.fromY)?.let { ViewConfiguration.get(it.context).scaledTouchSlop }
        }
        if (slop == null) {
            return Response.Error("No window")
        }
        val moves = Gestures.dragPath(command.fromX, command.fromY, command.toX, command.toY, slop.toFloat())
        return dispatchGesture(
            command.fromX,
            command.fromY,
            moves,
            liftAtMs = moves.last().atMs + Gestures.DRAG_REST_MS,
        )
    }

    private class Attempt(val response: Response?, val missing: String)

    private fun withElement(
        selector: Selector,
        timeoutMs: Long,
        settleMs: Long,
        action: (Element) -> Response,
    ): Response {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        // A page with an endless animation (Lottie, progress spinner) never gets idle; the lookup below polls anyway,
        // so
        // the wait must not eat the step's whole timeout
        idle.await(minOf(timeoutMs, IDLE_WAIT_MS))
        val settle = SettleTracker(settleMs, SystemClock.uptimeMillis())
        while (true) {
            val attempt = MainThread.run {
                val snapshot = reader.read()
                settle.observe(
                    signature = snapshot.windows.fold(17) { hash, window -> 31 * hash + window.signature() },
                    busy = snapshot.windows.any { it.hasBusyNode() },
                    now = SystemClock.uptimeMillis(),
                )
                // Lists and pagers keep off-screen items in the tree; a person can only tap what is visible
                val lookup = ElementLookup.find(snapshot.windows, selector, snapshot::clipOf)
                val element = lookup.pick(selector.index)?.let { snapshot.elementOf(it) }
                Attempt(element?.let(action), lookup.missing)
            }
            attempt.response?.let {
                return it
            }
            val now = SystemClock.uptimeMillis()
            val settled = settle.isSettled(now)
            if (settled || now > deadline) {
                return Response.Error(
                    "Element not found: $selector" + if (settled) " (screen settled)" else "",
                    attempt.missing,
                    Waiting.settledOrNull(settleMs, settled),
                )
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun Element.descendantWithText(): Element? {
        return children.firstNotNullOfOrNull { child ->
            child.takeIf { it.setText != null } ?: child.descendantWithText()
        }
    }

    private class PendingTouch(val root: View, val x: Float, val y: Float, val downTime: Long)

    @Volatile private var pendingTouch: PendingTouch? = null

    /** Pressing happens on the main thread, releasing after a short pause like a finger does. */
    private fun touchDown(element: Element): Boolean {
        val location = IntArray(2).also { element.rootView.getLocationOnScreen(it) }
        val x = element.node.bounds.centerX.toFloat() - location[0]
        val y = element.node.bounds.centerY.toFloat() - location[1]
        return press(element.rootView, x, y)
    }

    private fun press(root: View, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val handled = root.dispatchTouchEvent(down)
        down.recycle()
        pendingTouch = PendingTouch(root, x, y, now)
        return handled
    }

    private fun touchUp(touch: PendingTouch) {
        val up =
            MotionEvent.obtain(touch.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, touch.x, touch.y, 0)
        touch.root.dispatchTouchEvent(up)
        up.recycle()
    }

    /**
     * One finger that goes down at the start, moves along [moves] and lifts [liftAtMs] after the touch down. The times
     * of the events are made up, so the gesture is over at once, and a velocity tracker still sees the pace.
     */
    private fun dispatchGesture(startX: Int, startY: Int, moves: List<TouchPoint>, liftAtMs: Long): Response {
        val root = MainThread.run { windowAt(startX, startY) } ?: return Response.Error("No window")
        MainThread.run {
            val location = IntArray(2).also { root.getLocationOnScreen(it) }
            val start = SystemClock.uptimeMillis()
            fun send(action: Int, time: Long, x: Float, y: Float) {
                val event = MotionEvent.obtain(start, time, action, x - location[0], y - location[1], 0)
                root.dispatchTouchEvent(event)
                event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, start, startX.toFloat(), startY.toFloat())
            for (move in moves) {
                send(MotionEvent.ACTION_MOVE, start + move.atMs, move.x, move.y)
            }
            val last = moves.last()
            send(MotionEvent.ACTION_UP, start + liftAtMs, last.x, last.y)
        }
        return Response.Ok
    }

    private companion object {
        /** The commands this server answers with `unsupported`: the others are listed in its answer to `hello`. */
        val UNSUPPORTED_COMMANDS =
            setOf("screenshot", "setOrientation", "hideKeyboard", "setLocation", "clearLocation", "wheel")

        const val POLL_MS = 16L
        const val IDLE_WAIT_MS = 500L
        const val TAP_MS = 60L
        const val CHANGE_WAIT_MS = 1_000L
        const val DOUBLE_TAP_GAP_MS = 80L
    }
}
