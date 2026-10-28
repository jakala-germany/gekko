package com.jakala.gekko.device

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.SettleTracker
import com.jakala.gekko.protocol.UiNode

/** One reading of the screen: the windows, the topmost last, and where the ancestors of a node cut it off. */
class Frame(val windows: List<UiNode>, val clipOf: (UiNode) -> Bounds? = { null })

object Waiting {
    const val POLL_MS = 50L

    /**
     * Waits until the element is on screen ([Command.WaitFor.visible]) or gone. The error names the cause as a kind and
     * tells whether the screen was done loading; a wait for an element to disappear ends with a timeout.
     */
    fun visibility(command: Command.WaitFor, now: () -> Long, pause: (Long) -> Unit, read: () -> Frame): Response {
        val deadline = now() + command.timeoutMs
        val tracker = SettleTracker(command.settleMs, now())
        while (true) {
            val frame = read()
            val lookup = ElementLookup.find(frame.windows, command.selector, frame.clipOf)
            val shown = lookup.onScreen.isNotEmpty()
            if (shown == command.visible) {
                return Response.Ok
            }
            tracker.observe(
                signature = frame.windows.fold(17) { hash, window -> 31 * hash + window.signature() },
                busy = frame.windows.any { it.hasBusyNode() },
                now = now(),
            )
            // Past the point where the screen is done a wait cannot help; only a loading screen gets the full timeout
            val settled = tracker.isSettled(now())
            if (settled || now() > deadline) {
                val message =
                    if (command.visible) "Expected visible: ${command.selector}"
                    else "Expected not visible: ${command.selector}"
                val kind = if (command.visible) lookup.absent else ErrorKind.TIMEOUT
                return Response.Error(message, kind, settledOrNull(command.settleMs, settled))
            }
            pause(POLL_MS)
        }
    }

    /** The settle state is only known when the caller asked for the check at all. */
    fun settledOrNull(settleMs: Long, settled: Boolean): Boolean? {
        return if (settleMs > 0) settled else null
    }
}
