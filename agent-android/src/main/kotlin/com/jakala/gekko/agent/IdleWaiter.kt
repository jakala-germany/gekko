package com.jakala.gekko.agent

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import android.view.Choreographer
import com.jakala.gekko.protocol.UiNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Idle means: the main queue drained and the UI tree is identical across two consecutive frames. */
internal class IdleWaiter(private val snapshot: () -> List<UiNode>) {
    private val handler = Handler(Looper.getMainLooper())

    fun await(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var previous = snapshot()
        while (SystemClock.uptimeMillis() < deadline) {
            if (!awaitQueueIdleAndFrame(deadline)) {
                return false
            }
            val current = snapshot()
            if (current == previous) {
                return true
            }
            previous = current
        }
        return false
    }

    private fun awaitQueueIdleAndFrame(deadline: Long): Boolean {
        val latch = CountDownLatch(1)
        handler.post {
            Looper.myQueue()
                .addIdleHandler(
                    MessageQueue.IdleHandler {
                        Choreographer.getInstance().postFrameCallback { latch.countDown() }
                        false
                    }
                )
        }
        val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)
        return latch.await(remaining, TimeUnit.MILLISECONDS)
    }
}
