package com.jakala.gekko.cli

/**
 * The runners that `scripts/install.sh` (`install.ps1` on Windows) puts into `$GEKKO_HOME/runners` (default
 * `~/.gekko/runners`). The Android runner and the iOS runner are found there without any setting; the iOS agent
 * framework is injected into the app, so a test repository asks for it with `ios.agentFramework: installed`. The Apple
 * TV simulator has its own agent and runner (`gekko-tvos-agent`, `gekko-tvos-runner`).
 */
object InstalledRunners {
    /** The value of `android.runnerApk`, `ios.runnerDir` and `ios.agentFramework` that means "the installed one". */
    const val INSTALLED = "installed"

    private fun directory(): String? {
        return gekkoHome()
    }

    private fun existing(relative: String): String? {
        return directory()?.let { "$it/runners/$relative" }?.takeIf { fileExists(it) }
    }

    fun androidApk(): String? {
        return existing("gekko-system-runner.apk")
    }

    fun iosRunnerDir(): String? {
        return existing("gekko-ios-runner")
    }

    fun iosAgentFramework(): String? {
        return existing("gekko-ios-agent/GekkoAgent.framework")
    }

    fun tvosRunnerDir(): String? {
        return existing("gekko-tvos-runner")
    }

    fun tvosAgentFramework(): String? {
        return existing("gekko-tvos-agent/GekkoAgent.framework")
    }
}
