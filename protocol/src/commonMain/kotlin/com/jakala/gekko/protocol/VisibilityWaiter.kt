package com.jakala.gekko.protocol

/**
 * Waits for an element to be on screen or gone. The agents run it next to the app, so a single request replaces the CLI
 * reading the whole tree over the wire every few milliseconds; the CLI keeps its own loop for agents that do not know
 * [Command.WaitFor].
 */
class VisibilityWaiter(
    private val now: () -> Long,
    private val pause: (Long) -> Unit,
    private val pollMs: Long = DEFAULT_POLL_MS,
) {
    /** Returns null once the wanted state is reached, otherwise the failure message. */
    fun await(command: Command.WaitFor, read: () -> List<UiNode>): String? {
        val deadline = now() + command.timeoutMs
        val tracker = SettleTracker(command.settleMs, now())
        while (true) {
            val windows = read()
            val found = windows.any { window -> window.findAll(command.selector).any { it.isOnScreen(window.bounds) } }
            if (found == command.visible) {
                return null
            }
            tracker.observe(
                signature = windows.fold(17) { hash, window -> 31 * hash + window.signature() },
                busy = windows.any { it.hasBusyNode() },
                now = now(),
            )
            // Past the point where the screen is done a wait cannot help; only a loading screen gets the full timeout
            if (tracker.isSettled(now()) || now() > deadline) {
                return if (command.visible) {
                    "Expected visible: ${command.selector}"
                } else {
                    "Expected not visible: ${command.selector}"
                }
            }
            pause(pollMs)
        }
    }

    companion object {
        const val DEFAULT_POLL_MS = 50L
    }
}
