package org.feeluown.mobile

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeNavigationUiPolicyTest {
    @Test
    fun navigationCompactsAfterSustainedUpwardScrollAndExpandsOnReverseScroll() {
        val accumulator = HomeNavigationScrollAccumulator(thresholdPx = 28f)

        assertNull(accumulator.onScroll(-12f))
        assertNull(accumulator.onScroll(-12f))
        assertEquals(true, accumulator.onScroll(-4f))

        assertNull(accumulator.onScroll(10f))
        assertEquals(false, accumulator.onScroll(18f))
    }

    @Test
    fun navigationDirectionChangeResetsPartialScrollDistance() {
        val accumulator = HomeNavigationScrollAccumulator(thresholdPx = 28f)

        assertNull(accumulator.onScroll(-20f))
        assertNull(accumulator.onScroll(12f))
        assertNull(accumulator.onScroll(15f))
        assertEquals(false, accumulator.onScroll(13f))
    }

    @Test
    fun compactMiniPlayerKeepsPreviousControlForRoomierLayoutsOnly() {
        assertFalse(shouldShowMiniPlayerPreviousControl(360.dp, isWideLayout = false))
        assertFalse(shouldShowMiniPlayerPreviousControl(419.dp, isWideLayout = false))
        assertTrue(shouldShowMiniPlayerPreviousControl(420.dp, isWideLayout = false))
        assertTrue(shouldShowMiniPlayerPreviousControl(360.dp, isWideLayout = true))
    }
}
