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

    @Test
    fun playlistFiltersKeepCategoryWhileChangingOrder() {
        val filters = playlistSquareFilters(cat = "摇滚", order = "new")
        val categoryFilter = filters.single { it.key == "cat" }
        val orderFilter = filters.single { it.key == "order" }
        val hotTarget = orderFilter.options.single { it.label == "热门" }
        val request = parseNeteaseFeatureRequest(hotTarget.featureId)

        assertEquals("摇滚", categoryFilter.options.single { it.selected }.label)
        assertEquals("最新", orderFilter.options.single { it.selected }.label)
        assertEquals("摇滚", request.params["cat"])
        assertEquals("hot", request.params["order"])
    }

    @Test
    fun mvFiltersKeepOtherDimensionsWhenAreaChanges() {
        val filters = mvSquareFilters(area = "欧美", type = "现场版", order = "最新")
        val areaFilter = filters.single { it.key == "area" }
        val mainlandTarget = areaFilter.options.single { it.label == "内地" }
        val request = parseNeteaseFeatureRequest(mainlandTarget.featureId)

        assertEquals("欧美", areaFilter.options.single { it.selected }.label)
        assertEquals("内地", request.params["area"])
        assertEquals("现场版", request.params["type"])
        assertEquals("最新", request.params["order"])
    }

    @Test
    fun styleFiltersSwitchContentWithoutLosingStyle() {
        val filters = styleFilters(
            styles = listOf(
                NeteaseStyleTag(id = "101", name = "流行"),
                NeteaseStyleTag(id = "202", name = "摇滚"),
            ),
            tagId = "202",
            kind = "albums",
        )
        val styleFilter = filters.single { it.key == "tagId" }
        val contentFilter = filters.single { it.key == "kind" }
        val artistTarget = contentFilter.options.single { it.label == "歌手" }
        val request = parseNeteaseFeatureRequest(artistTarget.featureId)

        assertEquals("摇滚", styleFilter.options.single { it.selected }.label)
        assertEquals("专辑", contentFilter.options.single { it.selected }.label)
        assertEquals("202", request.params["tagId"])
        assertEquals("artists", request.params["kind"])
    }

    @Test
    fun malformedFeatureParametersAreIgnored() {
        val request = parseNeteaseFeatureRequest(
            "$NETEASE_PLAYLIST_SQUARE|cat=华语|invalid|=missing-key|order=new",
        )

        assertEquals(mapOf("cat" to "华语", "order" to "new"), request.params)
    }
}
