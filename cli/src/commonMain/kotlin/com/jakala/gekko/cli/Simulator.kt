package com.jakala.gekko.cli

/** iOS simulator controlled through `xcrun simctl`; the simulator shares the host's loopback, so no port forwarding. */
class SimctlDevice(private val requestedUdid: String?) : Device {
    private val udid: String by lazy { requestedUdid ?: singleBootedUdid() }

    // Simulators share the host's loopback, so each one gets its own deterministic port pair.
    override fun agentPort(config: GekkoConfig): Int {
        return agentPort()
    }

    private fun agentPort(): Int {
        return SimulatorPorts.agent(udid)
    }

    override val token: String
        get() = UserToken.value

    override fun systemPort(): Int {
        return SimulatorPorts.agent(udid) + 1
    }

    private fun simctl(vararg args: String, mergeStderr: Boolean = true): ProcessResult {
        return runProcess(listOf("xcrun", "simctl") + args.toList(), mergeStderr)
    }

    override fun screenshot(): ByteArray {
        val file = "/tmp/gekko-screenshot-$udid.png"
        simctl("io", udid, "screenshot", "--type=png", file)
        return runProcess(listOf("cat", file), mergeStderr = false).stdout
    }

    override fun pressKey(key: String) {
        error("pressKey '$key' is not supported on iOS simulators")
    }

    override fun openLink(url: String) {
        val result = simctl("openurl", udid, url)
        check(result.exitCode == 0) { "Opening $url failed: ${result.text}" }
    }

    override fun terminate(config: GekkoConfig) {
        simctl("terminate", udid, bundleId(config))
    }

    override fun setDarkMode(enabled: Boolean): () -> Unit {
        val previous = simctl("ui", udid, "appearance").text.trim()
        val result = simctl("ui", udid, "appearance", if (enabled) "dark" else "light")
        check(result.exitCode == 0) { "Switching the appearance failed: ${result.text}" }
        return { if (previous == "light" || previous == "dark") simctl("ui", udid, "appearance", previous) }
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        val result = simctl("location", udid, "set", "$latitude,$longitude")
        check(result.exitCode == 0) { "Setting the location failed: ${result.text}" }
    }

    override fun setPermissions(config: GekkoConfig, permissions: Map<String, Boolean>) {
        val bundle = bundleId(config)
        for ((name, granted) in permissions) {
            val result = simctl("privacy", udid, if (granted) "grant" else "revoke", PermissionNames.ios(name), bundle)
            check(result.exitCode == 0) { "Setting permission '$name' failed: ${result.text}" }
        }
    }

    override fun clearData(config: GekkoConfig) {
        val bundle = bundleId(config)
        simctl("terminate", udid, bundle)
        val container = simctl("get_app_container", udid, bundle, "data").text
        if (container.startsWith("/")) {
            runProcess(listOf("find", container, "-mindepth", "1", "-delete"))
        }
        simctl("keychain", udid, "reset")
        simctl("privacy", udid, "reset", "all", bundle)
        config.ios.permissions.forEach { simctl("privacy", udid, "grant", it, bundle) }
    }

    override fun uninstall(config: GekkoConfig): List<String> {
        runProcess(listOf("pkill", "-f", "xcodebuild test-without-building.*id=$udid"))
        val removed = mutableListOf<String>()
        val bundles = listOf(bundleId(config), RUNNER_HOST_BUNDLE, RUNNER_TESTS_BUNDLE)
        for (bundle in bundles.filter { isInstalled(it) }) {
            simctl("terminate", udid, bundle)
            val result = simctl("uninstall", udid, bundle)
            check(result.exitCode == 0) { "Uninstalling $bundle failed: ${result.text}" }
            removed += bundle
        }
        runProcess(
            listOf(
                "rm",
                "-rf",
                "/tmp/gekko-app-$udid",
                "/tmp/gekko-ios-runner-$udid.log",
                "/tmp/gekko-screenshot-$udid.png",
            )
        )
        val left = bundles.filter { isInstalled(it) }
        check(left.isEmpty()) { "Still installed after uninstalling: ${left.joinToString()}" }
        return removed
    }

    private fun isInstalled(bundle: String): Boolean {
        return simctl("get_app_container", udid, bundle).exitCode == 0
    }

    override fun install(config: GekkoConfig, path: String?) {
        val source = path ?: config.ios.app ?: error("No app to install; pass a path or set ios.app in gekko.yaml")
        check(fileExists(source)) { "App not found: $source" }
        val app =
            if (source.endsWith(".zip")) AppleBuilds.appInArchive(source, "/tmp/gekko-app-$udid", bundleId(config))
            else source
        val result = simctl("install", udid, app)
        check(result.exitCode == 0) { "Installing $app failed: ${result.text}" }
    }

