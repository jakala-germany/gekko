package com.jakala.gekko.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.TimeZone
import java.util.concurrent.TimeUnit

actual val isWindowsHost: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

private val nullDevice = File(if (isWindowsHost) "NUL" else "/dev/null")

private fun launchOf(command: List<String>): Launch? {
    return try {
        planLaunch(command, isWindowsHost, ::env, ::fileExists)
    } catch (error: IllegalArgumentException) {
        throw InfrastructureException(error.message ?: "cannot start ${command.first()}")
    }
}

actual fun executeProcess(request: ProcessRequest): ProcessOutcome {
    val launch =
        launchOf(request.command)
            ?: return ProcessOutcome(
                127,
                "cannot run '${request.command.first()}': No such file or directory".encodeToByteArray(),
                false,
            )
    val builder = ProcessBuilder(launch.argv)
    builder.environment().putAll(request.environment)
    request.workingDirectory?.let { builder.directory(File(it)) }
    if (request.mergeStderr) {
        builder.redirectErrorStream(true)
    } else {
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    }
    if (request.stdin == null) {
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice))
    }
    val process =
        try {
            builder.start()
        } catch (error: java.io.IOException) {
            return ProcessOutcome(
                127,
                "cannot run '${request.command.first()}': ${error.message}".encodeToByteArray(),
                false,
            )
        }
    request.stdin?.let { input ->
        Thread {
            runCatching { process.outputStream.use { it.write(input) } }
        }
            .apply { isDaemon = true }
            .start()
    }
    val collected = ByteArrayOutputStream()
    val reader = Thread { runCatching { process.inputStream.copyTo(collected) } }.apply { isDaemon = true }
    reader.start()
    val finished = process.waitFor(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
    if (!finished) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor()
    }
    // A program that started something that keeps the pipe open must not hold this call up
    reader.join(2_000)
    val output = synchronized(collected) { collected.toByteArray() }
    return ProcessOutcome(if (finished) process.exitValue() else -1, output, !finished)
}

actual fun spawnDetached(command: List<String>, environment: Map<String, String>, logFile: String): Boolean {
    val launch = launchOf(command) ?: return false
    val builder = ProcessBuilder(launch.argv)
    builder.environment().putAll(environment)
    builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice))
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(File(logFile)))
    builder.redirectErrorStream(true)
    return runCatching { builder.start() }.isSuccess
}

actual fun currentProcessId(): Long {
    return ProcessHandle.current().pid()
}

actual fun isProcessAlive(pid: Long): Boolean {
    return pid > 0 && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}

actual fun env(name: String): String? {
    return System.getenv(name)
}

actual fun tempDirectory(): String {
    val directory = if (isWindowsHost) System.getProperty("java.io.tmpdir") else "/tmp"
    return slashes(directory).trimEnd('/').ifEmpty { "/tmp" }
}

actual fun hostUtcOffsetMinutes(): Int {
    return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
}

actual fun initializeHost() {}

actual fun readTextFile(path: String): String {
    return File(path).readText()
}

actual fun writeFile(path: String, bytes: ByteArray) {
    File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
}

actual fun fileExists(path: String): Boolean {
    return File(path).exists()
}

actual fun listFilesRecursive(dir: String): List<String> {
    return File(dir).walkTopDown().filter { it.isFile }.map { slashes(it.path) }.sorted().toList()
}

actual fun makeDirs(path: String) {
    File(path).mkdirs()
}

actual fun removeRecursively(path: String) {
    File(path).deleteRecursively()
}

actual fun currentDirectory(): String {
    return slashes(File("").absolutePath)
}

actual fun nowMillis(): Long {
    return System.currentTimeMillis()
}

actual fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

actual fun restrictToOwner(path: String) {
    val target = File(path)
    if (isWindowsHost) {
        restrictWithAcl(target)
        return
    }
    val permissions = if (target.isDirectory) "rwx------" else "rw-------"
    Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(permissions))
}

/** NTFS has no POSIX permissions; Java can replace the ACL but not stop it from inheriting (the native CLI does). */
private fun restrictWithAcl(target: File) {
    val view = Files.getFileAttributeView(target.toPath(), AclFileAttributeView::class.java) ?: return
    val flags = if (target.isDirectory) setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) else emptySet()
    val entry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(view.owner)
            .setPermissions(AclEntryPermission.values().toSet())
            .setFlags(flags)
            .build()
    view.acl = listOf(entry)
}
