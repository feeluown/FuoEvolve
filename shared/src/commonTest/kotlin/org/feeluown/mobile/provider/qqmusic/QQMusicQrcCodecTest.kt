package org.feeluown.mobile.provider.qqmusic

import org.feeluown.mobile.parseLyrics
import kotlin.test.Test
import kotlin.test.assertEquals

class QQMusicQrcCodecTest {
    @Test
    fun decryptsKnownQrcPayloadAndNormalizesWordTiming() {
        val encrypted = "d6a2be95d64473721b73c97024a81f8507967be980ad83772d7a8ec317a07c96417882618bc1a7d2ad434fb8500fa4b1"
        val decoded = decodeQqLyricPayload(encrypted)
        val line = parseLyrics(decoded).single()

        assertEquals("Hello world", line.text)
        assertEquals(2, line.words?.size)
        assertEquals(1_000L, line.words?.first()?.startMs)
        assertEquals(500L, line.words?.first()?.durationMs)
    }

    @Test
    fun extractsQrcXmlAndKeepsRegularLrcUntouched() {
        val xml = """<?xml version="1.0"?><QrcInfos><LyricInfo><Lyric_1 LyricContent="[1000,500]Hi(1000,500)"/></LyricInfo></QrcInfos>"""
        assertEquals(
            "[1000,500]Hi(1000,500,0)",
            normalizeQqLyricText(xml),
        )
        assertEquals("[00:01.00]Hi", normalizeQqLyricText("[00:01.00]Hi"))
    }
}
