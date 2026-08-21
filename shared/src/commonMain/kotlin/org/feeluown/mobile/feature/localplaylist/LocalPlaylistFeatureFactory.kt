package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface LocalPlaylistFeatureOwner : LocalPlaylistFeatureController {
    fun canAddTrack(track: MusicTrack): Boolean
    fun addTrack(playlist: LocalPlaylist, track: MusicTrack)
}

fun createLocalPlaylistFeatureController(
    repository: LocalPlaylistRepository,
    navigator: AppNavigator,
    scope: CoroutineScope,
    providers: () -> List<ProviderInfo>,
): LocalPlaylistFeatureOwner {
    val controller = LocalPlaylistController(
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
    return object : LocalPlaylistFeatureOwner {
        override val uiState: StateFlow<LocalPlaylistUiState> = controller.uiState

        override fun refresh() = controller.refresh()
        override fun create(title: String) = controller.create(title)
        override fun open(playlist: LocalPlaylist) = controller.open(playlist)
        override fun close() = controller.close()
        override fun canRemove(track: MusicTrack): Boolean = controller.canRemove(track)
        override fun remove(track: MusicTrack) = controller.remove(track)
        override fun canDeleteSelected(): Boolean = controller.canDeleteSelected()
        override fun deleteSelected() = controller.deleteSelected()
        override fun prepareImport(fileName: String, content: String) = controller.prepareImport(fileName, content)
        override fun existingForImport(preview: LocalPlaylistImportPreview): LocalPlaylist? =
            controller.existingForImport(preview)
        override fun cancelImport() = controller.cancelImport()
        override fun importPlaylist(mode: LocalPlaylistImportMode, replacePlaylistId: String?) =
            controller.importPlaylist(mode, replacePlaylistId)
        override fun exportSelected(onReady: (LocalPlaylistFile) -> Unit) = controller.exportSelected(onReady)

        override fun canAddTrack(track: MusicTrack): Boolean = controller.canAddTrack(track)

        override fun addTrack(playlist: LocalPlaylist, track: MusicTrack) {
            if (!controller.canAddTrack(track)) return
            controller.openTargetPicker(track)
            controller.addTargetTrackTo(playlist)
        }
    }
}

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
