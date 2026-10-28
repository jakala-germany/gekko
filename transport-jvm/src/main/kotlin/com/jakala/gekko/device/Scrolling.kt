package com.jakala.gekko.device

import com.jakala.gekko.protocol.ScrollDirection

/** Which way the content of a container has to move for a scroll direction. */
class Reveal(
    /** 1: content on the right (physical) comes into view, -1: content on the left. */
    val x: Int,
    /** 1: content below comes into view, -1: content above. */
    val y: Int,
)

object Scrolling {
    /**
     * `down` and `up` are vertical, `left` and `right` physical, `start` and `end` follow the layout direction of the
     * container: `end` is on the right in a left-to-right layout and on the left when [rtl]. Null for an unknown name.
     */
    fun reveal(direction: String, rtl: Boolean): Reveal? {
        val end = if (rtl) -1 else 1
        return when (direction) {
            ScrollDirection.DOWN -> Reveal(0, 1)
            ScrollDirection.UP -> Reveal(0, -1)
            ScrollDirection.RIGHT -> Reveal(1, 0)
            ScrollDirection.LEFT -> Reveal(-1, 0)
            ScrollDirection.END -> Reveal(end, 0)
            ScrollDirection.START -> Reveal(-end, 0)
            else -> null
        }
    }
}
