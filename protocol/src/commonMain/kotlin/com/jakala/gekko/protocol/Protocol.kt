package com.jakala.gekko.protocol

/**
 * The wire format between the CLI and the agents and system runners. The number changes only when the format changes in
 * a way an older or newer peer cannot handle; the CLI refuses to talk to a peer with another number and says which side
 * to update. It is not the release version: most releases keep it.
 */
object Protocol {
    const val VERSION = 1

    /** The in-app agent. */
    const val ROLE_AGENT = "agent"

    /** The system runner (UiAutomator, XCUITest). */
    const val ROLE_RUNNER = "runner"

    /** The header that carries the per-run token on the iOS servers. */
    const val TOKEN_HEADER = "X-Gekko-Token"
}
