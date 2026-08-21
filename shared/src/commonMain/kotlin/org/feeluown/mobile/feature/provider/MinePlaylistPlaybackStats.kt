package org.feeluown.mobile

private const val MINE_PLAYLIST_STATS_KEY_SEPARATOR = "::"

internal fun minePlaylistPlaybackStatsKey(playlist: ProviderPlaylist): String =
    "${playlist.providerId}$MINE_PLAYLIST_STATS_KEY_SEPARATOR${playlist.id}"

internal fun sortedMinePlaylistsSnapshot(
    playlists: List<ProviderPlaylist>,
    playbackStats: Map<String, PlaylistPlaybackStat>,
): List<ProviderPlaylist> = playlists
    .mapIndexed { index, playlist ->
        RankedMinePlaylist(
            playlist = playlist,
            stat = playbackStats[minePlaylistPlaybackStatsKey(playlist)],
            originalIndex = index,
        )
    }
    .sortedWith(
        compareByDescending<RankedMinePlaylist> { it.stat?.lastPlayedAtMillis ?: 0L }
            .thenBy { it.originalIndex },
    )
    .map { it.playlist }

internal fun frequentlyPlayedMinePlaylists(
    playlists: List<ProviderPlaylist>,
    playbackStats: Map<String, PlaylistPlaybackStat>,
): List<ProviderPlaylist> = playlists
    .distinctBy(::minePlaylistPlaybackStatsKey)
    .mapIndexedNotNull { index, playlist ->
        val stat = playbackStats[minePlaylistPlaybackStatsKey(playlist)]
            ?.takeIf { it.playCount > 0 }
            ?: return@mapIndexedNotNull null
        RankedMinePlaylist(
            playlist = playlist,
            stat = stat,
            originalIndex = index,
        )
    }
    .sortedWith(
        compareByDescending<RankedMinePlaylist> { it.stat?.playCount ?: 0L }
            .thenByDescending { it.stat?.lastPlayedAtMillis ?: 0L }
            .thenBy { it.originalIndex },
    )
    .map { it.playlist }

private data class RankedMinePlaylist(
    val playlist: ProviderPlaylist,
    val stat: PlaylistPlaybackStat?,
    val originalIndex: Int,
)
