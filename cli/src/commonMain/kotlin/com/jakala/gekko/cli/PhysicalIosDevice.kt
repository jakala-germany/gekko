package com.jakala.gekko.cli

import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.runBlocking

/**
 * A physical iPhone or iPad on a USB cable. `xcrun devicectl` does everything on the device (install, launch,
 * screenshot, appearance, location); the ports of the in-app agent and the system runner stay on the device's own
 * loopback and are reached through usbmuxd ([UsbmuxForward]), so nothing listens on the network.
 *
 * The app under test links the `GekkoAgent` package product in its debug build (a device build is signed, so the agent
 * cannot be injected as on simulators); gekko only hands it `GEKKO_PORT` and `GEKKO_TOKEN` when it starts the app. A
 * fresh state means uninstalling and reinstalling the build, which also resets the privacy permissions.
 */
class PhysicalIosDevice(
    private val requestedUdid: String?,
    private val usbmux: Usbmux = Usbmux(),
    private val debug: (String) -> Unit = { println(it) },
) : Device {
    private val devicectl = Devicectl(debug)
    private val udid: String by lazy { canonical(requestedUdid ?: singleUdid()) }

    // The ports are on the device, so they do not need to differ between devices; the formula keeps them stable
    private val agentDevicePort: Int by lazy { SimulatorPorts.agent(udid) }
    private val runnerDevicePort: Int
        get() = agentDevicePort + 1

    private val agentForward by lazy { UsbmuxForward(usbmux, udid, agentDevicePort) }
    private val systemForward by lazy { UsbmuxForward(usbmux, udid, runnerDevicePort) }

    /** The folder of the installed app on the device; changes with every install, so it is looked up again then. */
    private val appRoots = mutableMapOf<String, String>()
    private val resolvedBuilds = mutableMapOf<String, String>()
    private var installedBuild: String? = null
    private var launchArguments: List<String> = emptyList()

    override fun agentPort(config: GekkoConfig): Int {
        return agentForward.port
    }

    override fun systemPort(): Int {
        return systemForward.port
    }

    override val token: String
        get() = UserToken.value

    override val agentHint: String
        get() =
            "On a physical iPhone the agent is not injected: link the GekkoAgent package product into the debug build " +
                "of the app, install that device build, and keep the phone unlocked"

    // Building (on first use), signing and starting the XCUITest runner on a device takes a while
    override val runnerStartTimeoutMs: Long
        get() = 180_000

    private fun device(vararg args: String): List<String> {
        return listOf("device") + args.toList() + listOf("--device", udid)
    }

    private fun bundleId(config: GekkoConfig): String {
        return config.app.ios ?: throw IllegalStateException("app.ios is not set in gekko.yaml")
    }

    private fun tempFile(extension: String): String {
        val directory = env("TMPDIR")?.trimEnd('/') ?: "/tmp"
        return "$directory/gekko-ios-$udid-${nowMillis()}.$extension"
    }

    private fun appsJson(bundle: String): String {
        val result =
            devicectl.require("Looking for $bundle", device("info", "apps") + listOf("--bundle-id", bundle), true)
        return result.json.orEmpty()
    }

    private fun isInstalled(bundle: String): Boolean {
        return DevicectlJson.isInstalled(appsJson(bundle), bundle)
    }

    /** Where the app lives on the device; null when it is not installed (or devicectl does not say where). */
    private fun installedRoot(bundle: String): String? {
        appRoots[bundle]?.let {
            return it
        }
        return DevicectlJson.appRoot(appsJson(bundle), bundle)?.also { appRoots[bundle] = it }
    }

    private fun runningPid(config: GekkoConfig): Long? {
        val root = installedRoot(bundleId(config)) ?: return null
        val processes = devicectl.require("Listing the processes", device("info", "processes"), true)
        return DevicectlJson.processId(processes.json.orEmpty(), root)
    }

    override fun isAppRunning(config: GekkoConfig): Boolean? {
        installedRoot(bundleId(config)) ?: return null
        return runningPid(config) != null
    }

    override fun terminate(config: GekkoConfig) {
        val pid = runningPid(config) ?: return
        devicectl.run(device("process", "terminate") + listOf("--pid", "$pid", "--kill"))
    }

    override fun screenshot(): ByteArray {
        val file = tempFile("png")
        try {
            devicectl.require("Taking a screenshot", device("capture", "screenshot") + listOf("--destination", file))
            val bytes = runProcess(listOf("cat", file), mergeStderr = false).stdout
            check(bytes.isNotEmpty()) { "The screenshot of the device is empty" }
            return bytes
        } finally {
            runProcess(listOf("rm", "-f", file))
        }
    }

    override fun pressKey(key: String) {
        error("pressKey '$key' is not supported on iOS devices")
    }

    override fun openLink(url: String) {
        devicectl.require("Opening $url", device("process", "openURL") + listOf("--", url))
    }

    override fun setDarkMode(enabled: Boolean): () -> Unit {
        val previous = appearance()
        setAppearance(if (enabled) "dark" else "light")
        if (previous == null) {
            debug("The previous appearance of the device could not be read; it keeps the one the test set")
        }
        return {
            if (previous != null) {
                runCatching { setAppearance(previous) }
            }
        }
    }

    private fun appearance(): String? {
        val result = devicectl.run(device("info", "appearance"), wantJson = true)
        val json = result.json?.takeIf { result.exitCode == 0 } ?: return null
        return runCatching { DevicectlJson.appearance(json) }.getOrNull()
    }

    private fun setAppearance(mode: String) {
        devicectl.require("Switching the appearance", device("settings", "appearance") + listOf("--mode", mode))
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        devicectl.require(
            "Setting the location",
            device("simulate", "location", "coordinate") +
                listOf("--latitude", plain(latitude), "--longitude", plain(longitude)),
        )
    }

    /** Ending the simulation must never stop the other restores of a test, so a failure is not thrown. */
    override fun clearLocation() {
        devicectl.run(device("simulate", "location", "clear"))
    }

    override fun setPermissions(config: GekkoConfig, permissions: Map<String, Boolean>) {
        error(
            "setPermissions is not possible on a physical iPhone: answer the prompt with " +
                "`tap: { text: Allow, system: true }`; a fresh start (reinstall) resets all permissions"
        )
    }

    override fun startRecording(): Recording {
        error("screen recording is not available on physical iOS devices")
    }

    override fun applySettings(config: GekkoConfig, settings: DeviceSettings): () -> Unit {
        launchArguments = AppleBuilds.settingsArguments(settings)
        return { launchArguments = emptyList() }
    }

    override fun install(config: GekkoConfig, path: String?) {
        val source =
            path ?: config.ios.deviceApp ?: error("No app to install; pass a path or set ios.deviceApp in gekko.yaml")
        check(fileExists(source)) { "App not found: $source" }
        val app =
            resolvedBuilds.getOrPut(source) {
                if (source.endsWith(".zip") || source.endsWith(".ipa")) {
                    AppleBuilds.appInArchive(source, "/tmp/gekko-app-$udid", bundleId(config))
                } else {
                    source
                }
            }
        val platforms = AppleBuilds.supportedPlatforms(app)
        check("iPhoneOS" in platforms) {
            "$app is not a build for a physical device (platforms: ${platforms.joinToString().ifEmpty { "unknown" }}); " +
                "build it for iphoneos (Debug-iphoneos), signed for this device"
        }
        devicectl.require("Installing $app", device("install", "app") + app, timeoutSeconds = INSTALL_TIMEOUT_SECONDS)
        appRoots.clear()
        installedBuild = source
    }

    /** The app is reinstalled: that is the only way to reset its data and permissions from the outside. */
    override fun clearData(config: GekkoConfig) {
        val bundle = bundleId(config)
        val build =
            installedBuild
                ?: config.ios.deviceApp
                ?: error(
                    "A physical iPhone gets a fresh state by reinstalling the app: set ios.deviceApp or pass --app " +
                        "(a build for iphoneos)"
                )
        terminate(config)
        if (isInstalled(bundle)) {
            devicectl.require("Uninstalling $bundle", device("uninstall", "app") + bundle)
        }
        appRoots.clear()
        install(config, build)
    }

    override fun launch(config: GekkoConfig, arguments: Map<String, String>) {
        val bundle = bundleId(config)
        if (!isInstalled(bundle)) {
            throw InfrastructureException("$bundle is not installed on $udid; install it (gekko install, --app)")
        }
        // The environment of devicectl itself, so the token never shows up on a command line
        val environment =
            mapOf(
                "DEVICECTL_CHILD_GEKKO_PORT" to "$agentDevicePort",
                "DEVICECTL_CHILD_GEKKO_TOKEN" to UserToken.value,
            )
        val extras = arguments.flatMap { (key, value) -> listOf("-$key", value) }
        devicectl.require(
            "Launching $bundle on $udid",
            device("process", "launch") + listOf("--terminate-existing", "--", bundle) + launchArguments + extras,
            environment = environment,
        )
    }

    private fun runnerDir(config: GekkoConfig): String? {
        return env("GEKKO_IOS_DEVICE_RUNNER_DIR")
            ?: config.ios.deviceRunnerDir?.takeUnless { it == InstalledRunners.INSTALLED }
            ?: InstalledRunners.iosDeviceRunnerDir()
    }

    override fun startSystemRunner(config: GekkoConfig, log: (String) -> Unit) {
        val dir =
            runnerDir(config)
                ?: error(
                    "System runner for devices not found; build it with " +
                        "scripts/build-ios-runner.sh --device --team TEAMID --install, or set ios.deviceRunnerDir in gekko.yaml"
                )
        check(fileExists("$dir/GekkoRunner.xctestrun")) { "No GekkoRunner.xctestrun in $dir" }
        log("Starting iOS system runner on the device")
        AppleBuilds.startRunner(dir, udid, bundleId(config), runnerDevicePort)
    }

    override fun runnerDiagnostics(): String? {
        val log = AppleBuilds.tail(AppleBuilds.runnerLog(udid)) ?: return null
        return "$log\n(unlock the phone, check that Developer Mode and Enable UI Automation are on, " +
            "and that the runner was built for this device and team)"
    }

    override fun uninstall(config: GekkoConfig): List<String> {
        AppleBuilds.stopRunner(udid)
        val bundles = (listOf(bundleId(config)) + AppleBuilds.runnerBundleIds(runnerDir(config))).distinct()
        val removed = mutableListOf<String>()
        for (bundle in bundles.filter { isInstalled(it) }) {
            devicectl.require("Uninstalling $bundle", device("uninstall", "app") + bundle)
            removed += bundle
        }
        appRoots.clear()
        runProcess(listOf("rm", "-rf", "/tmp/gekko-app-$udid", AppleBuilds.runnerLog(udid)))
        val left = bundles.filter { isInstalled(it) }
        check(left.isEmpty()) { "Still installed after uninstalling: ${left.joinToString()}" }
        return removed
    }

    private fun canonical(wanted: String): String {
        val known = physicalDevices(devicectl)
        return known.firstOrNull { Usbmux.normalize(it.udid) == Usbmux.normalize(wanted) }?.udid ?: wanted
    }

    private fun singleUdid(): String {
        val attached = attachedUdids()
        return when (attached.size) {
            0 -> error("No iPhone connected over USB")
            1 -> attached[0]
            else -> error("Several iPhones are connected (${attached.joinToString()}); choose one with -s")
        }
    }

    /** Latitude and longitude as plain decimals; devicectl does not read the exponent form of small numbers. */
    private fun plain(value: Double): String {
        val micro = round(value * 1_000_000).toLong()
        val sign = if (micro < 0) "-" else ""
        val magnitude = abs(micro)
        return "$sign${magnitude / 1_000_000}.${(magnitude % 1_000_000).toString().padStart(6, '0')}"
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600

        private var listed: List<PhysicalDeviceInfo>? = null

        /**
         * The physical devices `devicectl` lists; none when devicectl is missing (no Xcode) or fails. It takes a
         * second, so the answer is kept for the life of the process.
         */
        fun physicalDevices(devicectl: Devicectl = Devicectl()): List<PhysicalDeviceInfo> {
            listed?.let {
                return it
            }
            val result = devicectl.run(listOf("list", "devices"), wantJson = true)
            val json = result.json?.takeIf { result.exitCode == 0 }
            val devices =
                json?.let { runCatching { DevicectlJson.physicalDevices(it) }.getOrDefault(emptyList()) } ?: emptyList()
            listed = devices
            return devices
        }

        /** The UDIDs of the iPhones on a USB cable; a device only on Wi-Fi cannot be reached and is left out. */
        fun attachedUdids(): List<String> {
            val known = physicalDevices()
            if (known.isEmpty()) return emptyList()
            val usbmux = Usbmux()
            val usb =
                try {
                    runBlocking { usbmux.devices() }.filter { it.isUsb }.map { it.serial }
                } catch (error: Exception) {
                    null
                } finally {
                    usbmux.close()
                }
            // When usbmuxd cannot be asked, every physical device devicectl lists counts
            return known.filter { usb == null || Usbmux.normalize(it.udid) in usb }.map { it.udid }
        }

        /** A UDID is 24 hex digits (a dash after the eighth is optional) or 40 for old devices; no adb serial is. */
        fun looksLikeUdid(value: String): Boolean {
            val digits = Usbmux.normalize(value)
            return (digits.length == 24 || digits.length == 40) && digits.all { it in '0'..'9' || it in 'A'..'F' }
        }
    }
}

