package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope

fun createLocalPlaylistFeatureController(
    repository: LocalPlaylistRepository,
    navigator: AppNavigator,
    scope: CoroutineScope,
    providers: () -> List<ProviderInfo>,
): LocalPlaylistFeatureController = LocalPlaylistController(
    repository = repository,
    navigator = navigator,
    scope = scope,
    state = PlaylistControllerState(),
    toMusicTracks = { playlist ->
        val knownProviders = providers().associateBy(ProviderInfo::providerId)
        playlist.tracks.map { localTrack ->
            val provider = knownProviders[localTrack.providerId]
            val trackId = "${localTrack.providerId}:${localTrack.identifier}"
            MusicTrack(
                id = trackId,
                title = localTrack.title.ifBlank { localTrack.identifier },
                artists = localTrack.artists,
                album = localTrack.album,
                source = localTrack.providerId,
                sourceType = TrackSourceType.Provider,
                durationMs = localTrack.durationMs,
                providerId = trackId,
                providerName = provider?.providerName ?: localTrack.providerId,
                isUnavailable = provider == null,
            )
        }
    },
    toLocalPlaylistTrack = { track -> track.toLocalPlaylistTrackForFeature() },
    setLoading = {},
    setMessage = {},
    onError = {},
)

private fun MusicTrack.toLocalPlaylistTrackForFeature(): LocalPlaylistTrack? {
    if (sourceType != TrackSourceType.Provider && sourceType != TrackSourceType.Downloaded) return null
    val providerId = source.takeIf { it.isNotBlank() }
        ?: providerId?.substringBefore(":")?.takeIf { it.isNotBlank() }
        ?: return null
    val rawId = this.providerId?.takeIf { it.isNotBlank() } ?: id
    val identifier = rawId.removePrefix("$providerId:").trim()
    if (
        !providerId.matches(Regex("[A-Za-z0-9_]+")) ||
        !identifier.matches(Regex("[A-Za-z0-9_-]+"))
    ) {
        return null
    }
    return LocalPlaylistTrack(
        uri = LocalPlaylistFileCodec.normalizeSongUri(providerId, identifier),
        providerId = providerId,
        identifier = identifier,
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
    )
}
