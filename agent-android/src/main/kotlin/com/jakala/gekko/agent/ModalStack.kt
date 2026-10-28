package com.jakala.gekko.agent

import android.view.WindowManager.LayoutParams

/**
 * Dialogs, bottom sheets and menus are windows of their own above the activity; while one is open, the screen behind it
 * cannot be touched, so lookups must not match what only shines through its scrim.
 */
internal object ModalStack {
    class Window(val type: Int, val flags: Int)

    /** Index of the first window that is reachable; windows below it are blocked by a modal window above them. */
    fun firstReachable(windows: List<Window>): Int {
        return windows.indexOfLast { isModal(it) }.coerceAtLeast(0)
    }

    /**
     * An application window or sub-window (dialog, popup) that takes focus and swallows touches outside of it, or dims
     * what is behind it. Windows that cannot be focused (tooltips, suggestion lists, toasts) leave touches to the
     * window below, and the activity itself is the base of the stack.
     */
    fun isModal(window: Window): Boolean {
        val application = window.type in LayoutParams.TYPE_APPLICATION..LayoutParams.LAST_APPLICATION_WINDOW
        val sub = window.type in LayoutParams.FIRST_SUB_WINDOW..LayoutParams.LAST_SUB_WINDOW
        if (!application && !sub) {
            return false
        }
        if (window.flags and LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
            return false
        }
        if (window.flags and LayoutParams.FLAG_DIM_BEHIND != 0) {
            return true
        }
        return window.flags and (LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL) == 0
    }
}
