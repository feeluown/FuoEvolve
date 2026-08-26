package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyPlaylistStatsMigrationTest {
    @Test
    fun validLegacyPlaylistStatsBecomeProviderNeutralMigrationInputs() {
        val settings = AppSettings(
            playlistPlaybackStatsVersion = 1,
            playlistPlaybackStats = mapOf(
                "netease::playlist:netease:123" to PlaylistPlaybackStat(
                    playCount = 7,
                    lastPlayedAtMillis = 12_345L,
                ),
                "qqmusic::playlist:qqmusic:a::b" to PlaylistPlaybackStat(
                    playCount = 3,
                    lastPlayedAtMillis = 23_456L,
                ),
                "malformed" to PlaylistPlaybackStat(
                    playCount = 9,
                    lastPlayedAtMillis = 34_567L,
                ),
            ),
        )

        assertEquals(
            listOf(
                ListeningLegacyPlaylistStat(
                    sourceId = "qqmusic",
                    sourceResourceId = "playlist:qqmusic:a::b",
                    playCount = 3,
                    lastPlayedAtMillis = 23_456L,
                ),
                ListeningLegacyPlaylistStat(
                    sourceId = "netease",
                    sourceResourceId = "playlist:netease:123",
                    playCount = 7,
                    lastPlayedAtMillis = 12_345L,
                ),
            ),
            settings.toLegacyPlaylistListeningStats(),
        )
    }

    @Test
    fun unsupportedLegacyStatsVersionIsNotMigrated() {
        val settings = AppSettings(
            playlistPlaybackStatsVersion = 0,
            playlistPlaybackStats = mapOf(
                "netease::playlist:netease:123" to PlaylistPlaybackStat(
                    playCount = 7,
                    lastPlayedAtMillis = 12_345L,
                ),
            ),
        )

        assertTrue(settings.toLegacyPlaylistListeningStats().isEmpty())
    }
}
