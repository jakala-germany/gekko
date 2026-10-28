package com.jakala.gekko.agent

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeReaderTest {
    @Test
    fun onlyAnIndeterminateIndicatorKeepsTheScreenBusy() {
        assertTrue(ComposeReader.isBusy(ProgressBarRangeInfo.Indeterminate))
    }

    @Test
    fun aDeterminateBarSliderOrRatingDoesNot() {
        assertFalse(ComposeReader.isBusy(ProgressBarRangeInfo(0.3f, 0f..1f)))
        assertFalse(ComposeReader.isBusy(ProgressBarRangeInfo(0f, 0f..1f)))
        assertFalse(ComposeReader.isBusy(ProgressBarRangeInfo(25f, 0f..100f, steps = 3)))
        assertFalse(ComposeReader.isBusy(null))
    }
}
