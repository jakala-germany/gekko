package com.jakala.gekko.agent

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.jakala.gekko.protocol.Keys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The key presses the in-app agent can deliver inside its own windows; everything else is the system runner's. */
internal object KeyPresses {
    /** The key code of a key that goes to a field, or null when [key] is not one of them. */
    fun fieldKeyCode(key: String): Int? {
        return when (key.lowercase()) {
            Keys.ENTER -> KeyEvent.KEYCODE_ENTER
            Keys.BACKSPACE -> KeyEvent.KEYCODE_DEL
            Keys.TAB -> KeyEvent.KEYCODE_TAB
            else -> null
        }
    }

    fun isBack(key: String): Boolean {
        return key.lowercase() == Keys.BACK
    }

    /** Sends a press (down and up) of [code] to the window [root], the way the system hands a key to the window. */
    fun press(root: View, code: Int): Boolean {
        return MainThread.run {
            val down = SystemClock.uptimeMillis()
            val handledDown = root.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
            val handledUp =
                root.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            handledDown || handledUp
        }
    }

    /**
     * Presses [code] (down and up) through the input pipeline of the app's own window, the way a remote does: the
     * window moves the focus on a D-pad key and leaves touch mode, which a key handed to the root view does not. Runs
     * on the calling thread, which must not be the main thread. False when the system refused the injection.
     */
    fun inject(code: Int): Boolean {
        return try {
            Instrumentation().sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
            Instrumentation().sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
            true
        } catch (refused: RuntimeException) {
            false
        }
    }

    /**
     * What back does on a phone: with the keyboard up the first press closes it and goes no further, otherwise the key
     * goes to the window that has the focus, where the activity, a dialog or a popup takes it. True when something did.
     */
    fun back(root: View): Boolean {
        if (hideKeyboard(root)) {
            return true
        }
        return press(root, KeyEvent.KEYCODE_BACK)
    }

    /** True when the keyboard was showing for [root] and is gone now. */
    private fun hideKeyboard(root: View): Boolean {
        val manager = root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        val answered = CountDownLatch(1)
        val hidden = AtomicBoolean(false)
        val receiver =
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    hidden.set(resultCode == InputMethodManager.RESULT_HIDDEN)
                    answered.countDown()
                }
            }
        val asked = MainThread.run { manager.hideSoftInputFromWindow(root.windowToken, 0, receiver) }
        if (!asked) {
            return false
        }
        answered.await(KEYBOARD_ANSWER_MS, TimeUnit.MILLISECONDS)
        return hidden.get()
    }

    private const val KEYBOARD_ANSWER_MS = 500L
}
