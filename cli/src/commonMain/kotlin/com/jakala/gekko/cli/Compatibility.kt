package com.jakala.gekko.cli

import com.jakala.gekko.protocol.GekkoVersion
import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response

/** The agent in the app or the system runner on the device does not speak the protocol of this CLI. */
class IncompatibleVersionException(message: String) : ConfigurationException(message)

/** Checks the answer to `hello`: the CLI refuses a peer with another protocol number and says what to update. */
object Compatibility {
    fun check(answer: Response, role: String) {
        val peer = describe(role)
        if (answer !is Response.HelloResult) {
            throw IncompatibleVersionException(
                "The $peer does not answer the protocol check of gekko ${GekkoVersion.NAME}; " +
                    "it was built with a different gekko release. ${fix(role, olderPeer = true, peerVersion = null)}"
            )
        }
        if (answer.role != role) {
            throw IncompatibleVersionException(
                "Expected the $peer but the server on this port is a ${answer.role} (gekko ${answer.version})"
            )
        }
        if (answer.protocol == Protocol.VERSION) {
            return
        }
        throw IncompatibleVersionException(
            "The $peer (gekko ${answer.version}, protocol ${answer.protocol}) does not match this gekko CLI " +
                "(${GekkoVersion.NAME}, protocol ${Protocol.VERSION}). " +
                fix(role, answer.protocol < Protocol.VERSION, answer.version)
        )
    }

    private fun describe(role: String): String {
        return if (role == Protocol.ROLE_AGENT) "gekko agent in the app" else "gekko system runner"
    }

    private fun fix(role: String, olderPeer: Boolean, peerVersion: String?): String {
        return when {
            !olderPeer -> "Update the gekko CLI to ${peerVersion ?: "the release of the agent"} or newer."
            role == Protocol.ROLE_AGENT ->
                "Update the gekko Gradle plugin of the app to ${GekkoVersion.NAME} and rebuild the app."
            else -> "Remove the runner (gekko uninstall); the CLI installs the matching one on the next run."
        }
    }
}
