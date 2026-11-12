package com.jakala.gekko.cli

/** The key names of a test, as Playwright names them; any other name goes through as it is (`a`, `Shift+A`). */
internal object WebKeys {
    private val names =
        mapOf(
            "enter" to "Enter",
            "backspace" to "Backspace",
            "delete" to "Delete",
            "tab" to "Tab",
            "escape" to "Escape",
            "space" to "Space",
            "home" to "Home",
            "end" to "End",
            "pageup" to "PageUp",
            "pagedown" to "PageDown",
            "up" to "ArrowUp",
            "down" to "ArrowDown",
            "left" to "ArrowLeft",
            "right" to "ArrowRight",
            "select" to "Enter",
            "playpause" to "MediaPlayPause",
        )

    fun of(key: String): String {
        return names[key.lowercase()] ?: key
    }
}
