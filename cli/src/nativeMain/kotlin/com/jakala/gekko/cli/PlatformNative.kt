package com.jakala.gekko.cli

import kotlin.concurrent.AtomicInt
import kotlin.time.Clock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.chmod
import platform.posix.fread
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen
import platform.posix.setenv
import platform.posix.unsetenv

private const val CHUNK = 65536

/** The child of popen inherits the environment of this process, so it is changed only while the child starts. */
private val environmentLock = AtomicInt(0)

private fun <T> withEnvironment(environment: Map<String, String>, block: () -> T): T {
    if (environment.isEmpty()) {
        return block()
    }
    while (!environmentLock.compareAndSet(0, 1)) {
        // another process is starting with its own environment
    }
    try {
        environment.forEach { (name, value) -> setenv(name, value, 1) }
        return block()
    } finally {
        environment.keys.forEach { unsetenv(it) }
        environmentLock.value = 0
    }
}

actual fun runProcessUnlimited(
    command: List<String>,
    mergeStderr: Boolean,
    environment: Map<String, String>,
): ProcessResult {
    val suffix = if (mergeStderr) " 2>&1" else " 2>/dev/null"
    val pipe =
        withEnvironment(environment) { popen(command.joinToString(" ") { shellQuote(it) } + suffix, "r") }
            ?: return ProcessResult(-1, ByteArray(0))
    val chunks = mutableListOf<ByteArray>()
    memScoped {
        val buffer = allocArray<ByteVar>(CHUNK)
        while (true) {
            val count = fread(buffer, 1u, CHUNK.convert(), pipe).toInt()
            if (count <= 0) {
                break
            }
            chunks += buffer.readBytes(count)
        }
    }
    val status = pclose(pipe)
    val output = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(output, offset)
        offset += chunk.size
    }
    // A process killed by a signal has no exit code: report it like a shell does, 128 + the signal
    val signal = status and 0x7f
    return ProcessResult(if (signal != 0) 128 + signal else (status shr 8) and 0xff, output)
}

actual fun env(name: String): String? {
    return getenv(name)?.toKString()
}

actual fun readTextFile(path: String): String {
    return FileSystem.SYSTEM.read(path.toPath()) { readUtf8() }
}

actual fun writeFile(path: String, bytes: ByteArray) {
    val target = path.toPath()
    target.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    FileSystem.SYSTEM.write(target) { write(bytes) }
}

actual fun fileExists(path: String): Boolean {
    return FileSystem.SYSTEM.exists(path.toPath())
}

actual fun listFilesRecursive(dir: String): List<String> {
    return FileSystem.SYSTEM.listRecursively(dir.toPath())
        .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
        .map { it.toString() }
        .sorted()
        .toList()
}

actual fun makeDirs(path: String) {
    FileSystem.SYSTEM.createDirectories(path.toPath())
}

actual fun currentDirectory(): String {
    return memScoped {
        val buffer = allocArray<ByteVar>(CHUNK)
        getcwd(buffer, CHUNK.convert())?.toKString().orEmpty()
    }
}

actual fun nowMillis(): Long {
    return Clock.System.now().toEpochMilliseconds()
}

actual fun randomHex(byteCount: Int): String {
    val bytes = FileSystem.SYSTEM.read("/dev/urandom".toPath()) { readByteArray(byteCount.toLong()) }
    return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

actual fun restrictToOwner(path: String) {
    val directory = FileSystem.SYSTEM.metadataOrNull(path.toPath())?.isDirectory == true
    chmod(path, if (directory) 448u else 384u)
}
