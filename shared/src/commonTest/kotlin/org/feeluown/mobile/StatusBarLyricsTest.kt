package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StatusBarLyricsTest {
    @Test
    fun lrcUsesNextLineAndSongDurationAsEnds() {
        val payload = assertIs<StatusBarLyricsPayload.Timed>(
            buildStatusBarLyricsPayload(
                rawLyrics = "[00:01.000]第一行\n[00:02.500]第二行",
                durationMs = 5_000,
            ),
        )

        assertEquals(1_000, payload.lines[0].beginMs)
        assertEquals(2_500, payload.lines[0].endMs)
        assertEquals(2_500, payload.lines[1].beginMs)
        assertEquals(5_000, payload.lines[1].endMs)
    }

    @Test
    fun translationAndRomanizationArePreserved() {
        val raw = composeLyricsWithRichTracks(
            main = "[00:01.000]春",
            translation = "[00:01.000]Spring",
            romanization = "[00:01.000]haru",
        )
        val payload = assertIs<StatusBarLyricsPayload.Timed>(
            buildStatusBarLyricsPayload(raw, durationMs = 2_000),
        )

        assertEquals("春", payload.lines.single().text)
        assertEquals("Spring", payload.lines.single().translation)
        assertEquals("haru", payload.lines.single().romanization)
    }

    @Test
    fun yrcWordTimingIsPreserved() {
        val payload = assertIs<StatusBarLyricsPayload.Timed>(
            buildStatusBarLyricsPayload(
                rawLyrics = "[1000,800](1000,300,0)你(1300,500,0)好",
                durationMs = 3_000,
            ),
        )
        val line = payload.lines.single()

        assertEquals("你好", line.text)
        assertEquals(1_000, line.beginMs)
        assertEquals(3_000, line.endMs)
        assertEquals(2, line.words?.size)
        assertEquals(StatusBarLyricWord(1_000, 1_300, "你"), line.words?.get(0))
        assertEquals(StatusBarLyricWord(1_300, 1_800, "好"), line.words?.get(1))
    }

    @Test
    fun untimedLyricsUseTextPayload() {
        val payload = assertIs<StatusBarLyricsPayload.Text>(
            buildStatusBarLyricsPayload("第一行\n第二行", durationMs = null),
        )
        assertEquals("第一行\n第二行", payload.text)
    }

    @Test
    fun emptyLyricsProduceEmptyPayload() {
        assertIs<StatusBarLyricsPayload.Empty>(buildStatusBarLyricsPayload(null, durationMs = 2_000))
        assertIs<StatusBarLyricsPayload.Empty>(buildStatusBarLyricsPayload("   ", durationMs = 2_000))
    }

    @Test
    fun lastLineWithoutDurationStillHasValidEnd() {
        val payload = assertIs<StatusBarLyricsPayload.Timed>(
            buildStatusBarLyricsPayload("[00:01.000]最后一行", durationMs = null),
        )
        val line = payload.lines.single()
        assertEquals(1_000, line.beginMs)
        assertEquals(1_001, line.endMs)
        assertNull(line.words)
    }
}
