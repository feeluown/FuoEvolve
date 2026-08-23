package org.feeluown.mobile.provider.qqmusic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class QQMusicQrcCodecTest {
    @Test
    fun decryptsKnownQrcPayloadAndNormalizesWordTiming() {
        val encrypted = "d6a2be95d64473721b73c97024a81f8507967be980ad83772d7a8ec317a07c96417882618bc1a7d2ad434fb8500fa4b1"
        val decoded = assertNotNull(decodeQqLyricPayload(encrypted))
        val words = wordTimings(decoded)

        assertEquals("Hello world", lyricText(decoded))
        assertEquals(2, words.size)
        assertEquals(1_000L, words.first().first)
        assertEquals(500L, words.first().second)
    }

    @Test
    fun convertsQrcSuffixTimingsWithoutDroppingFirstWord() {
        val xml = """<?xml version="1.0"?><QrcInfos><LyricInfo><Lyric_1 LyricContent="[1000,1000]Hello(1000,500) world(1500,500)"/></LyricInfo></QrcInfos>"""
        val normalized = normalizeQqLyricText(xml)
        val words = wordTimings(normalized)

        assertEquals(
            "[1000,1000](1000,500,0)Hello(1500,500,0) world",
            normalized,
        )
        assertEquals("Hello world", lyricText(normalized))
        assertEquals(2, words.size)
        assertEquals(1_000L, words.first().first)
        assertEquals(500L, words.first().second)
    }

    @Test
    fun keepsAlreadyPrefixTimedQrcAndRegularLrcCompatible() {
        assertEquals(
            "[1000,500](1000,500,0)Hi",
            normalizeQqLyricText("[1000,500](1000,500)Hi"),
        )
        assertEquals("[00:01.00]Hi", normalizeQqLyricText("[00:01.00]Hi"))
    }

    private fun lyricText(value: String): String =
        wordTimingRegex.replace(value.substringAfter(']', value), "").trim()

    private fun wordTimings(value: String): List<Pair<Long, Long>> =
        wordTimingRegex.findAll(value).map { match ->
            match.groupValues[1].toLong() to match.groupValues[2].toLong()
        }.toList()

    private companion object {
        val wordTimingRegex = Regex("""\((\d+),(\d+),\d+\)""")
    }
}
