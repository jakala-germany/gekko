package com.jakala.gekko.device

import com.jakala.gekko.protocol.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidKeysTest {
    @Test
    fun everyRemoteButtonHasItsAndroidKeyCode() {
        val codes = Keys.remote.associateWith { AndroidKeys.remoteKeyCode(it) }

        assertEquals(
            mapOf(
                Keys.UP to 19,
                Keys.DOWN to 20,
                Keys.LEFT to 21,
                Keys.RIGHT to 22,
                Keys.SELECT to 23,
                Keys.PLAY_PAUSE to 85,
                Keys.MENU to 82,
            ),
            codes,
        )
    }

    @Test
    fun theCaseOfAKeyNameDoesNotMatter() {
        assertEquals(AndroidKeys.DPAD_DOWN, AndroidKeys.remoteKeyCode("DOWN"))
        assertEquals(AndroidKeys.MEDIA_PLAY_PAUSE, AndroidKeys.remoteKeyCode("playpause"))
    }

    @Test
    fun otherKeysAreNoRemoteButtons() {
        assertNull(AndroidKeys.remoteKeyCode(Keys.ENTER))
        assertNull(AndroidKeys.remoteKeyCode(Keys.BACK))
        assertNull(AndroidKeys.remoteKeyCode("66"))
    }
}
