package com.jakala.gekko.device

import kotlin.math.sqrt

/** Where a finger is [atMs] after it touched down. */
class TouchPoint(val x: Float, val y: Float, val atMs: Long)

/** The finger movements that the servers make for a swipe or a drag, so that every platform moves alike. */
object Gestures {
    /** How often a moving finger reports its position: about one frame of a 60 Hz display. */
    const val MOVE_INTERVAL_MS = 16L

    /** How long the finger of a drag takes to get to the end. */
    const val DRAG_MOVE_MS = 300L

    /** How long the finger of a drag rests before it lifts, longer than the 100 ms a velocity tracker looks back. */
    const val DRAG_REST_MS = 300L

    /**
     * The positions of a finger that goes in a straight line from the start to the end in [durationMs]. The first one
     * is a frame after the touch down and the last one is the end, exactly, at [durationMs].
     */
    fun path(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): List<TouchPoint> {
        return line(fromX.toFloat(), fromY.toFloat(), toX.toFloat(), toY.toFloat(), durationMs)
    }

    /**
     * The movement of a finger that drags the content under the start to the end. A scroll container follows the finger
     * only after it moved the touch slop ([slop] in pixels), so the finger goes that far beyond the end, on the line of
     * the drag. The caller lets it rest for [DRAG_REST_MS] at the last position before it lifts: it then has no speed
     * and the content stops where the finger stops.
     */
    fun dragPath(fromX: Int, fromY: Int, toX: Int, toY: Int, slop: Float): List<TouchPoint> {
        val dx = (toX - fromX).toFloat()
        val dy = (toY - fromY).toFloat()
        val length = sqrt(dx * dx + dy * dy)
        val beyond = if (length == 0f) 0f else slop / length
        return line(fromX.toFloat(), fromY.toFloat(), toX + dx * beyond, toY + dy * beyond, DRAG_MOVE_MS)
    }

    private fun line(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): List<TouchPoint> {
        val total = durationMs.coerceAtLeast(0)
        val steps = (total / MOVE_INTERVAL_MS).toInt().coerceAtLeast(1)
        return (1..steps).map { step ->
            val fraction = step / steps.toFloat()
            TouchPoint(fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction, total * step / steps)
        }
    }
}
