package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class MinePlaylistRankingTest {
    @Test
    fun neteasePlaylistUsesCanonicalPlaybackStatsKey() {
        val playlist = neteasePlaylist("987654321")

        assertEquals(
            "netease::playlist:netease:987654321",
            minePlaylistPlaybackStatsKey(playlist),
        )
    }

    @Test
    fun frequentlyPlayedNeteasePlaylistIsRankedFromStatsSnapshot() {
        val netease = neteasePlaylist("987654321")
        val qqmusic = ProviderPlaylist(
            id = "playlist:qqmusic:abc",
            title = "QQ Playlist",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
        )
        val playbackStats = mapOf(
            minePlaylistPlaybackStatsKey(netease) to PlaylistPlaybackStat(
                playCount = 4,
                lastPlayedAtMillis = 2_000,
            ),
            minePlaylistPlaybackStatsKey(qqmusic) to PlaylistPlaybackStat(
                playCount = 2,
                lastPlayedAtMillis = 3_000,
            ),
        )

        assertEquals(
            listOf(netease, qqmusic),
            frequentlyPlayedMinePlaylists(
                playlists = listOf(qqmusic, netease, netease),
                playbackStats = playbackStats,
            ),
        )
    }

    @Test
    fun minePlaylistSortKeepsUnplayedOrderAfterPlayedItems() {
        val first = neteasePlaylist("1")
        val second = neteasePlaylist("2")
        val played = neteasePlaylist("3")
        val playbackStats = mapOf(
            minePlaylistPlaybackStatsKey(played) to PlaylistPlaybackStat(
                playCount = 1,
                lastPlayedAtMillis = 5_000,
            ),
        )

        assertEquals(
            listOf(played, first, second),
            sortedMinePlaylistsSnapshot(
                playlists = listOf(first, second, played),
                playbackStats = playbackStats,
            ),
        )
    }

    private fun neteasePlaylist(identifier: String) = ProviderPlaylist(
        id = "playlist:netease:$identifier",
        title = "NetEase Playlist $identifier",
        providerId = "netease",
        providerName = "网易云音乐",
    )
}
