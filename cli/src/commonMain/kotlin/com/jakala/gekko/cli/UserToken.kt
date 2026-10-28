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
            env("GEKKO_HOME")
                ?: env("HOME")?.let { "$it/.gekko" }
                ?: error("HOME is not set; gekko keeps its token in ~/.gekko/token")
        val path = "$directory/token"
        if (fileExists(path)) {
            val existing = readTextFile(path).trim()
            check(existing.length >= BYTES * 2) { "$path is damaged; delete it and run gekko again" }
            return existing
        }
        makeDirs(directory)
        restrictToOwner(directory)
        val created = randomHex(BYTES)
        writeTextFile(path, created)
        restrictToOwner(path)
        return created
    }
}
