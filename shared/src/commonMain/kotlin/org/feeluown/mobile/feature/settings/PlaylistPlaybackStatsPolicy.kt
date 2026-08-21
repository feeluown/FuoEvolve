package org.feeluown.mobile

private const val MAX_PLAYLIST_PLAYBACK_STATS = 500
private const val MAX_PLAYLIST_STATS_KEY_LENGTH = 2_048
private const val MAX_PLAYLIST_PLAY_COUNT = 1_000_000_000L
private const val PLAYLIST_PLAYBACK_STATS_VERSION = 1

internal fun normalizedPlaylistPlaybackStats(settings: AppSettings): Map<String, PlaylistPlaybackStat> {
    if (settings.playlistPlaybackStatsVersion != PLAYLIST_PLAYBACK_STATS_VERSION) return emptyMap()
    return settings.playlistPlaybackStats.entries
        .asSequence()
        .filter { (key, stat) ->
            key.isNotBlank() &&
                key.length <= MAX_PLAYLIST_STATS_KEY_LENGTH &&
                key.none(Char::isISOControl) &&
                stat.playCount in 1..MAX_PLAYLIST_PLAY_COUNT &&
                stat.lastPlayedAtMillis > 0
        }
        .sortedByDescending { it.value.lastPlayedAtMillis }
        .take(MAX_PLAYLIST_PLAYBACK_STATS)
        .associate { it.toPair() }
}
