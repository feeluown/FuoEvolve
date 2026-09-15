package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun emitsTimestampForEveryRichSecondaryLine() {
        val lyrics = composeRichLyrics(
            main = "[11820,2220](11820,120,0)The (11940,420,0)club",
            translation = "[00:11.820]这俱乐部",
            romanization = "[00:11.820]the club",
        )

        assertEquals(
            "[00:11.820]The club\n[00:11.820]这俱乐部\n[00:11.820]the club",
            toTimedLineLrc(lyrics),
        )
    }

    @Test
    fun separatesColorOsPrimaryTranslationAndWordTimelines() {
        val lyrics = composeRichLyrics(
            main = "[11820,2220](11820,120,0)The (11940,420,0)club",
            translation = "[00:11.820]这俱乐部",
            romanization = "[00:11.820]the club",
        )

        val platformLyrics = assertNotNull(toPlatformTimedLyrics(lyrics))

        assertEquals("[00:11.820]The club", platformLyrics.lyric)
        assertEquals("[00:11.820]这俱乐部", platformLyrics.translationLyric)
        assertEquals(
            "[00:11.820]<00:11.820>The <00:11.940>club<00:12.360>",
            platformLyrics.rawLyric,
        )
    }

    @Test
    fun appliesAlignmentOffsetToAllColorOsTimelines() {
        val lyrics = composeLyricsWithTranslation(
            "[1000,1000](1000,400,0)Hel(1400,600,0)lo",
            "[00:01.000]你好",
        )

        val platformLyrics = assertNotNull(toPlatformTimedLyrics(lyrics, alignmentOffsetMs = 1_250L))

        assertEquals("[00:02.250]Hello", platformLyrics.lyric)
        assertEquals("[00:02.250]你好", platformLyrics.translationLyric)
        assertEquals(
            "[00:02.250]<00:02.250>Hel<00:02.650>lo<00:03.250>",
            platformLyrics.rawLyric,
        )
    }

    @Test
    fun infersOffsetFromAlreadyAlignedLineLyrics() {
        val source = """
            [00:01.000]First
            [00:03.000]Second
        """.trimIndent()
        val aligned = """
            [00:02.250]First
            [00:04.250]Second
        """.trimIndent()

        assertEquals(1_250L, inferTimedLyricsAlignmentOffsetMs(source, aligned))
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
    fun appliesAlignmentOffsetToPlatformTimeline() {
        val lyrics = """
            [00:01.000]First
            [00:03.000]Second
        """.trimIndent()

        assertEquals(
            "[00:02.250]First\n[00:04.250]Second",
            toTimedLineLrc(lyrics, alignmentOffsetMs = 1_250L),
        )
        assertEquals(
            "[00:00.000]First\n[00:01.500]Second",
            toTimedLineLrc(lyrics, alignmentOffsetMs = -1_500L),
        )
    }

    @Test
    fun ignoresUntimedLyricsForLockScreenTimeline() {
        assertNull(toTimedLineLrc("plain lyrics without timestamps"))
        assertNull(toPlatformTimedLyrics("plain lyrics without timestamps"))
    }
}
