package com.jakala.gekko.cli

/**
 * Which agent framework and which system runner a simulator uses: the builds for the iOS simulator, or on an Apple TV
 * simulator the builds for the tvOS simulator (dyld refuses a library that was built for another platform).
 */
internal object SimulatorArtifacts {
    /**
     * The agent framework that is injected into the app, or null when none is (the app has the agent linked in). The
     * environment wins over `gekko.yaml`: a CI action installs the agent for the job without touching it.
     */
    fun agentFramework(
        ios: IosConfig,
        tv: Boolean,
        environment: (String) -> String? = { env(it) },
        installedIos: () -> String? = { InstalledRunners.iosAgentFramework() },
        installedTvos: () -> String? = { InstalledRunners.tvosAgentFramework() },
    ): String? {
        if (tv) {
            return tvAgentFramework(ios, environment, installedTvos)
        }
        return environment("GEKKO_IOS_AGENT")
            ?: ios.agentFramework?.let { configured ->
                if (configured == InstalledRunners.INSTALLED) {
                    installedIos()
                        ?: error("ios.agentFramework is 'installed' but no agent was found; run scripts/install.sh")
                } else {
                    configured
                }
            }
    }

    private fun tvAgentFramework(
        ios: IosConfig,
        environment: (String) -> String?,
        installedTvos: () -> String?,
    ): String? {
        environment("GEKKO_TVOS_AGENT")?.let {
            return it
        }
        // One `ios.agentFramework: installed` serves both: an Apple TV takes the installed tvOS agent then
        val configured = ios.tvosAgentFramework ?: ios.agentFramework?.takeIf { it == InstalledRunners.INSTALLED }
        return when {
            configured == InstalledRunners.INSTALLED ->
                installedTvos() ?: error("The tvOS agent is 'installed' but none was found; run scripts/install.sh")
            configured != null -> configured
            ios.agentFramework != null ->
                error(
                    "ios.agentFramework is built for iOS simulators and cannot run in an app on an Apple TV; " +
                        "set ios.tvosAgentFramework (or GEKKO_TVOS_AGENT) to the framework built for the tvOS simulator"
                )
            else -> null
        }
    }

    /** The directory of the built system runner (`GekkoRunner.xctestrun` and the products). */
    fun runnerDir(
        ios: IosConfig,
        tv: Boolean,
        environment: (String) -> String? = { env(it) },
        installedIos: () -> String? = { InstalledRunners.iosRunnerDir() },
        installedTvos: () -> String? = { InstalledRunners.tvosRunnerDir() },
    ): String {
        if (tv) {
            return environment("GEKKO_TVOS_RUNNER_DIR")
                ?: ios.tvosRunnerDir?.takeUnless { it == InstalledRunners.INSTALLED }
                ?: installedTvos()
                ?: error("tvOS system runner not found; run scripts/install.sh or set ios.tvosRunnerDir in gekko.yaml")
        }
        return environment("GEKKO_IOS_RUNNER_DIR")
            ?: ios.runnerDir?.takeUnless { it == InstalledRunners.INSTALLED }
            ?: installedIos()
            ?: error("System runner not found; run scripts/install.sh or set ios.runnerDir in gekko.yaml")
    }
}
