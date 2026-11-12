package com.jakala.gekko.device

import com.jakala.gekko.protocol.Keys

/*
 * The Android key codes of the TV remote buttons, shared by the Android agent and the system runner. They are plain
 * numbers so this runs in JVM tests; they are the values of android.view.KeyEvent.
 */
object AndroidKeys {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val MENU = 82
    const val MEDIA_PLAY_PAUSE = 85

    /**
     * The key code of the remote button [key] (`down`, `select`, ...; case does not matter), or null for any other key.
     */
    fun remoteKeyCode(key: String): Int? {
        return when (key.lowercase()) {
            Keys.UP -> DPAD_UP
            Keys.DOWN -> DPAD_DOWN
            Keys.LEFT -> DPAD_LEFT
            Keys.RIGHT -> DPAD_RIGHT
            Keys.SELECT -> DPAD_CENTER
            Keys.PLAY_PAUSE.lowercase() -> MEDIA_PLAY_PAUSE
            Keys.MENU -> MENU
            else -> null
        }
    }
}
