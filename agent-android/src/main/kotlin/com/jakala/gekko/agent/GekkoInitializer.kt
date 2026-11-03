package com.jakala.gekko.agent

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.startup.Initializer
import com.jakala.gekko.protocol.MockRules
import com.jakala.gekko.transport.CommandServer
import java.io.File

class GekkoInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val application = context.applicationContext as Application
        // Only the process of the app itself: a service in its own process (`android:process`) would otherwise take
        // the mocks file away from the app and the socket the CLI connects to
        if (!isMainProcess(context.applicationInfo.processName, currentProcessName())) return
        // The mocks of this start are in place before the app code runs, so its first requests are answered too
        GekkoMocks.loadStartupFile(File(context.applicationInfo.dataDir, MockRules.STARTUP_FILE))
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

        /** True in the process of the app itself; when a name is not known the process counts as the app's. */
        internal fun isMainProcess(mainProcess: String?, currentProcess: String?): Boolean {
            return mainProcess == null || currentProcess == null || mainProcess == currentProcess
        }

        private fun currentProcessName(): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
            return try {
                File("/proc/self/cmdline").readText().substringBefore(Char(0)).trim().ifEmpty { null }
            } catch (error: Exception) {
                null
            }
        }

        /** Abstract socket of an app; the CLI forwards `localabstract:` plus this name. */
        fun socketName(packageName: String): String {
            return "gekko-$packageName"
        }
    }
}
