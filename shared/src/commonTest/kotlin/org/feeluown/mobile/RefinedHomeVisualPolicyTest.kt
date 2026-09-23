package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class RefinedHomeVisualPolicyTest {
    @Test
    fun personalizedEntriesUseTwoColumnsOnCompactDevices() {
        assertEquals(2, refinedForYouColumns(gridColumns = 3, wide = false))
        assertEquals(2, refinedForYouColumns(gridColumns = 4, wide = false))
    }

    @Test
    fun wideRecommendationCardsStayLargeEnoughToRead() {
        assertEquals(2, refinedForYouColumns(gridColumns = 1, wide = true))
        assertEquals(4, refinedForYouColumns(gridColumns = 4, wide = true))
        assertEquals(5, refinedForYouColumns(gridColumns = 8, wide = true))
    }
}
