package com.jakala.gekko.cli

import com.jakala.gekko.device.ElementLookup
import com.jakala.gekko.device.Frame
import com.jakala.gekko.device.Lookup
import com.jakala.gekko.device.Scrolling
import com.jakala.gekko.device.VisibilityWait
import com.jakala.gekko.device.Waiting
import com.jakala.gekko.device.hasArea
import com.jakala.gekko.device.intersect
import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.GekkoVersion
import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.SettleTracker
import com.jakala.gekko.protocol.UiNode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One reading of the page: the windows (the topmost last), where each node can be addressed, and any open dialog. */
internal class WebSnapshot(
    val windows: List<UiNode>,
    private val refs: List<Pair<UiNode, String>>,
    val viewport: Bounds,
    val rtl: Boolean,
    val dialog: JsonObject?,
) {
    /** The address of [node] in the page, by identity: equal nodes of the tree are still different elements. */
    fun refOf(node: UiNode): String? {
        return refs.firstOrNull { it.first === node }?.second
    }

    fun signature(): Int {
        return windows.fold(17) { hash, window -> 31 * hash + window.signature() }
    }

    fun isBusy(): Boolean {
        return windows.any { it.hasBusyNode() }
    }
}

/**
 * The web agent. The page itself only hands out a snapshot of what is on it; which node a selector means, whether it
 * can be seen, when the screen is done changing and what an action does to it is decided here, with the code the
 * Android agent uses ([ElementLookup], [SettleTracker]), so a test fails and heals the same way on every platform.
 */
