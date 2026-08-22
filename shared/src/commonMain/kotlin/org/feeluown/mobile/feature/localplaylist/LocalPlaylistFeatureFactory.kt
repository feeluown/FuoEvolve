package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.feeluown.mobile.feature.localplaylist.LocalPlaylistFeatureOperations
import org.feeluown.mobile.feature.localplaylist.LocalPlaylistFeatureOwner as CoreLocalPlaylistFeatureOwner
import org.feeluown.mobile.feature.localplaylist.LocalPlaylistFeatureState
import org.feeluown.mobile.feature.localplaylist.createLocalPlaylistFeatureOwner as createCoreLocalPlaylistFeatureOwner

typealias LocalPlaylistFeatureOwner = CoreLocalPlaylistFeatureOwner<
    MusicTrack,
    LocalPlaylist,
    LocalPlaylistImportPreview,
    LocalPlaylistImportMode,
    LocalPlaylistFile,
    LocalPlaylistOperationResult
>
typealias LocalPlaylistFeatureController = LocalPlaylistFeatureOwner
typealias LocalPlaylistController = LocalPlaylistFeatureOwner
typealias LocalPlaylistUiState = LocalPlaylistFeatureState<MusicTrack, LocalPlaylist, LocalPlaylistImportPreview>

fun createLocalPlaylistFeatureController(
    repository: LocalPlaylistRepository,
    navigator: AppNavigator,
    scope: CoroutineScope,
    providers: () -> List<ProviderInfo>,
): LocalPlaylistFeatureController {
    val operations = object : LocalPlaylistFeatureOperations<
        MusicTrack,
        LocalPlaylist,
        LocalPlaylistImportPreview,
        LocalPlaylistImportMode,
        LocalPlaylistFile,
        LocalPlaylistOperationResult
    > {
        override suspend fun list(): List<LocalPlaylist> = repository.list()
        override suspend fun create(title: String): LocalPlaylistOperationResult = repository.create(title)
        override suspend fun delete(playlist: LocalPlaylist): LocalPlaylistOperationResult = repository.delete(playlist)

        override suspend fun addTrack(
            playlist: LocalPlaylist,
            track: MusicTrack,
        ): LocalPlaylistOperationResult {
            val localTrack = track.toLocalPlaylistTrackForFeature()
                ?: return LocalPlaylistOperationResult(false, "当前歌曲无法添加到本地歌单")
            return repository.addTrack(playlist, localTrack)
        }

        override suspend fun removeTrack(
            playlist: LocalPlaylist,
            track: MusicTrack,
        ): LocalPlaylistOperationResult {
            val localTrack = track.toLocalPlaylistTrackForFeature()
                ?: return LocalPlaylistOperationResult(false, "当前歌曲无法从本地歌单移除")
            return repository.removeTrack(playlist, localTrack.uri)
        }

        override suspend fun importPlaylist(
            preview: LocalPlaylistImportPreview,
            mode: LocalPlaylistImportMode,
            replacePlaylist: LocalPlaylist?,
        ): LocalPlaylistOperationResult = repository.importPlaylist(preview, mode, replacePlaylist)

        override suspend fun export(playlist: LocalPlaylist): LocalPlaylistFile = repository.export(playlist)

        override fun decode(fileName: String, content: String): LocalPlaylistImportPreview =
            LocalPlaylistFileCodec.decode(fileName, content)

        override fun tracks(playlist: LocalPlaylist): List<MusicTrack> {
            val knownProviders = providers().associateBy(ProviderInfo::providerId)
            return playlist.tracks.map { localTrack ->
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
        }

        override fun canAddTrack(track: MusicTrack): Boolean = track.toLocalPlaylistTrackForFeature() != null

        override fun containsTrack(playlist: LocalPlaylist, track: MusicTrack): Boolean {
            val localTrack = track.toLocalPlaylistTrackForFeature() ?: return false
            return playlist.tracks.any { it.uri == localTrack.uri }
        }

        override fun removeTrackLocally(playlist: LocalPlaylist, track: MusicTrack): LocalPlaylist {
            val uri = track.toLocalPlaylistTrackForFeature()?.uri ?: return playlist
            return playlist.copy(tracks = playlist.tracks.filterNot { it.uri == uri })
        }

        override fun playlistId(playlist: LocalPlaylist): String = playlist.id
        override fun playlistTitle(playlist: LocalPlaylist): String = playlist.title
        override fun previewTitle(preview: LocalPlaylistImportPreview): String = preview.title
        override fun previewTrackCount(preview: LocalPlaylistImportPreview): Int = preview.tracks.size
        override fun previewSkippedLineCount(preview: LocalPlaylistImportPreview): Int = preview.skippedLineCount
        override fun resultSuccess(result: LocalPlaylistOperationResult): Boolean = result.success
        override fun resultMessage(result: LocalPlaylistOperationResult): String = result.message
        override fun resultPlaylist(result: LocalPlaylistOperationResult): LocalPlaylist? = result.playlist
        override fun withResultMessage(result: LocalPlaylistOperationResult, message: String): LocalPlaylistOperationResult =
            result.copy(message = message)
        override fun failureResult(message: String): LocalPlaylistOperationResult =
            LocalPlaylistOperationResult(success = false, message = message)
    }

    val owner = createCoreLocalPlaylistFeatureOwner(
        operations = operations,
        scope = scope,
        openPlaylist = { navigator.navigate(AppRoute.LocalPlaylist) },
        closePlaylist = { navigator.pop(AppRoute.LocalPlaylist) },
    )
    scope.launch { owner.loadForContent() }
    return owner
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
