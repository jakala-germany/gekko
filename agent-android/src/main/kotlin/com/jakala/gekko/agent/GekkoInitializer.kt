package com.jakala.gekko.agent

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import com.jakala.gekko.transport.CommandServer

class GekkoInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val application = context.applicationContext as Application
        val tracker = ActivityTracker()
        application.registerActivityLifecycleCallbacks(tracker)
        val executor = CommandExecutor(application, tracker)
        val socketName = socketName(context.packageName)
        // A test agent must never take the app down, e.g. when the socket name is already taken.
        runCatching { CommandServer(AbstractSocketListener(socketName)) { executor.execute(it) }.start() }
            .onFailure { Log.w(TAG, "Gekko agent could not listen on $socketName: ${it.message}") }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }

    companion object {
        private const val TAG = "GekkoAgent"

        /** Abstract socket of an app; the CLI forwards `localabstract:` plus this name. */
        fun socketName(packageName: String): String {
            return "gekko-$packageName"
        }
    }
}
