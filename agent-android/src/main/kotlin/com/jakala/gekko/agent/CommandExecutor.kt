package com.jakala.gekko.agent

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.jakala.gekko.device.ElementLookup
import com.jakala.gekko.device.Frame
import com.jakala.gekko.device.Scrolling
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
            Command.Hello -> Response.HelloResult(Protocol.VERSION, GekkoVersion.NAME, Protocol.ROLE_AGENT)
            Command.Metrics -> metrics()
            Command.Tree -> Response.TreeResult(snapshotNodes())
            Command.Foreground -> Response.ForegroundResult(isForeground())
            Command.Screenshot -> unsupported("Screenshot is handled by the host CLI")
            is Command.SetOrientation -> unsupported("Rotating the device is served by the system runner")
            Command.HideKeyboard -> unsupported("The CLI closes the keyboard over adb")
            is Command.WaitIdle -> waitIdle(command.timeoutMs)
            is Command.WaitFor -> waitFor(command)
            is Command.Tap -> tap(command)
            is Command.TapAt -> tapAt(command)
            is Command.DoubleTap -> doubleTap(command)
            is Command.LongPress -> longPress(command)
            is Command.Type -> type(command)
            is Command.Scroll -> scroll(command)
            is Command.Swipe -> swipe(command)
        }
    }

    /** Hello and metrics are facts about the agent and the display, and the CLI asks them before anything else. */
    private fun Command.isServedInBackground(): Boolean {
        return this == Command.Foreground ||
            this == Command.Hello ||
            this == Command.Metrics ||
            this is Command.SetOrientation
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

    /** A touch at screen coordinates, in the topmost window that contains them. */
    private fun tapAt(command: Command.TapAt): Response {
        pendingTouch = null
        MainThread.run {
            val root = windowAt(command.x, command.y)
            if (root != null) {
                val location = IntArray(2).also { root.getLocationOnScreen(it) }
                press(root, (command.x - location[0]).toFloat(), (command.y - location[1]).toFloat())
            }
        }
        val touch = pendingTouch ?: return Response.Error("No window to tap at ${command.x},${command.y}")
        Thread.sleep(TAP_MS)
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
        val root = MainThread.run { reader.windows().lastOrNull() } ?: return Response.Error("No window")
        MainThread.run { dispatchGesture(root, command) }
        return Response.Ok
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

    private fun dispatchGesture(root: View, command: Command.Swipe) {
        val location = IntArray(2).also { root.getLocationOnScreen(it) }
        val start = SystemClock.uptimeMillis()
        val steps = SWIPE_STEPS
        fun event(action: Int, time: Long, x: Float, y: Float): MotionEvent {
            return MotionEvent.obtain(start, time, action, x - location[0], y - location[1], 0)
        }
        root.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, start, command.fromX.toFloat(), command.fromY.toFloat()))
        for (step in 1..steps) {
            val fraction = step / steps.toFloat()
            val x = command.fromX + (command.toX - command.fromX) * fraction
            val y = command.fromY + (command.toY - command.fromY) * fraction
            val time = start + (command.durationMs * fraction).toLong()
            root.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, time, x, y))
        }
        root.dispatchTouchEvent(
            event(MotionEvent.ACTION_UP, start + command.durationMs, command.toX.toFloat(), command.toY.toFloat())
        )
    }

    private companion object {
        const val POLL_MS = 16L
        const val IDLE_WAIT_MS = 500L
        const val TAP_MS = 60L
        const val DOUBLE_TAP_GAP_MS = 80L
        const val SWIPE_STEPS = 10
    }
}
