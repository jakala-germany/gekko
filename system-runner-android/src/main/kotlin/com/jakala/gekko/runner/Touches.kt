package com.jakala.gekko.runner

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.jakala.gekko.device.Gestures
import com.jakala.gekko.device.TouchPoint

/**
 * One finger on the screen, injected through [UiAutomation] with event times of its own.
 *
 * `UiDevice.swipe` is no option: in UiAutomator 2.4.0 it waits for the next frame callback of the runner's own process
 * after the touch down and after every step, and the runner draws nothing. A step is meant to take 5 ms and takes about
 * 100 ms, so a long press of 700 ms lasts 18 s. Here time is only what [Thread.sleep] makes of it.
 */
internal class Touches(private val automation: () -> UiAutomation) {
    /** Presses at the point, holds still for [holdMs] and lifts. False when the system refused a touch. */
    fun hold(x: Int, y: Int, holdMs: Long): Boolean {
        return gesture(x.toFloat(), y.toFloat(), emptyList(), holdMs)
    }

    /**
     * A finger that goes from the start to the end in [durationMs] and lifts: a short one flings, a long one barely.
     */
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        return gesture(fromX.toFloat(), fromY.toFloat(), Gestures.path(fromX, fromY, toX, toY, durationMs), 0)
    }

    /** Moves the content under the start to the end and lets it rest there: the finger lifts without speed. */
    fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, slop: Float): Boolean {
        val path = Gestures.dragPath(fromX, fromY, toX, toY, slop)
        return gesture(fromX.toFloat(), fromY.toFloat(), path, Gestures.DRAG_REST_MS)
    }

    /**
     * Down, the [moves] at their times, [restMs] of rest at the last position, up. The finger always lifts, also when
     * the thread is interrupted on the way.
     */
    private fun gesture(startX: Float, startY: Float, moves: List<TouchPoint>, restMs: Long): Boolean {
        val downTime = SystemClock.uptimeMillis()
        if (!inject(MotionEvent.ACTION_DOWN, downTime, downTime, startX, startY, sync = true)) {
            return false
        }
        var injected = true
        var x = startX
        var y = startY
        try {
            for (move in moves) {
                sleepUntil(downTime + move.atMs)
                injected =
                    inject(MotionEvent.ACTION_MOVE, downTime, SystemClock.uptimeMillis(), move.x, move.y) && injected
                x = move.x
                y = move.y
            }
            if (restMs > 0) {
                Thread.sleep(restMs)
            }
        } finally {
            injected =
                inject(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), x, y, sync = true) && injected
        }
        return injected
    }

    private fun sleepUntil(uptimeMs: Long) {
        val wait = uptimeMs - SystemClock.uptimeMillis()
        if (wait > 0) {
            Thread.sleep(wait)
        }
    }

    /** Down and up wait until the system handled them; moves do not, so that a slow answer cannot stretch a gesture. */
    private fun inject(
        action: Int,
        downTime: Long,
        eventTime: Long,
        x: Float,
        y: Float,
        sync: Boolean = false,
    ): Boolean {
        // A pointer starts with the id -1, which the input dispatcher refuses
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        val event =
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                arrayOf(properties),
                arrayOf(coordinates),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        try {
            return automation().injectInputEvent(event, sync)
        } finally {
            event.recycle()
        }
    }
}
