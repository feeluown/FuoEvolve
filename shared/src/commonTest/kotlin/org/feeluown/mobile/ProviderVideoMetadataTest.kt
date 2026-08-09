package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProviderVideoMetadataTest {
    @Test
    fun parsesBilibiliMetadataAndRotatedDimensions() {
        val metadata = parseBilibiliVideoMetadata(
            """
            {
              "code": 0,
              "data": {
                "desc": "测试简介",
                "pubdate": 1704067200,
                "dimension": {"width": 1080, "height": 1920, "rotate": 90},
                "stat": {
                  "view": 123456,
                  "like": 2345,
                  "coin": 345,
                  "favorite": 456,
                  "reply": 567,
                  "danmaku": 678,
                  "share": 789
                }
              }
            }
            """.trimIndent(),
        )

        assertNotNull(metadata)
        assertEquals("测试简介", metadata.description)
        assertEquals("2024-01-01", metadata.publishedAt)
        assertEquals(1920, metadata.width)
        assertEquals(1080, metadata.height)
        assertEquals(7, metadata.stats.size)
        assertEquals(123456, metadata.stats.first().value)
    }

    @Test
    fun parsesNeteaseMetadata() {
        val metadata = parseNeteaseVideoMetadata(
            """
            {
              "data": {
                "desc": "MV 描述",
                "publishTime": "2026-08-01",
                "playCount": 10000,
                "subCount": 200,
                "commentCount": 30,
                "shareCount": 40
              }
            }
            """.trimIndent(),
        )

        assertNotNull(metadata)
        assertEquals("MV 描述", metadata.description)
        assertEquals("2026-08-01", metadata.publishedAt)
        assertEquals(listOf("播放", "收藏", "评论", "分享"), metadata.stats.map { it.label })
    }
}
