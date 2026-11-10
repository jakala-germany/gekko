package com.jakala.gekko.runner

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jakala.gekko.device.ElementLookup
import com.jakala.gekko.device.Frame
import com.jakala.gekko.device.NO_BOUNDS
import com.jakala.gekko.device.NodeFacts
import com.jakala.gekko.device.Reveal
import com.jakala.gekko.device.Scrolling
import com.jakala.gekko.device.Waiting
import com.jakala.gekko.device.intersect
import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.GekkoVersion
import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import com.jakala.gekko.transport.CommandServer
import com.jakala.gekko.transport.ServerAuth
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import org.junit.Test

/**
 * Started with `am instrument -w -e class com.jakala.gekko.runner.SystemRunnerTest`. Blocks forever and serves the
 * gekko protocol for windows outside the app process (browser, permission dialogs) on the abstract socket
 * [SOCKET_NAME], which only adb may connect to.
 */
class SystemRunnerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val mockLocation by lazy { MockLocation(instrumentation.targetContext) }
    private val touches = Touches { instrumentation.uiAutomation }

    @Test
    fun serve() {
        configureAccessibility()
        // The CLI hands the key over as an argument of `am instrument`; a runner started by hand has none and refuses
        // all
        val key = InstrumentationRegistry.getArguments().getString(Protocol.RUNNER_KEY_ARGUMENT)?.toByteArray()
        CommandServer(AbstractSocketListener(SOCKET_NAME), ServerAuth(SOCKET_NAME) { key }, ::execute).start()
        CountDownLatch(1).await()
    }

    private fun configureAccessibility() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo ?: return
        info.flags =
            info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        repeat(ROOT_RETRIES) {
            val automation = instrumentation.uiAutomation
            val active = automation.rootInActiveWindow
            if (active != null) {
                return listOf(active)
            }
            val windows = automation.windows.sortedByDescending { it.layer }.mapNotNull { it.root }
            if (windows.isNotEmpty()) {
                return windows
            }
            Thread.sleep(ROOT_RETRY_MS)
        }
        return emptyList()
    }

    /** The windows as nodes, and the accessibility node behind every one of them to act on. */
    private class Snapshot(
        val windows: List<UiNode>,
        private val handles: IdentityHashMap<UiNode, AccessibilityNodeInfo>,
    ) {
        fun handleOf(node: UiNode): AccessibilityNodeInfo {
            return checkNotNull(handles[node]) { "Not a node of this snapshot" }
        }

        /** What is scrolled out of sight is in the tree, but not visible to the user. */
        fun clipOf(node: UiNode): Bounds? {
            return if (handleOf(node).isVisibleToUser) null else NO_BOUNDS
        }
    }

    private fun snapshot(): Snapshot {
        val handles = IdentityHashMap<UiNode, AccessibilityNodeInfo>()
        return Snapshot(roots().map { toUiNode(it, handles) }, handles)
    }

    private fun execute(command: Command): Response {
        return when (command) {
            Command.Hello ->
                Response.HelloResult(
                    Protocol.VERSION,
                    GekkoVersion.NAME,
                    Protocol.ROLE_RUNNER,
                    Command.names() - UNSUPPORTED_COMMANDS,
                )
            Command.Metrics -> Response.MetricsResult(density(), isRtl())
            Command.Tree -> Response.TreeResult(snapshot().windows)
            Command.Foreground -> Response.ForegroundResult(false)
            Command.Screenshot -> Response.Error("Screenshot is handled by the host CLI", ErrorKind.UNSUPPORTED)
            Command.HideKeyboard -> Response.Error("The CLI closes the keyboard over adb", ErrorKind.UNSUPPORTED)
            is Command.Wheel -> Response.Error("A mouse wheel only exists in a browser", ErrorKind.UNSUPPORTED)
            is Command.SetMocks,
            Command.ClearMocks,
            Command.MockHits -> Response.Error("Network mocks are served by the app", ErrorKind.UNSUPPORTED)
            is Command.WaitIdle -> {
                device.waitForIdle(command.timeoutMs)
                Response.Ok
            }
            is Command.WaitFor ->
                Waiting.visibility(command, now = { System.currentTimeMillis() }, pause = { Thread.sleep(it) }) {
                    val snapshot = snapshot()
                    Frame(snapshot.windows, snapshot::clipOf)
                }
            is Command.Tap ->
                withNode(command.selector, command.timeoutMs) { match, node ->
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) {
                        device.click(match.bounds.centerX, match.bounds.centerY)
                    }
                    Response.Ok
                }
            is Command.TapAt -> {
                device.click(command.x, command.y)
                Response.Ok
            }
            is Command.DoubleTap ->
                withNode(command.selector, command.timeoutMs) { match, _ ->
                    device.click(match.bounds.centerX, match.bounds.centerY)
                    Thread.sleep(DOUBLE_TAP_GAP_MS)
                    device.click(match.bounds.centerX, match.bounds.centerY)
                    Response.Ok
                }
            is Command.LongPress ->
                withNode(command.selector, command.timeoutMs) { match, _ ->
                    touched(touches.hold(match.bounds.centerX, match.bounds.centerY, command.holdMs))
                }
            is Command.Type ->
                withNode(command.selector, command.timeoutMs) { _, node ->
                    val arguments =
                        android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.text)
                        }
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                        Response.Ok
                    } else {
                        Response.Error("Element is not editable", ErrorKind.REJECTED)
                    }
                }
            is Command.TypeFocused -> typeFocused(command)
            is Command.Scroll -> scroll(command)
            is Command.SetOrientation -> setOrientation(command.landscape)
            is Command.SetLocation -> setLocation(command)
            Command.ClearLocation -> {
                mockLocation.clear()
                Response.Ok
            }
            is Command.LongPressAt -> touched(touches.hold(command.x, command.y, command.holdMs))
            is Command.Swipe ->
                touched(touches.swipe(command.fromX, command.fromY, command.toX, command.toY, command.durationMs))
            is Command.Drag ->
                touched(touches.drag(command.fromX, command.fromY, command.toX, command.toY, touchSlop()))
        }
    }

    private fun density(): Float {
        return instrumentation.targetContext.resources.displayMetrics.density
    }

    /** The reading direction of the device; accessibility nodes do not tell it per window. */
    private fun isRtl(): Boolean {
        return instrumentation.targetContext.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    private fun setLocation(command: Command.SetLocation): Response {
        return try {
            mockLocation.set(command.latitude, command.longitude)
            Response.Ok
        } catch (error: SecurityException) {
            Response.Error(
                "Mock locations are not allowed for ${instrumentation.targetContext.packageName}: ${error.message}",
                ErrorKind.REJECTED,
            )
        }
    }

    private fun setOrientation(landscape: Boolean): Response {
        try {
            if (landscape) {
                device.setOrientationLeft()
            } else {
                device.setOrientationNatural()
                device.unfreezeRotation()
            }
        } catch (error: Exception) {
            return Response.Error("Could not rotate the device: ${error.message}")
        }
        device.waitForIdle(ORIENTATION_IDLE_MS)
        return Response.Ok
    }

    /** Swipes inside the scroll container of the element: the finger moves against the content that comes into view. */
    private fun scroll(command: Command.Scroll): Response {
        val reveal =
            Scrolling.reveal(command.direction, isRtl())
                ?: return Response.Error("Unknown scroll direction: ${command.direction}", ErrorKind.UNSUPPORTED)
        return withNode(command.selector, command.timeoutMs) { match, node ->
            val container = generateSequence(node) { it.parent }.firstOrNull { scrollsAlong(it, reveal) }
            if (container == null) {
                Response.Error("Nothing scrollable at ${describe(match)}", ErrorKind.REJECTED)
            } else {
                dragToReveal(nodeBounds(container), reveal, command.distanceDp * density())
            }
        }
    }

    /** A container that names directional scroll actions scrolls along those axes only. */
    private fun scrollsAlong(node: AccessibilityNodeInfo, reveal: Reveal): Boolean {
        if (!node.isScrollable) {
            return false
        }
        val actions = node.actionList
        val vertical =
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP in actions ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN in actions
        val horizontal =
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT in actions ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT in actions
        if (!vertical && !horizontal) {
            return true
        }
        return if (reveal.y != 0) vertical else horizontal
    }

    /** The content moves by the distance and stops: a drag, so nothing flings on past it. */
    private fun dragToReveal(container: Bounds, reveal: Reveal, distancePx: Float): Response {
        val visible = container.intersect(Bounds(0, 0, device.displayWidth, device.displayHeight))
        val extent = if (reveal.y != 0) visible.bottom - visible.top else visible.right - visible.left
        val distance = minOf(distancePx, extent * MAX_SWIPE_FRACTION).toInt().coerceAtLeast(1)
        // Content below comes into view when the finger moves up, content on the right when it moves left
        val moveX = -reveal.x * distance
        val moveY = -reveal.y * distance
        return touched(
            touches.drag(
                visible.centerX - moveX / 2,
                visible.centerY - moveY / 2,
                visible.centerX + moveX / 2,
                visible.centerY + moveY / 2,
                touchSlop(),
            )
        )
    }

    /** How far a finger moves before a scroll container follows it. */
    private fun touchSlop(): Float {
        return ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop.toFloat()
    }

    private fun touched(injected: Boolean): Response {
        return if (injected) Response.Ok else Response.Error("The system refused the touch", ErrorKind.REJECTED)
    }

    /** Waits for the element with the input focus, then types at its cursor and sees whether the field took it. */
    private fun typeFocused(command: Command.TypeFocused): Response {
        val deadline = System.currentTimeMillis() + command.timeoutMs
        var withoutText = false
        while (true) {
            val focus = roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            if (focus != null && (focus.isEditable || command.text.isEmpty())) {
                return typeInto(focus, command.text)
            }
            withoutText = withoutText || focus != null
            if (System.currentTimeMillis() > deadline) {
                val message =
                    if (withoutText) "The focused element does not take text" else "Nothing has the input focus"
                return Response.Error(message, ErrorKind.REJECTED)
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun typeInto(node: AccessibilityNodeInfo, text: String): Response {
        if (text.isEmpty()) {
            return Response.Ok
        }
        if (node.isPassword) {
            // The text of a password field is bullets here, so the cursor cannot be honoured
            return Response.Error(
                "The system runner cannot type at the cursor of a password field; the in-app agent can",
                ErrorKind.REJECTED,
            )
        }
        val shown = node.text?.toString()
        val current = if (showsHint(node)) "" else shown.orEmpty()
        val start = node.textSelectionStart.takeIf { it >= 0 }?.coerceAtMost(current.length) ?: current.length
        val end = node.textSelectionEnd.takeIf { it >= 0 }?.coerceAtMost(current.length) ?: current.length
        val low = minOf(start, end)
        val updated = current.replaceRange(low, maxOf(start, end), text)
        val replacement =
            android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, replacement)) {
            return Response.Error("The focused element did not accept the text", ErrorKind.REJECTED)
        }
        // Setting the text puts the cursor at its end: the next typing goes on where this one stopped
        val cursor =
            android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, low + text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, low + text.length)
            }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursor)
        val deadline = System.currentTimeMillis() + CHANGE_WAIT_MS
        while (true) {
            node.refresh()
            if (node.text?.toString() != shown) {
                return Response.Ok
            }
            if (System.currentTimeMillis() > deadline) {
                return Response.Error("The focused field did not take the text", ErrorKind.REJECTED)
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** An empty field shows its hint as its text; the hint is not part of what a person types into. */
    private fun showsHint(node: AccessibilityNodeInfo): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && node.isShowingHintText
    }

    private fun describe(node: UiNode): String {
        return node.id?.let { "id=$it" } ?: node.text?.let { "text=\"$it\"" } ?: "a ${node.role ?: "node"}"
    }

    /** Waits for the element the selector means (see [ElementLookup]); [action] gets its node and its handle. */
    private fun withNode(
        selector: Selector,
        timeoutMs: Long,
        action: (UiNode, AccessibilityNodeInfo) -> Response,
    ): Response {
        val deadline = System.currentTimeMillis() + timeoutMs
        val wanted = selector.copy(id = selector.id?.let(::shortName), within = selector.within?.let(::shortName))
        while (true) {
            val snapshot = snapshot()
            val lookup = ElementLookup.find(snapshot.windows, wanted, snapshot::clipOf)
            val match = lookup.pick(wanted.index)
            if (match != null) {
                return action(match, snapshot.handleOf(match))
            }
            if (System.currentTimeMillis() > deadline) {
                return Response.Error("Element not found: $selector", lookup.missing)
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** Ids on screen are the part after the package (`pkg:id/name`); a selector may name either form. */
    private fun shortName(id: String): String {
        return id.substringAfter('/')
    }

    private fun shortId(node: AccessibilityNodeInfo): String? {
        return node.viewIdResourceName?.let(::shortName)
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): Bounds {
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun toUiNode(node: AccessibilityNodeInfo, handles: IdentityHashMap<UiNode, AccessibilityNodeInfo>): UiNode {
        val actions = buildList {
            if (node.isClickable) add("click")
            if (node.isEditable) add("setText")
            if (node.isScrollable) add("scroll")
        }
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        val className = node.className?.toString()
        // A password field never hands out what was typed, neither as text nor through its description
        val secure = node.isPassword
        val uiNode =
            UiNode(
                id = shortId(node),
                text = if (secure) null else text ?: description,
                description = if (secure) NodeFacts.safeDescription(description, listOf(text)) else description,
                role = className,
                bounds = nodeBounds(node),
                actions = actions,
                enabled = node.isEnabled,
                checked = if (node.isCheckable) node.isChecked else null,
                selected = NodeFacts.selectedState(node.isSelected, NodeFacts.isTabLikeClass(className)),
                secure = secure,
                // A progress bar that shows a value (also sliders and seek bars) does not keep the screen busy
                busy = className == PROGRESS_BAR && node.rangeInfo == null,
                focused = node.isFocused,
                children = (0 until node.childCount).mapNotNull { node.getChild(it) }.map { toUiNode(it, handles) },
            )
        handles[uiNode] = node
        return uiNode
    }

    private companion object {
        const val SOCKET_NAME = "gekko-system-runner"
        const val PROGRESS_BAR = "android.widget.ProgressBar"
        const val POLL_MS = 50L
        const val CHANGE_WAIT_MS = 1_000L

        /** The commands this server answers with `unsupported`: the others are listed in its answer to `hello`. */
        val UNSUPPORTED_COMMANDS = setOf("screenshot", "hideKeyboard", "wheel", "setMocks", "clearMocks", "mockHits")
        const val ROOT_RETRIES = 10
        const val ROOT_RETRY_MS = 50L
        const val MAX_SWIPE_FRACTION = 0.8f
        const val DOUBLE_TAP_GAP_MS = 80L
        const val ORIENTATION_IDLE_MS = 2_000L
    }
}
