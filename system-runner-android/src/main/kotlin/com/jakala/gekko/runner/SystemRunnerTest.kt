package com.jakala.gekko.runner

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.View
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

    @Test
    fun serve() {
        configureAccessibility()
        CommandServer(AbstractSocketListener(SOCKET_NAME), ::execute).start()
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
            Command.Hello -> Response.HelloResult(Protocol.VERSION, GekkoVersion.NAME, Protocol.ROLE_RUNNER)
            Command.Metrics -> Response.MetricsResult(density(), isRtl())
            Command.Tree -> Response.TreeResult(snapshot().windows)
            Command.Foreground -> Response.ForegroundResult(false)
            Command.Screenshot -> Response.Error("Screenshot is handled by the host CLI", ErrorKind.UNSUPPORTED)
            Command.HideKeyboard -> Response.Error("The CLI closes the keyboard over adb", ErrorKind.UNSUPPORTED)
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
                    // A swipe that stays in place is the UiAutomator way to hold a finger down: one step is about 5 ms
                    val steps = (command.holdMs / SWIPE_STEP_MS).toInt().coerceAtLeast(1)
                    device.swipe(
                        match.bounds.centerX,
                        match.bounds.centerY,
                        match.bounds.centerX,
                        match.bounds.centerY,
                        steps,
                    )
                    Response.Ok
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
            is Command.Scroll -> scroll(command)
            is Command.SetOrientation -> setOrientation(command.landscape)
            is Command.Swipe -> {
                device.swipe(command.fromX, command.fromY, command.toX, command.toY, SWIPE_STEPS)
                Response.Ok
            }
        }
    }

    private fun density(): Float {
        return instrumentation.targetContext.resources.displayMetrics.density
    }

    /** The reading direction of the device; accessibility nodes do not tell it per window. */
    private fun isRtl(): Boolean {
        return instrumentation.targetContext.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
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
                swipeToReveal(nodeBounds(container), reveal, command.distanceDp * density())
                Response.Ok
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

    private fun swipeToReveal(container: Bounds, reveal: Reveal, distancePx: Float) {
        val visible = container.intersect(Bounds(0, 0, device.displayWidth, device.displayHeight))
        val extent = if (reveal.y != 0) visible.bottom - visible.top else visible.right - visible.left
        val distance = minOf(distancePx, extent * MAX_SWIPE_FRACTION).toInt().coerceAtLeast(1)
        // Content below comes into view when the finger moves up, content on the right when it moves left
        val moveX = -reveal.x * distance
        val moveY = -reveal.y * distance
        val steps = (distance / SWIPE_PX_PER_STEP).coerceIn(MIN_SCROLL_STEPS, MAX_SCROLL_STEPS)
        device.swipe(
            visible.centerX - moveX / 2,
            visible.centerY - moveY / 2,
            visible.centerX + moveX / 2,
            visible.centerY + moveY / 2,
            steps,
        )
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
                children = (0 until node.childCount).mapNotNull { node.getChild(it) }.map { toUiNode(it, handles) },
            )
        handles[uiNode] = node
        return uiNode
    }

    private companion object {
        const val SOCKET_NAME = "gekko-system-runner"
        const val PROGRESS_BAR = "android.widget.ProgressBar"
        const val POLL_MS = 50L
        const val ROOT_RETRIES = 10
        const val ROOT_RETRY_MS = 50L
        const val SWIPE_STEPS = 20
        const val SWIPE_STEP_MS = 5L
        const val SWIPE_PX_PER_STEP = 12
        const val MIN_SCROLL_STEPS = 10
        const val MAX_SCROLL_STEPS = 200
        const val MAX_SWIPE_FRACTION = 0.8f
        const val DOUBLE_TAP_GAP_MS = 80L
        const val ORIENTATION_IDLE_MS = 2_000L
    }
}
