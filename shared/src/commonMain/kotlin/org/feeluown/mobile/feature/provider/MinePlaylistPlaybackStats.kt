package org.feeluown.mobile

private const val MINE_PLAYLIST_STATS_KEY_SEPARATOR = "::"

internal fun minePlaylistPlaybackStatsKey(playlist: ProviderPlaylist): String =
    "${playlist.providerId}$MINE_PLAYLIST_STATS_KEY_SEPARATOR${playlist.id}"
