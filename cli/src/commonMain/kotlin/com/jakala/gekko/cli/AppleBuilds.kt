package com.jakala.gekko.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What simulators and physical iPhones share: builds, the XCUITest runner and the launch arguments of a test. */
internal object AppleBuilds {
    const val RUNNER_HOST_BUNDLE = "com.jakala.gekko.runnerhost"
    const val RUNNER_TESTS_BUNDLE = "com.jakala.gekko.runneruitests.xctrunner"
    private const val XCTESTRUN = "GekkoRunner.xctestrun"

    /** Unzips an archive of build products (`.zip`, `.ipa`) and returns the `.app` with the bundle id [wanted]. */
    fun appInArchive(archive: String, target: String, wanted: String): String {
        runProcess(listOf("rm", "-rf", target))
        val unzipped = runProcess(listOf("unzip", "-q", "-o", archive, "-d", target))
        check(unzipped.exitCode == 0) { "Unzipping $archive failed: ${unzipped.text}" }
        val candidates =
            runProcess(listOf("find", target, "-name", "*.app", "-type", "d", "-not", "-path", "*.app/*"))
                .text
                .lines()
                .filter { it.isNotBlank() }
        check(candidates.isNotEmpty()) { "No .app found in $archive" }
        return candidates.firstOrNull { bundleIdOf(it) == wanted }
            ?: error(
                "No app with bundle id $wanted in $archive (found: ${candidates.joinToString { bundleIdOf(it).orEmpty() }})"
            )
    }

    fun bundleIdOf(app: String): String? {
        val result = runProcess(listOf("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleIdentifier", "$app/Info.plist"))
        return if (result.exitCode == 0) result.text else null
    }

    /** `iPhoneOS` for a build that runs on a device, `iPhoneSimulator` for one that runs on a simulator. */
    fun supportedPlatforms(app: String): List<String> {
        val result =
            runProcess(listOf("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleSupportedPlatforms", "$app/Info.plist"))
        if (result.exitCode != 0) return emptyList()
        return result.text.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "}" && !it.startsWith("Array") }
    }

    /**
     * iOS has no per-app language switch from outside; the app gets it as launch arguments (user defaults argument
     * domain).
     */
    fun settingsArguments(settings: DeviceSettings): List<String> {
        val arguments = mutableListOf<String>()
        settings.language?.let { tag ->
            arguments += listOf("-AppleLanguages", "($tag)", "-AppleLocale", tag.replace('-', '_'))
        }
        settings.timeFormat?.let { arguments += listOf("-AppleICUForce24HourTime", if (it == "24h") "YES" else "NO") }
        return arguments
    }

    fun runnerLog(udid: String): String {
        return "/tmp/gekko-ios-runner-$udid.log"
    }

    /** Stops the runner of this device that an earlier call started; a second start would kill the first anyway. */
    fun stopRunner(udid: String) {
        runProcess(listOf("pkill", "-f", "xcodebuild test-without-building.*id=$udid"))
    }

    /** Starts the XCUITest runner of [runnerDir] on [udid] in the background; it serves [port] until it is killed. */
    fun startRunner(runnerDir: String, udid: String, bundle: String, port: Int) {
        stopRunner(udid)
        runProcess(
            listOf(
                "sh",
                "-c",
                "nohup xcodebuild test-without-building " +
                    "-xctestrun ${shellQuote("$runnerDir/$XCTESTRUN")} -destination ${shellQuote("id=$udid")} " +
                    ">${runnerLog(udid)} 2>&1 &",
            ),
            environment =
                mapOf(
                    "TEST_RUNNER_GEKKO_TARGET_BUNDLE_ID" to bundle,
                    "TEST_RUNNER_GEKKO_PORT" to "$port",
                    "TEST_RUNNER_GEKKO_TOKEN" to UserToken.value,
                ),
        )
    }

    /** The last [count] lines of a log, or null when there is none; shows why a runner did not start. */
    fun tail(path: String, count: Int = 15): String? {
        if (!fileExists(path)) return null
        return readTextFile(path).lines().filter { it.isNotBlank() }.takeLast(count).joinToString("\n").ifEmpty { null }
    }

    /**
     * The bundle ids of the apps the runner of [runnerDir] puts on a device (its host app and its test runner), read
     * from the xctestrun: a runner built for a team carries that team's prefix. Falls back to the ones of a release.
     */
    fun runnerBundleIds(runnerDir: String?): List<String> {
        val fallback = listOf(RUNNER_HOST_BUNDLE, RUNNER_TESTS_BUNDLE)
        val file = runnerDir?.let { "$it/$XCTESTRUN" }?.takeIf { fileExists(it) } ?: return fallback
        val converted = runProcess(listOf("plutil", "-convert", "json", "-o", "-", file), mergeStderr = false)
        if (converted.exitCode != 0) return fallback
        return bundleIdsIn(converted.text, runnerDir).ifEmpty { fallback }
    }

    /** The bundle ids an xctestrun (as JSON) names: the test host of every target and the app it tests. */
    fun bundleIdsIn(xctestrunJson: String, runnerDir: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(xctestrunJson) }.getOrNull() ?: return emptyList()
        val found = mutableListOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject ->
                    element.forEach { (key, value) ->
                        if (value is JsonPrimitive && value.isString) {
                            if (key == "TestHostBundleIdentifier") found += value.content
                            if (key == "UITargetAppPath") {
                                bundleIdOf(value.content.replace("__TESTROOT__", runnerDir))?.let { found += it }
                            }
                        } else {
                            walk(value)
                        }
                    }
                is JsonArray -> element.forEach { walk(it) }
                else -> {}
            }
        }
        walk(root)
        return found.distinct()
    }
}
