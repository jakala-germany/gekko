package com.jakala.gekko.agent

import android.view.WindowManager.LayoutParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModalStackTest {
    private val activity = ModalStack.Window(LayoutParams.TYPE_BASE_APPLICATION, 0)
    private val dialog = ModalStack.Window(LayoutParams.TYPE_APPLICATION, LayoutParams.FLAG_DIM_BEHIND)
    private val bottomSheet = ModalStack.Window(LayoutParams.TYPE_APPLICATION, 0)
    private val menu = ModalStack.Window(LayoutParams.TYPE_APPLICATION_PANEL, 0)
    private val suggestions = ModalStack.Window(LayoutParams.TYPE_APPLICATION_PANEL, LayoutParams.FLAG_NOT_FOCUSABLE)
    private val tooltip =
        ModalStack.Window(
            LayoutParams.TYPE_APPLICATION_PANEL,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE,
        )
    private val toast =
        ModalStack.Window(LayoutParams.TYPE_TOAST, LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE)

    @Test
    fun theActivityAloneIsReachable() {
        assertEquals(0, ModalStack.firstReachable(listOf(activity)))
        assertEquals(0, ModalStack.firstReachable(emptyList()))
        assertFalse(ModalStack.isModal(activity))
    }

    @Test
    fun aDialogBlocksTheActivityBehindIt() {
        assertEquals(1, ModalStack.firstReachable(listOf(activity, dialog)))
        assertTrue(ModalStack.isModal(dialog))
    }

    @Test
    fun aBottomSheetAndAFocusableMenuAreModal() {
        assertEquals(1, ModalStack.firstReachable(listOf(activity, bottomSheet)))
        assertEquals(1, ModalStack.firstReachable(listOf(activity, menu)))
    }

    @Test
    fun onlyTheTopmostModalWindowAndWhatIsAboveItCount() {
        assertEquals(2, ModalStack.firstReachable(listOf(activity, dialog, menu)))
        assertEquals(1, ModalStack.firstReachable(listOf(activity, dialog, suggestions)))
        assertEquals(1, ModalStack.firstReachable(listOf(activity, dialog, toast)))
    }

    @Test
    fun windowsThatLeaveTouchesToTheOnesBelowDoNotHideThem() {
        assertEquals(0, ModalStack.firstReachable(listOf(activity, suggestions)))
        assertEquals(0, ModalStack.firstReachable(listOf(activity, tooltip, toast)))
        assertFalse(ModalStack.isModal(suggestions))
        assertFalse(ModalStack.isModal(tooltip))
        assertFalse(ModalStack.isModal(toast))
    }

    @Test
    fun aWindowWithoutLayoutParamsIsNotModal() {
        assertFalse(ModalStack.isModal(ModalStack.Window(0, 0)))
    }

    @Test
    fun aPopupThatLetsTouchesPassIsNotModal() {
        val outside = ModalStack.Window(LayoutParams.TYPE_APPLICATION_PANEL, LayoutParams.FLAG_NOT_TOUCH_MODAL)

        assertFalse(ModalStack.isModal(outside))
    }
}
