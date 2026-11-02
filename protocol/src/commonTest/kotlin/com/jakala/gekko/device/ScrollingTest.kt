package com.jakala.gekko.device

import com.jakala.gekko.protocol.ScrollDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScrollingTest {
    private fun reveal(direction: String, rtl: Boolean): Pair<Int, Int> {
        val step = assertNotNull(Scrolling.reveal(direction, rtl), direction)
        return step.x to step.y
    }

    @Test
    fun downAndUpAreVerticalWhateverTheLayoutDirection() {
        assertEquals(0 to 1, reveal(ScrollDirection.DOWN, rtl = false))
        assertEquals(0 to -1, reveal(ScrollDirection.UP, rtl = false))
        assertEquals(0 to 1, reveal(ScrollDirection.DOWN, rtl = true))
        assertEquals(0 to -1, reveal(ScrollDirection.UP, rtl = true))
    }

    @Test
    fun leftAndRightArePhysical() {
        assertEquals(1 to 0, reveal(ScrollDirection.RIGHT, rtl = false))
        assertEquals(-1 to 0, reveal(ScrollDirection.LEFT, rtl = false))
        assertEquals(1 to 0, reveal(ScrollDirection.RIGHT, rtl = true))
        assertEquals(-1 to 0, reveal(ScrollDirection.LEFT, rtl = true))
    }

    @Test
    fun startAndEndFollowTheLayoutDirection() {
        assertEquals(1 to 0, reveal(ScrollDirection.END, rtl = false))
        assertEquals(-1 to 0, reveal(ScrollDirection.START, rtl = false))
        assertEquals(-1 to 0, reveal(ScrollDirection.END, rtl = true))
        assertEquals(1 to 0, reveal(ScrollDirection.START, rtl = true))
    }

    @Test
    fun everyDirectionOfTheProtocolIsKnown() {
        ScrollDirection.all.forEach { assertNotNull(Scrolling.reveal(it, rtl = false), it) }
    }

    @Test
    fun anUnknownDirectionIsNotGuessed() {
        assertNull(Scrolling.reveal("diagonal", rtl = false))
        assertNull(Scrolling.reveal("", rtl = true))
    }
}
