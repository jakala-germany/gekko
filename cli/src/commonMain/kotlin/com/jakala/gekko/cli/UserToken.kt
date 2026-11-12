package com.jakala.gekko.cli

/**
 * The secret that authenticates the CLI to the iOS agent and system runner. One per user, created on first use in
 * `$GEKKO_HOME/token` (default `~/.gekko/token`) and readable by that user only, so every CLI call finds the agent an
 * earlier call launched while other users and web pages cannot talk to it.
 */
object UserToken {
    private const val BYTES = 32

    val value: String by lazy { load() }

    private fun load(): String {
        val directory =
            gekkoHome() ?: error("HOME (USERPROFILE on Windows) is not set; gekko keeps its token in ~/.gekko/token")
        val path = "$directory/token"
        if (fileExists(path)) {
            val existing = readTextFile(path).trim()
            check(existing.length >= BYTES * 2) { "$path is damaged; delete it and run gekko again" }
            return existing
        }
        val existed = fileExists(directory)
        makeDirs(directory)
        // On Windows a restriction is handed down to everything inside (the installed runner, the Playwright files),
        // so it is only set on a folder that gekko creates itself; the token file below is restricted in any case
        if (!existed || !isWindowsHost) {
            restrictToOwner(directory)
        }
        val created = randomHex(BYTES)
        writeTextFile(path, created)
        restrictToOwner(path)
        return created
    }
}
