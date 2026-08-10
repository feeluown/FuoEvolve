package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RichLyricsTest {
    @Test
    fun rendersTranslationAndRomanizationOnSeparateLines() {
        val raw = composeRichLyrics(
            main = "[00:01.000]Hello\n[00:03.000]World",
            translation = "[00:01.020]你好\n[00:03.000]世界",
            romanization = "[00:01.000]hello\n[00:03.030]world",
        )

        val lines = parseLyrics(raw)
        assertEquals(2, lines.size)
        assertEquals("Hello", lines[0].text)
        assertEquals("你好\nhello", lines[0].translation)
        assertEquals("世界\nworld", lines[1].translation)
        assertTrue(!raw.contains('\u2028'))
    }

    @Test
    fun preservesWordTimedPrimaryTrack() {
        val raw = composeRichLyrics(
            main = "[1000,2000](1000,500,0)Hel(1500,1500,0)lo",
            translation = "[00:01.000]你好",
            romanization = "[00:01.000]hello",
        )

        val line = parseLyrics(raw).single()
        assertEquals("Hello", line.text)
        assertEquals(2, line.words?.size)
        assertTrue(line.translation?.contains("你好") == true)
        assertTrue(line.translation?.contains("hello") == true)
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
}
