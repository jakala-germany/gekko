package com.jakala.gekko.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** One physical device as `devicectl list devices` reports it. */
class PhysicalDeviceInfo(val udid: String, val name: String)

/**
 * Reads the JSON that `xcrun devicectl ... --json-output` writes. The format is versioned by Apple but not documented
 * in detail, and it has changed between Xcode releases (`properties` replaced `hardwareProperties` and friends), so
 * every lookup tries the current key first, then the older ones, and a value that is not there is null instead of a
 * crash.
 */
object DevicectlJson {
    private val json = Json { ignoreUnknownKeys = true }

    private fun result(text: String): JsonObject {
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("devicectl wrote no JSON object")
        return root["result"] as? JsonObject ?: error("devicectl wrote no result: ${text.take(200)}")
    }

    private fun JsonElement?.obj(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) {
            current = (current as? JsonObject)?.get(key)
        }
        return current
    }

    private fun JsonElement?.text(vararg keys: String): String? {
        return (obj(*keys) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    private fun JsonElement?.items(key: String): List<JsonElement> {
        return (obj(key) as? JsonArray)?.toList().orEmpty()
    }

    /** Every string in the element, nested ones included, with the key it was found under. */
    private fun strings(element: JsonElement?, key: String = ""): List<Pair<String, String>> {
        return when (element) {
            is JsonObject -> element.flatMap { (name, value) -> strings(value, name) }
            is JsonArray -> element.flatMap { strings(it, key) }
            is JsonPrimitive -> if (element.isString) listOf(key to element.content) else emptyList()
            else -> emptyList()
        }
    }

    /** The physical iPhones and iPads, in whatever connection state; simulators are left out. */
    fun physicalDevices(text: String): List<PhysicalDeviceInfo> {
        return result(text).items("devices").mapNotNull { device ->
            val hardware = device.obj("properties", "hardware") ?: device.obj("hardwareProperties")
            val reality = hardware.text("reality") ?: return@mapNotNull null
            val platform = hardware.text("platform").orEmpty()
            if (!reality.equals("physical", ignoreCase = true) || !platform.equals("iOS", ignoreCase = true)) {
                return@mapNotNull null
            }
            val udid = hardware.text("udid") ?: device.text("identifier") ?: return@mapNotNull null
            val name =
                device.text("properties", "device", "name")
                    ?: device.text("deviceProperties", "name")
                    ?: hardware.text("marketingName")
                    ?: udid
            PhysicalDeviceInfo(udid, name)
        }
    }

    private fun appOf(text: String, bundleId: String): JsonElement? {
        return result(text).items("apps").firstOrNull { it.text("bundleIdentifier") == bundleId }
    }

    /** The folder of an installed app on the device (`/var/containers/Bundle/Application/<id>/Name.app`), or null. */
    fun appRoot(text: String, bundleId: String): String? {
        val app = appOf(text, bundleId) ?: return null
        val url =
            app.text("url")
                ?: app.text("installationURL")
                ?: strings(app).firstOrNull { (_, value) -> value.startsWith("file://") && ".app" in value }?.second
                ?: return null
        return pathOf(url)
    }

    /** True when `info apps` lists the bundle id. */
    fun isInstalled(text: String, bundleId: String): Boolean {
        return appOf(text, bundleId) != null
    }

    /** `file:///private/var/...` and `/var/...` are the same place; compare paths without scheme, prefix and slash. */
    fun pathOf(url: String): String {
        return url.removePrefix("file://").removePrefix("/private").trimEnd('/')
    }

    /** The pid of the running process whose executable lies inside [appRoot], or null when the app is not running. */
    fun processId(text: String, appRoot: String): Long? {
        val root = pathOf(appRoot)
        for (process in result(text).items("runningProcesses")) {
            val executable =
                strings(process).firstOrNull { (key, value) ->
                    key.contains("executable", ignoreCase = true) && pathOf(value).startsWith("$root/")
                }
            if (executable != null) {
                val pid = process.obj("processIdentifier") ?: process.obj("pid")
                return (pid as? JsonPrimitive)?.longOrNull
            }
        }
        return null
    }

    /** `light` or `dark`, or null when the answer has no such value. */
    fun appearance(text: String): String? {
        val wanted = listOf("style", "mode", "appearance", "interface")
        return strings(result(text))
            .filter { (key, _) -> wanted.any { key.contains(it, ignoreCase = true) } }
            .map { it.second.lowercase() }
            .firstOrNull { it == "light" || it == "dark" }
    }
}

/** What one `xcrun devicectl` call returned: the exit code, the text it printed and the JSON it wrote, when asked. */
class DevicectlResult(val exitCode: Int, val text: String, val json: String?)

/** Runs `xcrun devicectl` against one physical device. Every call is logged under `GEKKO_DEBUG`. */
class Devicectl(private val log: (String) -> Unit = {}) {
    private var counter = 0

    private fun jsonPath(): String {
        val directory = env("TMPDIR")?.trimEnd('/') ?: "/tmp"
        counter++
        return "$directory/gekko-devicectl-${nowMillis()}-$counter.json"
    }

    /**
     * Runs devicectl with [args] (everything after `devicectl`). With [wantJson] the machine readable result is read
     * back into [DevicectlResult.json]. [environment] reaches devicectl only, never its command line.
     */
    fun run(
        args: List<String>,
        wantJson: Boolean = false,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = PROCESS_TIMEOUT_SECONDS,
    ): DevicectlResult {
        val file = if (wantJson) jsonPath() else null
        val command = listOf("xcrun", "devicectl") + args + (file?.let { listOf("--json-output", it) } ?: emptyList())
        if (env("GEKKO_DEBUG") != null) {
            log("    [devicectl] ${command.drop(2).joinToString(" ")}")
        }
        val result = runProcess(command, environment = environment, timeoutSeconds = timeoutSeconds)
        val json = file?.takeIf { fileExists(it) }?.let { readTextFile(it) }
        if (file != null) runProcess(listOf("rm", "-f", file))
        return DevicectlResult(result.exitCode, result.text, json)
    }

    /** Like [run] but a failure is an [InfrastructureException] that carries what devicectl said. */
    fun require(
        what: String,
        args: List<String>,
        wantJson: Boolean = false,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = PROCESS_TIMEOUT_SECONDS,
    ): DevicectlResult {
        val result = run(args, wantJson, environment, timeoutSeconds)
        if (result.exitCode != 0) {
            throw InfrastructureException(
                "$what failed (devicectl exit ${result.exitCode}): ${result.text.takeLast(1500)}"
            )
        }
        if (wantJson && result.json == null) {
            throw InfrastructureException("$what: devicectl wrote no JSON result: ${result.text.takeLast(500)}")
        }
        return result
    }
}