    override fun launch(config: GekkoConfig, arguments: Map<String, String>) {
        // The environment wins: a CI action installs the agent for the job without touching gekko.yaml
        val framework =
            env("GEKKO_IOS_AGENT")
                ?: config.ios.agentFramework?.let { configured ->
                    if (configured == InstalledRunners.INSTALLED) {
                        InstalledRunners.iosAgentFramework()
                            ?: error("ios.agentFramework is 'installed' but no agent was found; run scripts/install.sh")
                    } else {
                        configured
                    }
                }
        val environment =
            mutableMapOf("SIMCTL_CHILD_GEKKO_PORT" to "${agentPort()}", "SIMCTL_CHILD_GEKKO_TOKEN" to UserToken.value)
        if (framework != null) {
            val binary =
                if (framework.endsWith(".framework"))
                    "$framework/${framework.substringAfterLast('/').removeSuffix(".framework")}"
                else framework
            check(fileExists(binary)) { "ios.agentFramework not found: $binary" }
            environment["SIMCTL_CHILD_DYLD_INSERT_LIBRARIES"] = binary
        }
        val result =
            runProcess(
                listOf("xcrun", "simctl", "launch", udid, bundleId(config)) +
                    launchArguments +
                    arguments.flatMap { (key, value) -> listOf("-$key", value) },
                environment = environment,
            )
        if (result.exitCode != 0) {
            throw InfrastructureException("Launching ${bundleId(config)} on $udid failed: ${result.text}")
        }
    }

    // Building + launching the XCUITest runner takes a while; a second start on the same simulator kills the first
    override val runnerStartTimeoutMs: Long
        get() = 120_000

    override fun startSystemRunner(config: GekkoConfig, log: (String) -> Unit) {
        val dir =
            env("GEKKO_IOS_RUNNER_DIR")
                ?: config.ios.runnerDir?.takeUnless { it == InstalledRunners.INSTALLED }
                ?: InstalledRunners.iosRunnerDir()
                ?: error("System runner not found; run scripts/install.sh or set ios.runnerDir in gekko.yaml")
        log("Starting iOS system runner")
        AppleBuilds.startRunner(dir, udid, bundleId(config), systemPort())
    }

    override fun runnerDiagnostics(): String? {
        return AppleBuilds.tail(AppleBuilds.runnerLog(udid))
    }

    private var launchArguments: List<String> = emptyList()

    override fun startRecording(): Recording {
        val directory = env("TMPDIR")?.trimEnd('/') ?: "/tmp"
        val file = "$directory/gekko-record-${udid}-${nowMillis()}.mp4"
        val started =
            runProcess(
                listOf(
                    "sh",
                    "-c",
                    "nohup xcrun simctl io \"\$1\" recordVideo --codec=h264 --force \"\$2\" >/dev/null 2>&1 & echo \$!",
                    "gekko",
                    udid,
                    file,
                )
            )
        val pid = started.text.lines().lastOrNull()?.trim().orEmpty()
        return object : Recording {
            override fun stop(path: String?): Boolean {
                // SIGINT lets simctl finish the file
                runProcess(listOf("sh", "-c", "kill -2 \"\$1\" 2>/dev/null; sleep 2", "gekko", pid))
                val saved = path != null && runProcess(listOf("cp", file, path)).exitCode == 0
                runProcess(listOf("rm", "-f", file))
                return saved
            }
        }
    }

    override fun applySettings(config: GekkoConfig, settings: DeviceSettings): () -> Unit {
        launchArguments = AppleBuilds.settingsArguments(settings)
        return { launchArguments = emptyList() }
    }

    private fun bundleId(config: GekkoConfig): String {
        return config.app.ios ?: throw IllegalStateException("app.ios is not set in gekko.yaml")
    }

    companion object {
        const val RUNNER_HOST_BUNDLE = AppleBuilds.RUNNER_HOST_BUNDLE
        const val RUNNER_TESTS_BUNDLE = AppleBuilds.RUNNER_TESTS_BUNDLE

        fun bootedUdids(): List<String> {
            val pattern = Regex("\\(([0-9A-Fa-f-]{36})\\) \\(Booted\\)")
            return runProcess(listOf("xcrun", "simctl", "list", "devices", "booted")).text.lines().mapNotNull {
                pattern.find(it)?.groupValues?.get(1)
            }
        }

        private fun singleBootedUdid(): String {
            val booted = bootedUdids()
            return when (booted.size) {
                0 -> error("No booted iOS simulator")
                1 -> booted[0]
                else -> error("Several simulators are booted (${booted.joinToString()}); choose one with -s")
            }
        }
    }
}

object SimulatorPorts {
    private const val BASE = 20_000
    private const val SLOTS = 4_000

    /** Stable per simulator, so every CLI call finds the agent that an earlier call launched. */
    fun agent(udid: String): Int {
        return BASE + 2 * ((udid.hashCode() and 0x7fffffff) % SLOTS)
    }
}
