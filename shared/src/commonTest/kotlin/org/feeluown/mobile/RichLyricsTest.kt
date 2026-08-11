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
