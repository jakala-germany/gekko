package com.jakala.gekko.device

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.SettleTracker
import com.jakala.gekko.protocol.UiNode

/** One reading of the screen: the windows, the topmost last, and where the ancestors of a node cut it off. */
class Frame(val windows: List<UiNode>, val clipOf: (UiNode) -> Bounds? = { null })

/**
 * The decisions of a wait for an element to be seen (or gone), one reading of the screen at a time.
 * [Waiting.visibility] loops over it; a caller that has a loop of its own (the web agent suspends while it reads) uses
 * it directly, so every platform answers a wait the same way.
 */
class VisibilityWait(private val command: Command.WaitFor, startedAt: Long) {
    private val deadline = startedAt + command.timeoutMs
    private val tracker = SettleTracker(command.settleMs, startedAt)
    private var absent = ErrorKind.NOT_FOUND

    /**
     * The answer once there is one, null while the caller should read again. [frame] is null when the screen could not
     * be read for a moment (a browser that navigates): that reading is skipped.
     */
    fun onReading(frame: Frame?, now: Long): Response? {
        if (frame != null) {
            val lookup = ElementLookup.find(frame.windows, command.selector, frame.clipOf)
            if (lookup.onScreen.isNotEmpty() == command.visible) {
                return Response.Ok
            }
            absent = lookup.absent
            tracker.observe(
                signature = frame.windows.fold(17) { hash, window -> 31 * hash + window.signature() },
                busy = frame.windows.any { it.hasBusyNode() },
                now = now,
            )
        }
        // Past the point where the screen is done a wait cannot help; only a loading screen gets the full timeout
        val settled = tracker.isSettled(now)
        if (!settled && now <= deadline) {
            return null
        }
        val message =
            if (command.visible) "Expected visible: ${command.selector}"
            else "Expected not visible: ${command.selector}"
        val kind = if (command.visible) absent else ErrorKind.TIMEOUT
        return Response.Error(message, kind, Waiting.settledOrNull(command.settleMs, settled))
    }
}

object Waiting {
    const val POLL_MS = 50L

    /**
     * Waits until the element is on screen ([Command.WaitFor.visible]) or gone. The error names the cause as a kind and
     * tells whether the screen was done loading; a wait for an element to disappear ends with a timeout.
     */
    fun visibility(command: Command.WaitFor, now: () -> Long, pause: (Long) -> Unit, read: () -> Frame): Response {
        val wait = VisibilityWait(command, now())
        while (true) {
            wait.onReading(read(), now())?.let {
                return it
            }
            pause(POLL_MS)
        }
    }

    /** The settle state is only known when the caller asked for the check at all. */
    fun settledOrNull(settleMs: Long, settled: Boolean): Boolean? {
        return if (settleMs > 0) settled else null
    }
}
