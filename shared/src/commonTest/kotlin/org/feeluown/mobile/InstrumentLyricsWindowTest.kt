package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstrumentLyricsWindowTest {
    @Test
    fun timedLyricsExposePreviousCurrentAndNextLines() {
        val payload = StatusBarLyricsPayload.Timed(
            listOf(
                StatusBarLyricLine(0, 1_000, "第一行"),
                StatusBarLyricLine(1_000, 2_000, "第二行"),
                StatusBarLyricLine(2_000, 3_000, "第三行"),
            ),
        )

        assertEquals(
            InstrumentLyricsWindow("第一行", "第二行", "第三行"),
            buildInstrumentLyricsWindow(payload, 1_500),
        )
    }

    @Test
    fun positionBeforeFirstTimestampUsesFirstLineAsCurrent() {
        val payload = StatusBarLyricsPayload.Timed(
            listOf(
                StatusBarLyricLine(1_000, 2_000, "第一行"),
                StatusBarLyricLine(2_000, 3_000, "第二行"),
            ),
        )

        assertEquals(
            InstrumentLyricsWindow("", "第一行", "第二行"),
            buildInstrumentLyricsWindow(payload, 0),
        )
    }

    @Test
    fun untimedLyricsUseFirstLineAndFollowingLine() {
        val payload = StatusBarLyricsPayload.Text("第一行\n第二行\n第三行")

        assertEquals(
            InstrumentLyricsWindow("", "第一行", "第二行"),
            buildInstrumentLyricsWindow(payload, 10_000),
        )
    }

    @Test
    fun emptyPayloadHasNoWindow() {
        assertNull(buildInstrumentLyricsWindow(StatusBarLyricsPayload.Empty, 0))
    }
}
