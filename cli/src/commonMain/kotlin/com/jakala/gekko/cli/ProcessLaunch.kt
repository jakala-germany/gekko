package com.jakala.gekko.cli

/**
 * How a program is started. On macOS and Linux that is the program and its arguments as they are. Windows has no
 * argument list: a program gets one command line that it splits itself, so gekko builds that line here, quotes every
 * argument so that the program reads back exactly what was passed, and finds the program on `PATH` with `PATHEXT` (the
 * system call does not). `.cmd` and `.bat` files (what npm installs for `npm`, `claude` or `opencode`) only run through
 * `cmd.exe`, whose quoting rules are different and weaker: arguments that cannot be passed through it safely are
 * refused instead of being guessed at.
 */
internal class Launch(
    /** The executable that is started: the program itself, or cmd.exe for a `.cmd` or `.bat` file. */
    val application: String,
    /** The program as it is found (on Windows with its full path) followed by the arguments. */
    val argv: List<String>,
    /** The one command line of Windows; null elsewhere. */
    val commandLine: String?,
)

/** The extensions Windows can start directly (`.exe`, `.com`) or through cmd.exe (`.cmd`, `.bat`). */
private val LAUNCHABLE = setOf(".exe", ".com", ".cmd", ".bat")
private val BATCH = setOf(".cmd", ".bat")
private const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"
private const val DEFAULT_COMSPEC = "C:\\Windows\\System32\\cmd.exe"

internal fun planLaunch(
    command: List<String>,
    windows: Boolean,
    environment: (String) -> String?,
    exists: (String) -> Boolean,
): Launch? {
    if (!windows) {
        return Launch(command.first(), command, null)
    }
    val program =
        resolveWindowsProgram(command.first(), environment("PATH"), environment("PATHEXT"), exists) ?: return null
    val arguments = command.drop(1)
    if (extensionOf(program) in BATCH) {
        val shell = environment("ComSpec")?.takeIf { it.isNotBlank() } ?: DEFAULT_COMSPEC
        return Launch(shell, listOf(program) + arguments, batchCommandLine(shell, program, arguments))
    }
    return Launch(program, listOf(program) + arguments, windowsCommandLine(listOf(program) + arguments))
}

/** The full path of the file Windows would start for [name], or null. */
internal fun resolveWindowsProgram(
    name: String,
    path: String?,
    pathExt: String?,
    exists: (String) -> Boolean,
): String? {
    val extensions = (pathExt ?: DEFAULT_PATHEXT).split(';').map { it.trim().lowercase() }.filter { it.startsWith(".") }
    val located = name.contains('\\') || name.contains('/') || name.contains(':')
    val directories =
        if (located) listOf("") else (path ?: "").split(';').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    val ownExtension = extensionOf(name)
    val candidates = buildList {
        if (ownExtension in LAUNCHABLE && (ownExtension in extensions || extensions.isEmpty())) {
            add(name)
        }
        extensions.filter { it in LAUNCHABLE }.forEach { add(name + it) }
    }
    for (directory in directories) {
        for (candidate in candidates) {
            val full = if (directory.isEmpty()) candidate else directory.trimEnd('\\', '/') + "\\" + candidate
            if (exists(full)) {
                return full
            }
        }
    }
    return null
}

private fun extensionOf(name: String): String {
    val file = name.substringAfterLast('\\').substringAfterLast('/')
    val dot = file.lastIndexOf('.')
    return if (dot <= 0) "" else file.substring(dot).lowercase()
}

/** The command line that `CommandLineToArgvW` and the C runtime split back into exactly [arguments]. */
internal fun windowsCommandLine(arguments: List<String>): String {
    return arguments.joinToString(" ") { windowsQuote(it) }
}

internal fun windowsQuote(argument: String): String {
    require('\u0000' !in argument) { "an argument of a program cannot contain a NUL character" }
    val plain =
        argument.isNotEmpty() && argument.none { it == ' ' || it == '\t' || it == '\n' || it == '\u000B' || it == '"' }
    if (plain) {
        return argument
    }
    val quoted = StringBuilder("\"")
    var backslashes = 0
    for (char in argument) {
        when (char) {
            '\\' -> backslashes++
            '"' -> {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"')
                backslashes = 0
            }
            else -> {
                quoted.append("\\".repeat(backslashes)).append(char)
                backslashes = 0
            }
        }
    }
    quoted.append("\\".repeat(backslashes * 2)).append('"')
    return quoted.toString()
}

/**
 * `cmd.exe /d /s /c ""script" "argument" ..."`: delayed expansion is off, auto-run is skipped, the outer quotes are the
 * ones `/s` removes. Every argument is quoted and `"` is doubled; `%` (and a carriage return) would still be expanded
 * inside quotes, so `%` is written as `%%cd:~,%`: cmd expands the empty `%cd:~,%` and keeps one literal `%` (the way
 * Rust's standard library does it). A line feed or NUL cannot be passed at all.
 */
internal fun batchCommandLine(shell: String, script: String, arguments: List<String>): String {
    require('"' !in script && !script.endsWith("\\")) { "cannot run $script through cmd.exe" }
    val line = StringBuilder("\"").append(shell).append("\" /e:ON /v:OFF /d /s /c \"\"").append(script).append('"')
    for (argument in arguments) {
        line.append(' ').append(batchArgument(argument))
    }
    return line.append('"').toString()
}

internal fun batchArgument(argument: String): String {
    require(argument.none { it == '\n' || it == '\u0000' }) {
        "an argument with a line break or NUL cannot be passed to a .cmd or .bat file"
    }
    val result = StringBuilder("\"")
    var backslashes = 0
    for (char in argument) {
        if (char == '\\') {
            backslashes++
            result.append(char)
            continue
        }
        if (char == '"') {
            result.append("\\".repeat(backslashes)).append('"')
        } else if (char == '%' || char == '\r') {
            result.append("%%cd:~,")
        }
        backslashes = 0
        result.append(char)
    }
    result.append("\\".repeat(backslashes)).append('"')
    return result.toString()
}

/** The file that [command] starts, found like the system finds it on `PATH`, or null. */
fun findOnPath(command: String): String? {
    return findProgram(command, isWindowsHost, env("PATH"), env("PATHEXT"), ::isExecutableFile)
}

/** How [command] is started on this host; null when the program is not there. */
internal fun launchOf(command: List<String>): Launch? {
    return try {
        planLaunch(command, isWindowsHost, ::env, ::isExecutableFile)
    } catch (error: IllegalArgumentException) {
        throw InfrastructureException(error.message ?: "cannot start ${command.first()}")
    }
}

internal fun findProgram(
    command: String,
    windows: Boolean,
    path: String?,
    pathExt: String?,
    exists: (String) -> Boolean,
): String? {
    if (windows) {
        return resolveWindowsProgram(command, path, pathExt, exists)
    }
    if (command.contains('/')) {
        return command.takeIf(exists)
    }
    return (path ?: "").split(':').filter { it.isNotEmpty() }.map { "$it/$command" }.firstOrNull(exists)
}
