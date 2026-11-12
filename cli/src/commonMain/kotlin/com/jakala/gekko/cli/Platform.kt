package com.jakala.gekko.cli

class ProcessResult(val exitCode: Int, val stdout: ByteArray) {
    val text: String
        get() = stdout.decodeToString().trim()
}

/** What [executeProcess] runs. [stdin] is what the program reads (none: it reads nothing, not the terminal). */
class ProcessRequest(
    val command: List<String>,
    val mergeStderr: Boolean,
    val environment: Map<String, String>,
    val timeoutSeconds: Int,
    val workingDirectory: String?,
    val stdin: ByteArray?,
)

/** What came back: [timedOut] means the time limit ran out and the program was killed. */
class ProcessOutcome(val exitCode: Int, val output: ByteArray, val timedOut: Boolean)

/**
 * Starts the program without a shell, waits at most [ProcessRequest.timeoutSeconds] and kills it (on Windows with
 * everything it started) when that runs out. A program that cannot be started is an exit code 127 and the reason as
 * output, like a shell reports it.
 */
expect fun executeProcess(request: ProcessRequest): ProcessOutcome

/**
 * Starts [command] so that it keeps running after this process ends: no input, its output appended to [logFile]. False
 * when it could not even be handed over; a program that cannot be executed only shows in the log.
 */
expect fun spawnDetached(command: List<String>, environment: Map<String, String>, logFile: String): Boolean

/** A regular file that can be started: on POSIX with the execute permission, on Windows any file that is no folder. */
expect fun isExecutableFile(path: String): Boolean

/** The id of this process. */
expect fun currentProcessId(): Long

/** Whether a process with this id runs right now. */
expect fun isProcessAlive(pid: Long): Boolean

/** True on a Windows host, where paths have drive letters and programs are found through `PATHEXT`. */
expect val isWindowsHost: Boolean

/** The time limit of every process gekko starts (adb, simctl, unzip, ...), unless the caller names another. */
const val PROCESS_TIMEOUT_SECONDS = 300

/**
 * Runs [command] and waits at most [timeoutSeconds]; a hung adb or simctl must not hang the whole run, it fails with an
 * [InfrastructureException]. [environment] is added to the environment of the process only, never to its command line.
 * [stdin] is passed to the program on its standard input, [workingDirectory] is where it runs.
 */
fun runProcess(
    command: List<String>,
    mergeStderr: Boolean = true,
    environment: Map<String, String> = emptyMap(),
    timeoutSeconds: Int = PROCESS_TIMEOUT_SECONDS,
    workingDirectory: String? = null,
    stdin: ByteArray? = null,
): ProcessResult {
    val outcome =
        executeProcess(ProcessRequest(command, mergeStderr, environment, timeoutSeconds, workingDirectory, stdin))
    if (outcome.timedOut) {
        throw InfrastructureException("${command.firstOrNull() ?: "command"} did not finish within ${timeoutSeconds}s")
    }
    return ProcessResult(outcome.exitCode, outcome.output)
}

/**
 * Runs [command] with the short limit [timeoutSeconds] and once more when that runs out. For a call that is normally
 * instant but hangs now and then (seen with `simctl terminate` on a freshly booted CI simulator): it costs
 * [timeoutSeconds], not the whole [PROCESS_TIMEOUT_SECONDS]. A second time out fails like [runProcess] does.
 */
fun runProcessRetrying(command: List<String>, timeoutSeconds: Int): ProcessResult {
    return try {
        runProcess(command, timeoutSeconds = timeoutSeconds)
    } catch (error: InfrastructureException) {
        runProcess(command, timeoutSeconds = timeoutSeconds)
    }
}

expect fun env(name: String): String?

/** The temporary directory, with `/` as separator and without a trailing one. */
expect fun tempDirectory(): String

/** The offset of the time zone of this host to UTC, in minutes (+120 for CEST). */
expect fun hostUtcOffsetMinutes(): Int

/** Deletes a file, or a directory with everything in it; nothing happens when it does not exist. */
expect fun removeRecursively(path: String)

/** Prepares the process: on Windows the console writes and reads UTF-8. */
expect fun initializeHost()

/** The home directory of the user: `USERPROFILE` on Windows, `HOME` everywhere else. */
fun userHome(): String? {
    val home = if (isWindowsHost) env("USERPROFILE") ?: env("HOME") else env("HOME")
    return home?.takeIf { it.isNotBlank() }?.let { slashes(it) }
}

/** Where gekko keeps its token, the installed runners and what the web platform needs: `GEKKO_HOME` or `~/.gekko`. */
fun gekkoHome(): String? {
    return env("GEKKO_HOME")?.let { slashes(it) } ?: userHome()?.let { "${it.trimEnd('/')}/.gekko" }
}

/** Paths are `/`-separated inside gekko on every host; Windows accepts that everywhere. */
fun slashes(path: String): String {
    return if (isWindowsHost) path.replace('\\', '/') else path
}

expect fun readTextFile(path: String): String

expect fun writeFile(path: String, bytes: ByteArray)

expect fun fileExists(path: String): Boolean

expect fun listFilesRecursive(dir: String): List<String>

expect fun makeDirs(path: String)

expect fun currentDirectory(): String

/** Quotes [value] for a POSIX shell: the shell of an Android device (`adb shell`) and of the macOS host scripts. */
fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\\''") + "'"
}

fun writeTextFile(path: String, text: String) {
    writeFile(path, text.encodeToByteArray())
}

/**
 * A new directory in the temporary directory (`/tmp`, on Windows the user's temp folder) that only this user can enter
 * and whose name nobody can guess, for files that hold secrets: another user can neither plant a link at the path
 * before the file is written nor read the file afterwards.
 */
fun createPrivateDirectory(prefix: String): String {
    val path = "${tempDirectory()}/$prefix-${randomHex(8)}"
    makeDirs(path)
    restrictToOwner(path)
    return path
}

expect fun nowMillis(): Long

/** [byteCount] random bytes from the operating system's secure source, as hex. */
expect fun randomHex(byteCount: Int): String

/** Makes a file readable and writable by its owner only (a directory: accessible by its owner only). */
expect fun restrictToOwner(path: String)