/** Chooses between a simulator and a physical device for a serial or UDID. */
object IosDevices {
    /** True when [serial] names an iPhone on a USB cable (and not a booted simulator). */
    fun isPhysical(serial: String): Boolean {
        if (!PhysicalIosDevice.looksLikeUdid(serial) || serial in SimctlDevice.bootedUdids()) return false
        val wanted = Usbmux.normalize(serial)
        return PhysicalIosDevice.attachedUdids().any { Usbmux.normalize(it) == wanted }
    }

    fun forSerial(serial: String?): Device {
        if (serial != null) {
            return if (isPhysical(serial)) PhysicalIosDevice(serial) else SimctlDevice(serial)
        }
        val booted = SimctlDevice.bootedUdids()
        // A booted simulator is what `--platform ios` always meant; physical devices are only asked when it is not
        // clear
        if (booted.size == 1) return SimctlDevice(null)
        val physical = PhysicalIosDevice.attachedUdids()
        return when {
            physical.isEmpty() -> SimctlDevice(null)
            booted.isEmpty() && physical.size == 1 -> PhysicalIosDevice(physical[0])
            else ->
                error(
                    "Several iOS devices are available (simulators: ${booted.joinToString().ifEmpty { "none" }}; " +
                        "iPhones: ${physical.joinToString()}); choose one with -s"
                )
        }
    }
}
