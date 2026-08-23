package org.feeluown.mobile.provider.qqmusic

import kotlin.test.Test
import kotlin.test.assertEquals

class QQMusicQrcCodecTest {
    @Test
    fun decryptsKnownQrcPayloadAndNormalizesWordTiming() {
        val encrypted = "d6a2be95d64473721b73c97024a81f8507967be980ad83772d7a8ec317a07c96417882618bc1a7d2ad434fb8500fa4b1"

        assertEquals(
            "[1000,1000](1000,500,0)Hello(1500,500,0) world",
            decodeQqLyricPayload(encrypted),
        )
    }

    @Test
    fun convertsQrcSuffixTimingsWithoutDroppingFirstWord() {
        val xml = """<?xml version="1.0"?><QrcInfos><LyricInfo><Lyric_1 LyricContent="[1000,1000]Hello(1000,500) world(1500,500)"/></LyricInfo></QrcInfos>"""

        assertEquals(
            "[1000,1000](1000,500,0)Hello(1500,500,0) world",
            normalizeQqLyricText(xml),
        )
    }

    @Test
    fun keepsAlreadyPrefixTimedQrcAndRegularLrcCompatible() {
        assertEquals(
            "[1000,500](1000,500,0)Hi",
            normalizeQqLyricText("[1000,500](1000,500)Hi"),
        )
        assertEquals("[00:01.00]Hi", normalizeQqLyricText("[00:01.00]Hi"))
    }
}
