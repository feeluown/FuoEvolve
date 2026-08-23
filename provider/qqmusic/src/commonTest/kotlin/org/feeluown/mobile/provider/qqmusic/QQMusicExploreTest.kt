package org.feeluown.mobile.provider.qqmusic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QQMusicExploreTest {
    @Test
    fun mvSquareSortFiltersPreserveSelection() {
        val filters = qqMusicMvSquareFilters(
            area = "16",
            version = "9",
            order = "0",
        )

        val sortFilter = filters.first { it.key == "order" }
        assertEquals(listOf("热门", "最新"), sortFilter.options.map { it.label })
        assertTrue(sortFilter.options.first { it.label == "最新" }.selected)

        val areaRequest = parseQQMusicFeatureRequest(
            filters.first { it.key == "area" }
                .options.first { it.label == "欧美" }
                .featureId,
        )
        assertEquals(QQMUSIC_MV_SQUARE, areaRequest.baseId)
        assertEquals("18", areaRequest.params["area"])
        assertEquals("9", areaRequest.params["version"])
        assertEquals("0", areaRequest.params["order"])

        val popularRequest = parseQQMusicFeatureRequest(
            sortFilter.options.first { it.label == "热门" }.featureId,
        )
        assertEquals("16", popularRequest.params["area"])
        assertEquals("9", popularRequest.params["version"])
        assertEquals("1", popularRequest.params["order"])
    }

    @Test
    fun mvSquareDefaultsToPopularSort() {
        val filters = qqMusicMvSquareFilters(
            area = QQ_DEFAULT_MV_AREA,
            version = QQ_DEFAULT_MV_VERSION,
        )

        val sortFilter = filters.first { it.key == "order" }
        assertTrue(sortFilter.options.first { it.label == "热门" }.selected)
    }
}
