package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson

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
    fun mapsHomepageBlockArtworkToExploreFeatures() {
        val root = providerJson.parseToJsonElement(
            """
            {
              "data": {
                "blocks": [
                  {
                    "blockCode": "HOMEPAGE_BLOCK_OLD_DRAGON_BALL",
                    "creatives": [
                      {
                        "resources": [
                          {
                            "action": "orpheus://songrcmd",
                            "uiElement": {
                              "mainTitle": {"title": "每日推荐"},
                              "image": {"imageUrl": "daily.png"}
                            }
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "blockCode": "HOMEPAGE_BLOCK_NEW_ALBUM_NEW_SONG",
                    "creatives": [
                      {
                        "resources": [
                          {"uiElement": {"image": {"imageUrl": "new.jpg"}}}
                        ]
                      }
                    ]
                  },
                  {
                    "blockCode": "HOMEPAGE_BLOCK_STYLE_RCMD",
                    "creatives": [
                      {
                        "resources": [
                          {"uiElement": {"image": {"imageUrl": "style.jpg"}}}
                        ]
                      }
                    ]
                  },
                  {
                    "blockCode": "HOMEPAGE_MUSIC_MLOG",
                    "creatives": [
                      {
                        "resources": [
                          {"uiElement": {"image": {"imageUrl": "video.jpg"}}}
                        ]
                      }
                    ]
                  },
                  {
                    "blockCode": "HOMEPAGE_BLOCK_TOPLIST",
                    "creatives": [
                      {
                        "resources": [
                          {"uiElement": {"image": {"imageUrl": "rank.jpg"}}}
                        ]
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        ).asObject()
        val blocks = root.obj("data")!!.array("blocks").map { it.asObject() }

        val covers = mapNeteaseHomepageCovers(blocks)

        assertEquals("daily.png", covers["netease_daily_songs"])
        assertEquals("new.jpg", covers["netease_new_songs"])
        assertEquals("style.jpg", covers["netease_styles"])
        assertEquals("video.jpg", covers["netease_mv_square"])
        assertEquals("video.jpg", covers["netease_top_mvs"])
        assertEquals("rank.jpg", covers["netease_toplists"])
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
