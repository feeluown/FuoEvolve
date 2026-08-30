package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichLyricsTest {
    @Test
    fun keepsTranslationAndRomanizationOnDedicatedTracks() {
        val raw = composeRichLyrics(
            main = "[00:01.000]Hello\n[00:03.000]World",
            translation = "[00:01.020]你好\n[00:03.000]世界",
            romanization = "[00:01.000]hello\n[00:03.030]world",
        )

        val lines = parseLyrics(raw)
        assertEquals(2, lines.size)
        assertEquals("Hello", lines[0].text)
        assertEquals("你好", lines[0].translation)
        assertEquals("hello", lines[0].romanization)
        assertEquals("世界", lines[1].translation)
        assertEquals("world", lines[1].romanization)
        assertNull(lines[0].romanizationWords)
        assertTrue(!raw.contains('\u2028'))
    }

    @Test
    fun alignsSecondaryTracksToLyricInsteadOfNearbyCredit() {
        val raw = composeRichLyrics(
            main = """
                [00:01.000]作詞：でんの子P
                [00:01.200]ふわっとしたあなたゆるさせつなさ
            """.trimIndent(),
            translation = "[00:01.100]轻飘飘的你 摇摇晃晃 略显悲伤",
            romanization = "[00:01.200]fu wa tto shi ta a na ta yu ru sa se tsu na sa",
        )

        val lines = parseLyrics(raw)

        assertEquals(2, lines.size)
        assertEquals("作詞：でんの子P", lines[0].text)
        assertNull(lines[0].translation)
        assertNull(lines[0].romanization)
        assertEquals("ふわっとしたあなたゆるさせつなさ", lines[1].text)
        assertEquals("轻飘飘的你 摇摇晃晃 略显悲伤", lines[1].translation)
        assertEquals(
            "fu wa tto shi ta a na ta yu ru sa se tsu na sa",
            lines[1].romanization,
        )
    }

    @Test
    fun composeRichLyricsReservesFollowingExactSecondaryMatch() {
        val raw = composeRichLyrics(
            main = """
                [00:01.000]First
                [00:01.300]Second
            """.trimIndent(),
            translation = """
                [00:01.200]第一
                [00:01.300]第二
            """.trimIndent(),
            romanization = """
                [00:01.000]first
                [00:01.300]second
            """.trimIndent(),
        )

        val lines = parseLyrics(raw)

        assertEquals(2, lines.size)
        assertEquals("第一", lines[0].translation)
        assertEquals("第二", lines[1].translation)
    }

    @Test
    fun preservesWordTimedPrimaryAndRomanizationTracks() {
        val raw = composeRichLyrics(
            main = "[1000,2000](1000,500,0)Hel(1500,1500,0)lo",
            translation = "[00:01.000]你好",
            romanization = "[1000,2000](1000,500,0)hel(1500,1500,0)lo",
        )

        val line = parseLyrics(raw).single()
        assertEquals("Hello", line.text)
        assertEquals(2, line.words?.size)
        assertEquals("你好", line.translation)
        assertEquals("hello", line.romanization)
        assertEquals(
            listOf(
                LyricWord(1_000, 500, "hel"),
                LyricWord(1_500, 1_500, "lo"),
            ),
            line.romanizationWords,
        )
    }

    @Test
    fun translationOnlyPayloadStaysBackwardCompatible() {
        val main = "[00:01.000]Hello"
        val translation = "[00:01.000]你好"
        assertEquals(
            composeLyricsWithTranslation(main, translation),
            composeRichLyrics(main, translation = translation),
        )
    }

    @Test
    fun stripsStructuredYrcMetadataFromInstrumentalLyrics() {
        val raw = composeRichLyrics(
            main = """
                纯音乐，请欣赏
                {"t":0,"c":[{"tx":"作曲："},{"tx":"温菁 Jing.W (HOYO-MiX)"}]}
                {"t":555,"c":[{"tx":"编曲 Arranger：温菁 Jing.W (HOYO-MiX)"}]}
                {"t":1110,"c":[{"tx":"制谱 Music Copyist：吴泽熙 Jersey Wu (HOYO-MiX)"}]}
            """.trimIndent(),
        )

        assertEquals("纯音乐，请欣赏", raw)
        assertEquals(listOf("纯音乐，请欣赏"), parseLyrics(raw).map(LyricLine::text))
        assertTrue(!raw.contains("\"tx\""))
    }

    @Test
    fun stripsStructuredYrcMetadataWithoutDroppingWordTiming() {
        val raw = composeRichLyrics(
            main = """
                {"t":0,"c":[{"tx":"作词："},{"tx":"Someone"}]}
                [1000,2000](1000,500,0)Hel(1500,1500,0)lo
            """.trimIndent(),
        )

        val line = parseLyrics(raw).single()
        assertEquals("Hello", line.text)
        assertEquals(2, line.words?.size)
        assertTrue(!raw.contains("\"tx\""))
    }
}
