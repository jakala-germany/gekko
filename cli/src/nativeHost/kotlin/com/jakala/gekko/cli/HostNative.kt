@file:OptIn(ExperimentalNativeApi::class)

package com.jakala.gekko.cli

import com.jakala.gekko.host.gekko_free
import com.jakala.gekko.host.gekko_getcwd
import com.jakala.gekko.host.gekko_getenv
import com.jakala.gekko.host.gekko_init_host
import com.jakala.gekko.host.gekko_is_alive
import com.jakala.gekko.host.gekko_is_executable_file
import com.jakala.gekko.host.gekko_pid
import com.jakala.gekko.host.gekko_random_bytes
import com.jakala.gekko.host.gekko_restrict_to_owner
import com.jakala.gekko.host.gekko_run
import com.jakala.gekko.host.gekko_spawn_detached
import com.jakala.gekko.host.gekko_temp_dir
import com.jakala.gekko.host.gekko_utc_offset_minutes
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path.Companion.toPath

/*
 * The part of the platform layer that talks to the host layer in C (cli/src/interop/host-*.def). The same source is
 * compiled for macOS, Linux and Windows, because the C functions have the same signatures on all of them.
 */

actual val isWindowsHost: Boolean = Platform.osFamily == OsFamily.WINDOWS

private const val ERROR_NOT_SUPPORTED = 50

private fun takeString(pointer: CPointer<ByteVar>?): String? {
    if (pointer == null) {
        return null
    }
    val text = pointer.toKString()
    gekko_free(pointer)
    return text
}

/** Windows takes `\` in the paths it is handed by gekko most reliably; everywhere else nothing changes. */
private fun native(path: String): String {
    return if (isWindowsHost) path.replace('/', '\\') else path
}

actual fun env(name: String): String? {
    return takeString(gekko_getenv(name))
}

actual fun currentDirectory(): String {
    return slashes(takeString(gekko_getcwd()).orEmpty())
}

actual fun tempDirectory(): String {
    val directory = takeString(gekko_temp_dir()) ?: return "/tmp"
    return slashes(directory).trimEnd('/').ifEmpty { "/tmp" }
}

actual fun hostUtcOffsetMinutes(): Int {
    return gekko_utc_offset_minutes()
}

actual fun initializeHost() {
    gekko_init_host()
}

actual fun randomHex(byteCount: Int): String {
    if (byteCount <= 0) {
        return ""
    }
    val bytes = ByteArray(byteCount)
    val status = bytes.usePinned { gekko_random_bytes(it.addressOf(0), byteCount) }
    check(status == 0) { "the operating system has no secure random numbers" }
    return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

actual fun restrictToOwner(path: String) {
    val directory = FileSystem.SYSTEM.metadataOrNull(path.toPath())?.isDirectory == true
    val status = gekko_restrict_to_owner(native(path), if (directory) 1 else 0)
    // chmod is best effort, as it always was; on Windows a path that stays readable for others is a failure, unless the
    // file system has no ACLs at all (FAT, exFAT), where POSIX chmod is as powerless
    check(status == 0 || status == ERROR_NOT_SUPPORTED || !isWindowsHost) {
        "cannot restrict $path to its owner (Windows error $status)"
    }
}

actual fun isExecutableFile(path: String): Boolean {
    return gekko_is_executable_file(native(path)) != 0
}

actual fun currentProcessId(): Long {
    return gekko_pid()
}

actual fun isProcessAlive(pid: Long): Boolean {
    return gekko_is_alive(pid) != 0
}

private fun notFound(program: String): ProcessOutcome {
    return ProcessOutcome(127, "cannot run '$program': No such file or directory".encodeToByteArray(), false)
}

/** A NULL-terminated `char **` for the C functions. */
private fun MemScope.argv(values: List<String>): CPointer<CPointerVar<ByteVar>> {
    val array = allocArray<CPointerVar<ByteVar>>(values.size + 1)
    values.forEachIndexed { index, value -> array[index] = value.cstr.getPointer(this) }
    array[values.size] = null
    return array
}

/** The environment of a call as NULL-terminated name, value, name, value, ... */
private fun MemScope.environmentPairs(environment: Map<String, String>): CPointer<CPointerVar<ByteVar>> {
    return argv(environment.flatMap { (name, value) -> listOf(name, value) })
}

actual fun executeProcess(request: ProcessRequest): ProcessOutcome {
    val launch = launchOf(request.command) ?: return notFound(request.command.first())
    return memScoped {
        val output = alloc<CPointerVar<ByteVar>>()
        val length = alloc<LongVar>()
        val exitCode = alloc<IntVar>()
        val input = request.stdin?.takeIf { it.isNotEmpty() }
        val status =
            gekko_run(
                native(launch.application),
                argv(launch.argv),
                launch.commandLine,
                environmentPairs(request.environment),
                request.workingDirectory?.let { native(it) },
                input?.refTo(0),
                input?.size?.toLong() ?: 0L,
                if (request.mergeStderr) 1 else 0,
                request.timeoutSeconds,
                output.ptr,
                length.ptr,
                exitCode.ptr,
            )
        val bytes = output.value?.readBytes(length.value.toInt()) ?: ByteArray(0)
        gekko_free(output.value)
        when (status) {
            0 -> ProcessOutcome(exitCode.value, bytes, false)
            1 -> ProcessOutcome(-1, bytes, true)
            else -> ProcessOutcome(127, bytes, false)
        }
    }
}

actual fun spawnDetached(command: List<String>, environment: Map<String, String>, logFile: String): Boolean {
    val launch = launchOf(command) ?: return false
    return memScoped {
        gekko_spawn_detached(
            native(launch.application),
            argv(launch.argv),
            launch.commandLine,
            environmentPairs(environment),
            native(logFile),
        ) == 0
    }
}
