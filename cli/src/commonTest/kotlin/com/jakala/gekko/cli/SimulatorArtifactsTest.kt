package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulatorArtifactsTest {
    private fun agent(
        ios: IosConfig,
        tv: Boolean,
        environment: Map<String, String> = emptyMap(),
        installedIos: String? = "/home/ios-agent/GekkoAgent.framework",
        installedTvos: String? = "/home/tvos-agent/GekkoAgent.framework",
    ): String? {
        return SimulatorArtifacts.agentFramework(ios, tv, { environment[it] }, { installedIos }, { installedTvos })
    }

    private fun runner(
        ios: IosConfig,
        tv: Boolean,
        environment: Map<String, String> = emptyMap(),
        installedIos: String? = "/home/ios-runner",
        installedTvos: String? = "/home/tvos-runner",
    ): String {
        return SimulatorArtifacts.runnerDir(ios, tv, { environment[it] }, { installedIos }, { installedTvos })
    }

    @Test
    fun anIosSimulatorKeepsTakingTheEnvironmentThenTheConfigThenTheInstalledAgent() {
        val installed = IosConfig(agentFramework = InstalledRunners.INSTALLED)
        val path = IosConfig(agentFramework = "/builds/GekkoAgent.framework")

        assertEquals(
            "/ci/GekkoAgent.framework",
            agent(path, tv = false, mapOf("GEKKO_IOS_AGENT" to "/ci/GekkoAgent.framework")),
        )
        assertEquals("/builds/GekkoAgent.framework", agent(path, tv = false))
        assertEquals("/home/ios-agent/GekkoAgent.framework", agent(installed, tv = false))
        assertNull(agent(IosConfig(), tv = false))
    }

    @Test
    fun anAppleTvTakesTheTvosAgentAndNeverTheIosOne() {
        val installed = IosConfig(agentFramework = InstalledRunners.INSTALLED)
        val explicit =
            IosConfig(
                agentFramework = "/builds/ios/GekkoAgent.framework",
                tvosAgentFramework = "/builds/tv/GekkoAgent.framework",
            )

        assertEquals("/home/tvos-agent/GekkoAgent.framework", agent(installed, tv = true))
        assertEquals("/builds/tv/GekkoAgent.framework", agent(explicit, tv = true))
        assertEquals(
            "/home/tvos-agent/GekkoAgent.framework",
            agent(IosConfig(tvosAgentFramework = InstalledRunners.INSTALLED), tv = true),
        )
        assertEquals(
            "/ci/tv/GekkoAgent.framework",
            agent(
                installed,
                tv = true,
                mapOf("GEKKO_IOS_AGENT" to "/ci/ios", "GEKKO_TVOS_AGENT" to "/ci/tv/GekkoAgent.framework"),
            ),
        )
    }

    @Test
    fun anAppleTvWithoutAnAgentSettingHasNoInjectedAgent() {
        assertNull(agent(IosConfig(), tv = true))
        assertNull(agent(IosConfig(), tv = true, mapOf("GEKKO_IOS_AGENT" to "/ci/ios")))
    }

    @Test
    fun anIosAgentPathIsNotUsedOnAnAppleTvAndTheMessageSaysWhatToSet() {
        val failure =
            assertFailsWith<IllegalStateException> {
                agent(IosConfig(agentFramework = "/builds/ios/GekkoAgent.framework"), tv = true)
            }

        assertTrue(failure.message!!.contains("ios.tvosAgentFramework"), failure.message)
    }

    @Test
    fun aMissingInstalledAgentSaysToRunTheInstaller() {
        val failure =
            assertFailsWith<IllegalStateException> {
                agent(IosConfig(agentFramework = InstalledRunners.INSTALLED), tv = true, installedTvos = null)
            }

        assertTrue(failure.message!!.contains("scripts/install.sh"), failure.message)
    }

    @Test
    fun theRunnerDirectoryIsPickedPerPlatform() {
        val config = IosConfig(runnerDir = "/builds/ios-runner", tvosRunnerDir = "/builds/tv-runner")

        assertEquals("/builds/ios-runner", runner(config, tv = false))
        assertEquals("/builds/tv-runner", runner(config, tv = true))
        assertEquals("/home/ios-runner", runner(IosConfig(runnerDir = InstalledRunners.INSTALLED), tv = false))
        assertEquals("/home/tvos-runner", runner(IosConfig(runnerDir = "/builds/ios-runner"), tv = true))
        assertEquals(
            "/ci/tv",
            runner(config, tv = true, mapOf("GEKKO_IOS_RUNNER_DIR" to "/ci/ios", "GEKKO_TVOS_RUNNER_DIR" to "/ci/tv")),
        )
        assertEquals(
            "/ci/ios",
            runner(config, tv = false, mapOf("GEKKO_IOS_RUNNER_DIR" to "/ci/ios", "GEKKO_TVOS_RUNNER_DIR" to "/ci/tv")),
        )
    }

    @Test
    fun aMissingRunnerNamesTheSettingToUse() {
        val ios = assertFailsWith<IllegalStateException> { runner(IosConfig(), tv = false, installedIos = null) }
        val tvos = assertFailsWith<IllegalStateException> { runner(IosConfig(), tv = true, installedTvos = null) }

        assertTrue(ios.message!!.contains("ios.runnerDir"), ios.message)
        assertTrue(tvos.message!!.contains("ios.tvosRunnerDir"), tvos.message)
    }

    @Test
    fun theTvosSettingsAreReadFromTheConfig() {
        val config =
            Yamls.parse(
                GekkoConfig.serializer(),
                "ios:\n  agentFramework: installed\n  tvosRunnerDir: /builds/tv-runner\n  tvosAgentFramework: /builds/tv/GekkoAgent.framework",
            )

        assertEquals("/builds/tv-runner", config.ios.tvosRunnerDir)
        assertEquals("/builds/tv/GekkoAgent.framework", config.ios.tvosAgentFramework)
    }
}
