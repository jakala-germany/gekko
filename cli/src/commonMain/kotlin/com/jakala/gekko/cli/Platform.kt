package com.jakala.gekko.cli

class ProcessResult(val exitCode: Int, val stdout: ByteArray) {
    val text: String
        get() = stdout.decodeToString().trim()
}

/** Runs [command] once, without a time limit; use [runProcess]. */
expect fun runProcessUnlimited(
    command: List<String>,
    mergeStderr: Boolean = true,
    environment: Map<String, String> = emptyMap(),
): ProcessResult

/** Exit code of a process that was killed by SIGALRM: the time limit ran out. */
private const val EXIT_TIMED_OUT = 142

/** The time limit of every process gekko starts (adb, simctl, unzip, ...), unless the caller names another. */
const val PROCESS_TIMEOUT_SECONDS = 300

/**
 * Runs [command] and waits at most [timeoutSeconds]; a hung adb or simctl must not hang the whole run, it fails with an
 * [InfrastructureException]. [environment] is added to the environment of the process only, never to its command line.
 */
fun runProcess(
    command: List<String>,
    mergeStderr: Boolean = true,
    environment: Map<String, String> = emptyMap(),
    timeoutSeconds: Int = PROCESS_TIMEOUT_SECONDS,
): ProcessResult {
    val limited = listOf("perl", "-e", "alarm shift; exec @ARGV", timeoutSeconds.toString()) + command
    val result = runProcessUnlimited(limited, mergeStderr, environment)
    if (result.exitCode == EXIT_TIMED_OUT) {
        throw InfrastructureException("${command.firstOrNull() ?: "command"} did not finish within ${timeoutSeconds}s")
    }
    return result
}

expect fun env(name: String): String?

expect fun readTextFile(path: String): String

expect fun writeFile(path: String, bytes: ByteArray)

expect fun fileExists(path: String): Boolean

expect fun listFilesRecursive(dir: String): List<String>

expect fun makeDirs(path: String)

expect fun currentDirectory(): String

fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\\''") + "'"
}

fun writeTextFile(path: String, text: String) {
    writeFile(path, text.encodeToByteArray())
}

expect fun nowMillis(): Long

/** [byteCount] random bytes from the operating system's secure source, as hex. */
expect fun randomHex(byteCount: Int): String

/** Makes a file readable and writable by its owner only (a directory: accessible by its owner only). */
expect fun restrictToOwner(path: String)
