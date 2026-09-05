package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderSourceSettingsExpressiveTest {
    @Test
    fun dragCanJumpAcrossMultipleProviderRows() {
        assertEquals(
            3,
            providerDropTargetIndex(
                startIndex = 0,
                dragDistancePx = 318f,
                itemExtentPx = 100f,
                lastIndex = 3,
            ),
        )
    }

    @Test
    fun dragTargetClampsToListBounds() {
        assertEquals(0, providerDropTargetIndex(2, -900f, 100f, 3))
        assertEquals(3, providerDropTargetIndex(1, 900f, 100f, 3))
    }

    @Test
    fun dragTargetUsesNearestRow() {
        assertEquals(1, providerDropTargetIndex(1, 49f, 100f, 3))
        assertEquals(2, providerDropTargetIndex(1, 51f, 100f, 3))
    }
}