internal class WebAgentClient(
    private val bridge: WebRpc,
    private val config: GekkoConfig,
    private val clock: () -> Long = ::nowMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : CommandClient {
    private var promptText: String? = null
    private var lastRtl = false

    override suspend fun isReachable(): Boolean {
        return bridge.isUp()
    }

    override fun close() {
        bridge.close()
    }

    override suspend fun send(command: Command): Response {
        return try {
            execute(command)
        } catch (error: WebBridgeException) {
            Response.Error(error.message ?: "The web bridge failed", error.kind)
        }
    }

    private suspend fun execute(command: Command): Response {
        return when (command) {
            Command.Hello -> Response.HelloResult(Protocol.VERSION, GekkoVersion.NAME, Protocol.ROLE_AGENT)
            Command.Metrics -> Response.MetricsResult(1f, read()?.rtl ?: lastRtl)
            Command.Tree -> Response.TreeResult(read()?.windows.orEmpty())
            Command.Foreground -> Response.ForegroundResult(true)
            Command.Screenshot -> unsupported("Screenshot is handled by the host CLI")
            Command.HideKeyboard -> {
                bridge.call("blur")
                Response.Ok
            }
            is Command.SetOrientation -> setOrientation(command.landscape)
            is Command.SetLocation -> {
                bridge.call(
                    "emulate",
                    buildJsonObject {
                        put(
                            "geolocation",
                            buildJsonObject {
                                put("latitude", JsonPrimitive(command.latitude))
                                put("longitude", JsonPrimitive(command.longitude))
                            },
                        )
                    },
                )
                Response.Ok
            }
            Command.ClearLocation -> {
                bridge.call("emulate", buildJsonObject { put("geolocation", JsonNull) })
                Response.Ok
            }
            is Command.TapAt -> {
                bridge.call("click", point(command.x, command.y))
                Response.Ok
            }
            is Command.Swipe -> {
                bridge.call(
                    "drag",
                    buildJsonObject {
                        put("fromX", JsonPrimitive(command.fromX))
                        put("fromY", JsonPrimitive(command.fromY))
                        put("toX", JsonPrimitive(command.toX))
                        put("toY", JsonPrimitive(command.toY))
                        put("durationMs", JsonPrimitive(command.durationMs))
                    },
                )
                Response.Ok
            }
            is Command.Wheel -> {
                bridge.call(
                    "wheel",
                    buildJsonObject {
                        put("x", JsonPrimitive(command.x))
                        put("y", JsonPrimitive(command.y))
                        put("dx", JsonPrimitive(command.dx))
                        put("dy", JsonPrimitive(command.dy))
                    },
                )
                Response.Ok
            }
            is Command.WaitIdle -> waitIdle(command.timeoutMs)
            is Command.WaitFor -> waitFor(command)
            is Command.Tap -> click(command.selector, command.timeoutMs, command.settleMs, count = 1, holdMs = 0)
            is Command.DoubleTap -> click(command.selector, command.timeoutMs, command.settleMs, count = 2, holdMs = 0)
            is Command.LongPress ->
                click(command.selector, command.timeoutMs, command.settleMs, count = 1, holdMs = command.holdMs)
            is Command.Type -> type(command)
            is Command.Scroll -> scroll(command)
        }
    }

    private fun unsupported(message: String): Response {
        return Response.Error(message, ErrorKind.UNSUPPORTED)
    }

    private fun point(x: Int, y: Int): JsonObject {
        return buildJsonObject {
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
        }
    }

    // ------------------------------------------------------------------------------------------------- reading the
    // page

    /** The page as it is now; null while it is being replaced (a navigation) and cannot be read for a moment. */
    private suspend fun read(): WebSnapshot? {
        val answer =
            try {
                bridge.call(
                    "snapshot",
                    buildJsonObject {
                        put("idAttributes", JsonArray(config.web.idAttributes.map { JsonPrimitive(it) }))
                    },
                )
            } catch (error: WebBridgeException) {
                if (error.transient) return null
                throw error
            }
        if (answer["transient"]?.jsonPrimitive?.boolean == true) return null
        val refs = mutableListOf<Pair<UiNode, String>>()
        val viewportJson = answer["viewport"] as? JsonObject
        val viewport =
            Bounds(
                0,
                0,
                viewportJson?.get("w")?.jsonPrimitive?.int ?: config.web.viewport.substringBefore('x').toInt(),
                viewportJson?.get("h")?.jsonPrimitive?.int ?: config.web.viewport.substringAfter('x').toInt(),
            )
        val windows = (answer["windows"] as? JsonArray).orEmpty().map { parseNode(it.jsonObject, refs) }.toMutableList()
        val dialog = answer["dialog"] as? JsonObject
        if (dialog != null) {
            windows += dialogWindow(dialog, viewport)
        } else {
            promptText = null
        }
        val rtl = answer["rtl"]?.jsonPrimitive?.boolean ?: false
        lastRtl = rtl
        return WebSnapshot(windows, refs, viewport, rtl, dialog)
    }

    private fun parseNode(json: JsonObject, refs: MutableList<Pair<UiNode, String>>): UiNode {
        val children = (json["children"] as? JsonArray).orEmpty().map { parseNode(it.jsonObject, refs) }
        val node =
            UiNode(
                id = json.string("id"),
                text = json.string("text"),
                description = json.string("description"),
                role = json.string("role"),
                bounds = bounds(json["bounds"]) ?: Bounds(0, 0, 0, 0),
                actions = (json["actions"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                enabled = json.flag("enabled") ?: true,
                checked = json.flag("checked"),
                selected = json.flag("selected"),
                secure = json.flag("secure") ?: false,
                busy = json.flag("busy") ?: false,
                clip = bounds(json["clip"]),
                children = children,
            )
        json.string("ref")?.let { refs += node to it }
        return node
    }

    private fun JsonObject.flag(key: String): Boolean? {
        val value = this[key]
        return if (value is JsonPrimitive && value !is JsonNull) value.boolean else null
    }

    private fun bounds(element: JsonElement?): Bounds? {
        val json = element as? JsonObject ?: return null
        fun side(key: String): Int {
            return json[key]?.jsonPrimitive?.content?.toDouble()?.toInt() ?: 0
        }
        return Bounds(side("left"), side("top"), side("right"), side("bottom"))
    }

    /**
     * A native dialog (alert, confirm, prompt, beforeunload) blocks the page, so it is shown as the topmost window with
     * the buttons it has; a test answers it with `tap: { text: OK, system: true }`.
     */
    private fun dialogWindow(dialog: JsonObject, viewport: Bounds): UiNode {
        val type = dialog.string("type")
        val x = viewport.centerX
        val y = viewport.centerY
        fun button(id: String, text: String, left: Int): UiNode {
            return UiNode(
                id = id,
                text = text,
                role = "button",
                bounds = Bounds(x + left, y + 20, x + left + 100, y + 50),
                actions = listOf("click"),
            )
        }
        val children = mutableListOf<UiNode>()
        if (type == "prompt") {
            children +=
                UiNode(
                    id = DIALOG_FIELD,
                    text = promptText ?: dialog.string("defaultValue").orEmpty(),
                    role = "textbox",
                    bounds = Bounds(x - 110, y - 30, x + 110, y),
                    actions = listOf("click", "setText"),
                )
        }
        val leave = type == "beforeunload"
        children += button(DIALOG_ACCEPT, if (leave) "Leave" else "OK", left = -110)
        if (type != "alert") children += button(DIALOG_DISMISS, if (leave) "Stay" else "Cancel", left = 10)
        return UiNode(
            id = DIALOG_WINDOW,
            text = dialog.string("message").orEmpty(),
            role = "alertdialog",
            bounds = viewport,
            children = children,
        )
    }

    private fun dialogHint(dialog: JsonObject?): String {
        dialog ?: return ""
        return "; the browser shows a ${dialog.string("type")} dialog (\"${dialog.string("message")}\"): " +
            "answer it with tap: { text: OK, system: true }"
    }

    // ------------------------------------------------------------------------------------------------------- elements

    /**
     * Looks the element up on every reading until it is found and no longer moves, or the screen is done (or the time
     * is up) without it. A page that is still loading gets the whole timeout, a finished one fails at once.
     */
    private suspend fun withElement(
        selector: Selector,
        timeoutMs: Long,
        settleMs: Long,
        action: suspend (WebSnapshot, UiNode) -> Response,
    ): Response {
        val deadline = clock() + timeoutMs
        val settle = SettleTracker(settleMs, clock())
        var missing = ErrorKind.NOT_FOUND
        var previous: Bounds? = null
        var dialog: JsonObject? = null
        var scrolls = 0
        while (true) {
            val snapshot = read()
            if (snapshot != null) {
                dialog = snapshot.dialog
                settle.observe(snapshot.signature(), snapshot.isBusy(), clock())
                val lookup = ElementLookup.find(snapshot.windows, selector) { it.clip }
                missing = lookup.missing
                val node = lookup.pick(selector.index)
                if (node == null && scrolls < MAX_PAGE_SCROLLS && bringIntoView(snapshot, lookup, selector)) {
                    // The page scrolled to the element: read it again at its new place
                    scrolls++
                    previous = null
                    continue
                }
                if (node != null) {
                    // Like a person, wait for an element that is still sliding or being laid out before hitting it
                    if (previous == node.bounds || settle.isSettled(clock()) || clock() > deadline) {
                        return action(snapshot, node)
                    }
                    previous = node.bounds
                } else {
                    previous = null
                }
            }
            val settled = settle.isSettled(clock())
            if (settled || clock() > deadline) {
                return Response.Error(
                    "Element not found: $selector" + (if (settled) " (screen settled)" else "") + dialogHint(dialog),
                    missing,
                    Waiting.settledOrNull(settleMs, settled),
                )
            }
            pause(POLL_MS)
        }
    }

    /**
     * An element of the page that is only outside the window gets scrolled to, like a person (or Playwright) would do
     * before acting on it; one that a container cuts off (a row far down a list) is not touched: that is what `scroll`
     * and `scrollUntilVisible` are for, as on the other platforms.
     */
    private suspend fun bringIntoView(snapshot: WebSnapshot, lookup: Lookup, selector: Selector): Boolean {
        val window = snapshot.windows.lastOrNull()?.bounds ?: return false
        val candidates =
            lookup.matches.filter { node ->
                val cut = node.clip
                val centerInClip =
                    cut == null ||
                        (node.bounds.centerX in cut.left until cut.right &&
                            node.bounds.centerY in cut.top until cut.bottom)
                val usable = selector.enabled != null || node.enabled
                centerInClip && node.bounds.hasArea() && usable && node.id?.startsWith("gekko.dialog") != true
            }
        val target = candidates.getOrNull(selector.index) ?: return false
        if (
            target.bounds.centerX in window.left until window.right &&
                target.bounds.centerY in window.top until window.bottom
        ) {
            return false
        }
        val ref = snapshot.refOf(target) ?: return false
        bridge.call("scrollIntoView", buildJsonObject { put("ref", JsonPrimitive(ref)) })
        return true
    }

    private suspend fun click(selector: Selector, timeoutMs: Long, settleMs: Long, count: Int, holdMs: Long): Response {
        return withElement(selector, timeoutMs, settleMs) { snapshot, node ->
            when (node.id) {
                DIALOG_ACCEPT -> answerDialog(snapshot, accept = true)
                DIALOG_DISMISS -> answerDialog(snapshot, accept = false)
                DIALOG_FIELD -> Response.Ok
                else -> {
                    val (x, y) = visibleCenter(node, snapshot.viewport)
                    bridge.call(
                        "click",
                        buildJsonObject {
                            put("x", JsonPrimitive(x))
                            put("y", JsonPrimitive(y))
                            put("count", JsonPrimitive(count))
                            put("holdMs", JsonPrimitive(holdMs))
                        },
                    )
                    Response.Ok
                }
            }
        }
    }

    private suspend fun answerDialog(snapshot: WebSnapshot, accept: Boolean): Response {
        val dialog = snapshot.dialog ?: return Response.Error("No browser dialog is open", ErrorKind.NOT_FOUND)
        val text =
            if (accept && dialog.string("type") == "prompt") promptText ?: dialog.string("defaultValue") else null
        bridge.call(
            "dialog",
            buildJsonObject {
                put("accept", JsonPrimitive(accept))
                text?.let { put("text", JsonPrimitive(it)) }
            },
        )
        promptText = null
        return Response.Ok
    }

    private suspend fun type(command: Command.Type): Response {
        return withElement(command.selector, command.timeoutMs, command.settleMs) { snapshot, node ->
            if (node.id == DIALOG_FIELD) {
                promptText = command.text
                return@withElement Response.Ok
            }
            val target = if ("setText" in node.actions) node else firstEditable(node)
            val ref = target?.let { snapshot.refOf(it) }
            if (ref == null) {
                Response.Error("Element is not editable", ErrorKind.REJECTED)
            } else {
                bridge.call(
                    "fill",
                    buildJsonObject {
                        put("ref", JsonPrimitive(ref))
                        put("text", JsonPrimitive(command.text))
                    },
                )
                Response.Ok
            }
        }
    }

    private fun firstEditable(node: UiNode): UiNode? {
        return node.children.firstNotNullOfOrNull { child ->
            child.takeIf { "setText" in it.actions } ?: firstEditable(child)
        }
    }

    private suspend fun scroll(command: Command.Scroll): Response {
        if (Scrolling.reveal(command.direction, rtl = false) == null) {
            return unsupported("Unknown scroll direction: ${command.direction}")
        }
        return withElement(command.selector, command.timeoutMs, command.settleMs) { snapshot, node ->
            val reveal =
                Scrolling.reveal(command.direction, snapshot.rtl)
                    ?: return@withElement unsupported("Unknown scroll direction: ${command.direction}")
            // The nearest container that scrolls does it, like on Android; the page itself is the last one
            val container =
                pathTo(snapshot.windows, node).lastOrNull { "scroll" in it.actions }
                    ?: return@withElement Response.Error(
                        "Nothing scrollable at ${describe(node)}",
                        ErrorKind.REJECTED,
                    )
            val (x, y) = visibleCenter(container, snapshot.viewport)
            // Distances are in CSS pixels, which is what dp are on the web
            bridge.call(
                "wheel",
                buildJsonObject {
                    put("x", JsonPrimitive(x))
                    put("y", JsonPrimitive(y))
                    put("dx", JsonPrimitive(reveal.x * command.distanceDp))
                    put("dy", JsonPrimitive(reveal.y * command.distanceDp))
                },
            )
            awaitQuiet()
            Response.Ok
        }
    }

    private fun describe(node: UiNode): String {
        return node.id?.let { "id=$it" } ?: node.text?.let { "text=\"$it\"" } ?: "a ${node.role ?: "node"}"
    }

    /** The middle of what can be seen of the node: inside the window and inside what its ancestors cut off. */
    private fun visibleCenter(node: UiNode, viewport: Bounds): Pair<Int, Int> {
        var area = node.bounds.intersect(viewport)
        node.clip?.let { area = area.intersect(it) }
        val target = if (area.hasArea()) area else node.bounds
        return target.centerX to target.centerY
    }

    private fun pathTo(windows: List<UiNode>, target: UiNode): List<UiNode> {
        for (window in windows) {
            findPath(window, target)?.let {
                return it
            }
        }
        return listOf(target)
    }

    private fun findPath(root: UiNode, target: UiNode): List<UiNode>? {
        if (root === target) return listOf(root)
        for (child in root.children) {
            findPath(child, target)?.let {
                return listOf(root) + it
            }
        }
        return null
    }

    // --------------------------------------------------------------------------------------------------------- waiting

    /** Waits until the element is on screen (or gone); the same answers as the Android agent gives. */
    private suspend fun waitFor(command: Command.WaitFor): Response {
        val wait = VisibilityWait(command, clock())
        while (true) {
            val snapshot = read()
            wait.onReading(snapshot?.let { Frame(it.windows) { node -> node.clip } }, clock())?.let {
                return it
            }
            pause(Waiting.POLL_MS)
        }
    }

    /** True when two readings in a row are the same: nothing moved or changed, nothing is loading. */
    private suspend fun waitIdle(timeoutMs: Long): Response {
        val deadline = clock() + timeoutMs
        var previous: Int? = null
        while (clock() <= deadline) {
            val snapshot = read()
            if (snapshot != null) {
                val signature = snapshot.signature()
                if (signature == previous && !snapshot.isBusy()) return Response.Ok
                previous = signature
            }
            pause(Waiting.POLL_MS)
        }
        return Response.Error("The page did not become idle in ${timeoutMs}ms", ErrorKind.TIMEOUT)
    }

    /** A scroll or a swipe goes on for a moment (smooth scrolling); the next lookup must not see it half way. */
    private suspend fun awaitQuiet() {
        val deadline = clock() + QUIET_LIMIT_MS
        var previous: Int? = null
        var steadySince = clock()
        while (clock() <= deadline) {
            pause(Waiting.POLL_MS)
            val signature = read()?.signature()
            if (signature == null || signature != previous) {
                previous = signature
                steadySince = clock()
            } else if (clock() - steadySince >= QUIET_MS) {
                return
            }
        }
    }

    private suspend fun setOrientation(landscape: Boolean): Response {
        val viewport = read()?.viewport ?: return Response.Error("The page cannot be read", ErrorKind.TIMEOUT)
        val longer = maxOf(viewport.right, viewport.bottom)
        val shorter = minOf(viewport.right, viewport.bottom)
        bridge.call(
            "emulate",
            buildJsonObject {
                put(
                    "viewport",
                    buildJsonObject {
                        put("w", JsonPrimitive(if (landscape) longer else shorter))
                        put("h", JsonPrimitive(if (landscape) shorter else longer))
                    },
                )
            },
        )
        return Response.Ok
    }

    private companion object {
        const val POLL_MS = 30L
        const val QUIET_LIMIT_MS = 1_500L
        const val QUIET_MS = 100L
        const val MAX_PAGE_SCROLLS = 3
        const val DIALOG_WINDOW = "gekko.dialog"
        const val DIALOG_ACCEPT = "gekko.dialog.accept"
        const val DIALOG_DISMISS = "gekko.dialog.dismiss"
        const val DIALOG_FIELD = "gekko.dialog.field"
    }
}
