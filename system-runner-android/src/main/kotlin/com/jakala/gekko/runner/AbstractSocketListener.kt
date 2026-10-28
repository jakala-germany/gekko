package com.jakala.gekko.runner

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import com.jakala.gekko.transport.ClientConnection
import com.jakala.gekko.transport.Listener
import com.jakala.gekko.transport.Peers
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A socket in the abstract namespace, reachable with `adb forward tcp:0 localabstract:<name>`; only adb (shell), root
 * and the app itself may connect. The in-app agent has the same class: the runner is an app of its own and must not
 * depend on the agent library, whose manifest would start a second server in the runner process.
 */
internal class AbstractSocketListener(name: String) : Listener {
    private val server = LocalServerSocket(name)

    @Volatile private var open = true

    override val isOpen: Boolean
        get() = open

    override fun accept(): ClientConnection? {
        while (open) {
            val socket =
                try {
                    server.accept()
                } catch (error: IOException) {
                    open = false
                    return null
                }
            if (isPermitted(socket)) {
                return LocalConnection(socket)
            }
            runCatching { socket.close() }
        }
        return null
    }

    /** Decided from the kernel's credentials of the peer, before a single byte is read from the client. */
    private fun isPermitted(socket: LocalSocket): Boolean {
        val uid =
            try {
                socket.peerCredentials.uid
            } catch (error: Exception) {
                return false
            }
        val allowed = Peers.isAllowed(uid, Process.myUid())
        if (!allowed) {
            Log.w(TAG, "Rejected a client with uid $uid; only adb may talk to the gekko system runner")
        }
        return allowed
    }

    private class LocalConnection(private val socket: LocalSocket) : ClientConnection {
        override val input: InputStream
            get() = socket.inputStream

        override val output: OutputStream
            get() = socket.outputStream

        override fun setReadTimeout(millis: Int) {
            socket.soTimeout = millis
        }

        override fun close() {
            socket.close()
        }
    }

    private companion object {
        const val TAG = "GekkoRunner"
    }
}
