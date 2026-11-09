package com.jakala.gekko.protocol

/**
 * The wire format between the CLI and the agents and system runners. The number changes only when the format changes in
 * a way an older or newer peer cannot handle; the CLI refuses to talk to a peer with another number and says which side
 * to update. It is not the release version: most releases keep it.
 *
 * Version 2 authenticates both directions. Whoever listens on a port or a socket first can answer the CLI, so a server
 * has to prove that it holds the key of this user, and the CLI has to prove the same to the server. Nothing secret is
 * sent: every request carries a fresh nonce and an HMAC-SHA256 over its endpoint, the session of the server, the nonce
 * and the body ([requestMessage]); every answer carries an HMAC over the same nonce, the status and its body
 * ([responseMessage]). The session is a random id that a server picks when it starts and tells in its answer to
 * `hello`, so a request that was recorded against an impostor is worthless against the real server.
 */
object Protocol {
    const val VERSION = 2

    /** The in-app agent. */
    const val ROLE_AGENT = "agent"

    /** The system runner (UiAutomator, XCUITest). */
    const val ROLE_RUNNER = "runner"

    /**
     * The file in the private directory of an app (and the instrumentation argument of the runner, see
     * [RUNNER_KEY_ARGUMENT]) that holds the key of the Android servers. The CLI writes it with `run-as`, which only a
     * debuggable build allows, so no other app can read it.
     */
    const val AGENT_KEY_FILE = "gekko-key"

    /** The `am instrument -e` argument that hands the key to the system runner. */
    const val RUNNER_KEY_ARGUMENT = "gekkoKey"

    /** A random value (hex) the client picks for every request; the answer is bound to it. */
    const val NONCE_HEADER = "X-Gekko-Nonce"

    /** The session of the server (hex): empty in `hello`, which is how the client learns it. */
    const val SESSION_HEADER = "X-Gekko-Session"

    /** The HMAC-SHA256 (lowercase hex) of the message, in requests and in answers. */
    const val AUTH_HEADER = "X-Gekko-Auth"

    private const val REQUEST_LABEL = "gekko-req"
    private const val RESPONSE_LABEL = "gekko-res"

    /**
     * What a client signs. [endpoint] is what the server knows about itself: its port on the iOS simulators and in the
     * browser, its socket name on Android. A server that was reached through another port or socket than it believes to
     * listen on does not accept the request.
     */
    fun requestMessage(endpoint: String, session: String, nonce: String, body: ByteArray): ByteArray {
        return "$REQUEST_LABEL\n$endpoint\n$session\n$nonce\n".encodeToByteArray() + body
    }

    /** What a server signs; [nonce] is the one of the request it answers. */
    fun responseMessage(endpoint: String, session: String, nonce: String, status: Int, body: ByteArray): ByteArray {
        return "$RESPONSE_LABEL\n$endpoint\n$session\n$nonce\n$status\n".encodeToByteArray() + body
    }
}
