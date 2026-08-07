package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsParserTest {
    @Test
    fun parseLrcPairsTranslatedLinesByTimestamp() {
        val lines = parseLrc(
            """
            [by:lyrics.example]
            [00:06.220]Hello, it's me
            [00:11.320]I was wondering if after all these years you'd like to meet
            [00:06.220]你好 是我
            [00:11.320]我犹豫着要不要给你来电
            """.trimIndent(),
        )

        assertEquals(2, lines.size)
        assertEquals(6_220, lines[0].timeMs)
        assertEquals("Hello, it's me", lines[0].text)
        assertEquals("你好 是我", lines[0].translation)
        assertEquals("我犹豫着要不要给你来电", lines[1].translation)
    }

    @Test
    fun parseLrcKeepsLinesWithoutTranslationAndSkipsMetadata() {
        val lines = parseLrc(
            """
            [ar:Adele]
            [offset:0]
            [00:00.000]Hello
            [00:01.500]world
            """.trimIndent(),
        )

        assertEquals(2, lines.size)
        assertEquals("Hello", lines[0].text)
        assertNull(lines[0].translation)
        assertEquals(1_500, lines[1].timeMs)
    }

    @Test
    fun parseYrcParsesWordTimingsAndSkipsJsonMetadata() {
        val lines = parseYrc(
            """
            {"t":0,"c":[{"tx":"作词: "}]}
            [1000,2000](1000,500,0)逐(1500,500,0)字
            [3500,1800](3500,600,0)歌(4100,700,0)词
            """.trimIndent(),
        )

        assertEquals(2, lines.size)
        assertEquals(1_000, lines[0].timeMs)
        assertEquals("逐字", lines[0].text)
        assertEquals(
            listOf(
                LyricWord(1_000, 500, "逐"),
                LyricWord(1_500, 500, "字"),
            ),
            lines[0].words,
        )
        assertEquals("歌词", lines[1].text)
        assertEquals(2, lines[1].words?.size)
    }

    @Test
    fun parseLyricsDetectsYrcVersusLrc() {
        val yrc = parseLyrics("[1000,2000](1000,500,0)逐(1500,500,0)字")
        val lrc = parseLyrics("[00:01.00]普通歌词")

        assertEquals("逐字", yrc.single().text)
        assertTrue(!yrc.single().words.isNullOrEmpty())
        assertEquals("普通歌词", lrc.single().text)
        assertNull(lrc.single().words)
    }

    @Test
    fun parseLyricsAttachesYtlrcTranslationToYrcLines() {
        val raw = composeLyricsWithTranslation(
            """
            [11820,2220](11820,120,0)The (11940,420,0)club
            [14040,1860](14040,180,0)So (14220,150,0)the
            """.trimIndent(),
            """
            [00:11.820]这俱乐部
            [00:14.040]所以我们
            """.trimIndent(),
        )

        val lines = parseLyrics(raw)

        assertEquals(2, lines.size)
        assertEquals("The club", lines[0].text)
        assertEquals("这俱乐部", lines[0].translation)
        assertEquals(2, lines[0].words?.size)
        assertEquals("So the", lines[1].text)
        assertEquals("所以我们", lines[1].translation)
    }

    @Test
    fun parseLyricsAttachesTlyricTranslationToLrcLines() {
        val raw = composeLyricsWithTranslation(
            "[00:11.82]The club isn't the best place",
            "[00:11.82]这俱乐部不是个能找到安慰的地方",
        )

        val lines = parseLyrics(raw)

        assertEquals("The club isn't the best place", lines.single().text)
        assertEquals("这俱乐部不是个能找到安慰的地方", lines.single().translation)
        assertNull(lines.single().words)
    }

    @Test
    fun karaokeFillProgressUsesWordWidthsAndTimeline() {
        val words = listOf(
            LyricWord(1_000, 1_000, "逐"),
            LyricWord(2_000, 1_000, "字"),
        )
        val widths = listOf(10f, 30f)

        assertEquals(0f, karaokeFillProgress(words, 500, widths), absoluteTolerance = 0.0001f)
        assertEquals(0.125f, karaokeFillProgress(words, 1_500, widths), absoluteTolerance = 0.0001f)
        assertEquals(0.25f, karaokeFillProgress(words, 2_000, widths), absoluteTolerance = 0.0001f)
        assertEquals(0.625f, karaokeFillProgress(words, 2_500, widths), absoluteTolerance = 0.0001f)
        assertEquals(1f, karaokeFillProgress(words, 3_000, widths), absoluteTolerance = 0.0001f)
    }
}
