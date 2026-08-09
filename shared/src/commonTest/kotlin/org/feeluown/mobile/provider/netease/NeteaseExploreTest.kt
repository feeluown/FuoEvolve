package org.feeluown.mobile.provider.netease

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeteaseExploreTest {
    @Test
    fun featureRequestParsesDerivedFilterParameters() {
        val id = neteaseFeatureId(
            NETEASE_PLAYLIST_SQUARE,
            "cat" to "R&B/Soul",
            "order" to "new",
        )

        val request = parseNeteaseFeatureRequest(id)

        assertEquals(NETEASE_PLAYLIST_SQUARE, request.baseId)
        assertEquals("R&B/Soul", request.params["cat"])
        assertEquals("new", request.params["order"])
    }

    @Test
    fun presentationMetadataIsIgnoredWhenParsingRequest() {
        val request = parseNeteaseFeatureRequest(
            "netease_artist_square|area=96|type=3|initial=77^filters^presentation-only",
        )

        assertEquals(NETEASE_ARTIST_SQUARE, request.baseId)
        assertEquals("96", request.params["area"])
        assertEquals("3", request.params["type"])
        assertEquals("77", request.params["initial"])
    }

    @Test
    fun artistFilterTargetsPreserveOtherDimensions() {
        val filters = artistSquareFilters(area = "96", type = "3", initial = "77")
        val initialFilter = filters.first { it.key == "initial" }
        val selectedInitial = initialFilter.options.single { it.selected }
        val japanTarget = filters.first { it.key == "area" }.options.first { it.label == "日本" }
        val request = parseNeteaseFeatureRequest(japanTarget.featureId)

        assertEquals("M", selectedInitial.label)
        assertEquals("8", request.params["area"])
        assertEquals("3", request.params["type"])
        assertEquals("77", request.params["initial"])
        assertTrue(initialFilter.options.any { it.label == "#" })
    }
}
