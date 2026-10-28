package com.jakala.gekko.agent

import android.view.View
import android.view.WindowManager

internal object WindowRoots {
    /** Every window of the app, the one that was added last on top. */
    fun all(): List<View> {
        return runCatching {
                val globalClass = Class.forName("android.view.WindowManagerGlobal")
                val global = globalClass.getMethod("getInstance").invoke(null)
                val field = globalClass.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") (field.get(global) as List<View>).toList()
            }
            .getOrDefault(emptyList())
    }

    /** The windows a person can reach: from the topmost modal one (dialog, bottom sheet, menu) on, see [ModalStack]. */
    fun reachable(windows: List<View>): List<View> {
        val traits = windows.map {
            val params = it.layoutParams as? WindowManager.LayoutParams
            ModalStack.Window(params?.type ?: 0, params?.flags ?: 0)
        }
        return windows.drop(ModalStack.firstReachable(traits))
    }
}
