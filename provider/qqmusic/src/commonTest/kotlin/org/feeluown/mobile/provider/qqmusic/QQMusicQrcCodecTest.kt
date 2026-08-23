package org.feeluown.mobile.provider.qqmusic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QQMusicQrcCodecTest {
    @Test
    fun decryptsKnownQrcPayloadAndNormalizesWordTiming() {
        val encrypted = "d6a2be95d64473721b73c97024a81f8507967be980ad83772d7a8ec317a07c96417882618bc1a7d2ad434fb8500fa4b1"
        val decoded = assertNotNull(decodeQqLyricPayload(encrypted))

        assertEquals("Hello world", lyricText(decoded))
        assertEquals(listOf(1_000L to 500L, 1_500L to 500L), wordTimings(decoded))
    }

    @Test
    fun convertsQrcSuffixTimingsWithoutDroppingFirstWord() {
        val xml = """<?xml version="1.0"?><QrcInfos><LyricInfo><Lyric_1 LyricContent="[1000,1000]Hello(1000,500) world(1500,500)"/></LyricInfo></QrcInfos>"""
        val normalized = normalizeQqLyricText(xml)

        assertEquals("Hello world", lyricText(normalized))
        assertEquals(listOf(1_000L to 500L, 1_500L to 500L), wordTimings(normalized))
        assertTrue(normalized.startsWith("[1000,1000]"))
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
