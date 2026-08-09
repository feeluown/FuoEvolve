package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimedLineLyricsTest {
    @Test
    fun convertsYrcWordsToColorOsCompatibleLineLrc() {
        val lyrics = composeLyricsWithTranslation(
            "[11820,2220](11820,120,0)The (11940,420,0)club",
            "[00:11.820]这俱乐部",
        )

        assertEquals(
            "[00:11.820]The club\n[00:11.820]这俱乐部",
            toTimedLineLrc(lyrics),
        )
    }

    @Test
    fun normalizesLrcTimestampsAndKeepsTranslations() {
        val lyrics = """
            [ar:Example]
            [00:01.5]Hello
            [00:01.5]你好
            [01:02.03]World
        """.trimIndent()

        assertEquals(
            "[00:01.500]Hello\n[00:01.500]你好\n[01:02.030]World",
            toTimedLineLrc(lyrics),
        )
    }

    @Test
    fun ignoresUntimedLyricsForLockScreenTimeline() {
        assertNull(toTimedLineLrc("plain lyrics without timestamps"))
    }
}
