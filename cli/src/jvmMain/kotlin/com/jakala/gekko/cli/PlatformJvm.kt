package com.jakala.gekko.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

actual fun runProcessUnlimited(
    command: List<String>,
    mergeStderr: Boolean,
    environment: Map<String, String>,
): ProcessResult {
    val builder = ProcessBuilder(command)
    builder.environment().putAll(environment)
    if (mergeStderr) {
        builder.redirectErrorStream(true)
    } else {
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    }
    val process = builder.start()
    val output = process.inputStream.readBytes()
    return ProcessResult(process.waitFor(), output)
}

actual fun env(name: String): String? {
    return System.getenv(name)
}

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
    return File(dir).walkTopDown().filter { it.isFile }.map { it.path }.sorted().toList()
}

actual fun makeDirs(path: String) {
    File(path).mkdirs()
}

actual fun currentDirectory(): String {
    return File("").absolutePath
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
    val permissions = if (target.isDirectory) "rwx------" else "rw-------"
    Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(permissions))
}
