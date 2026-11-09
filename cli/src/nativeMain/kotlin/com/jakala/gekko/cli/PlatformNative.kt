package com.jakala.gekko.cli

import kotlin.time.Clock
import okio.FileSystem
import okio.Path.Companion.toPath

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
        .map { slashes(it.toString()) }
        .sorted()
        .toList()
}

actual fun makeDirs(path: String) {
    FileSystem.SYSTEM.createDirectories(path.toPath())
}

actual fun removeRecursively(path: String) {
    FileSystem.SYSTEM.deleteRecursively(path.toPath(), mustExist = false)
}

actual fun nowMillis(): Long {
    return Clock.System.now().toEpochMilliseconds()
}
