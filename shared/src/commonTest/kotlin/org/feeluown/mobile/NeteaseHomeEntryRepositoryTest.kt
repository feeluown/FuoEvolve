package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NeteaseHomeEntryRepositoryTest {
    @Test
    fun mapsHomepageEntriesToExploreFeatures() {
        val covers = mapNeteaseHomeEntryCovers(
            listOf(
                NeteaseHomeEntry("每日推荐", "daily.png"),
                NeteaseHomeEntry("新歌新碟", "new.png"),
                NeteaseHomeEntry("排行榜", "rank.png"),
                NeteaseHomeEntry("曲风", "style.png"),
                NeteaseHomeEntry("MV", "mv.png"),
            ),
        )

        assertEquals("daily.png", covers["netease_daily_songs"])
        assertEquals("new.png", covers["netease_new_songs"])
        assertEquals("style.png", covers["netease_styles"])
        assertEquals("mv.png", covers["netease_mv_square"])
        assertEquals("mv.png", covers["netease_top_mvs"])
    }

    @Test
    fun normalizesFilteredFeatureIds() {
        assertEquals(
            "netease_mv_square",
            neteaseFeatureBaseId("netease_mv_square|area=全部|type=全部^filters^encoded"),
        )
        assertNull(neteaseHomeEntryCoverUrl("netease:12345"))
    }
}
