package org.feeluown.mobile

/**
 * Shared app-shell adapter for Search.
 *
 * Search owns query/result state. Cross-feature actions are composed here from narrow owners so
 * Android and iOS do not rebuild controller-backed forwarding objects independently.
 */
internal class DefaultSearchAppPort(
    private val searchController: SearchFeatureController,
    private val providerSessions: () -> ProviderSessionState,
    private val playbackQueue: PlaybackQueueUiPort,
    private val downloads: DownloadActionPort,
    private val playlists: PlaylistActionPort,
    private val providerTrackActions: ProviderTrackActionPort,
    private val navigator: AppNavigator,
) : SearchAppPort {
    override val providers: List<ProviderInfo>
        get() = providerSessions().providers

    override val downloadStates: Map<String, DownloadState>
        get() = downloads.downloadStates

    override fun closeSearch() {
        navigator.pop(AppRoute.Search)
    }

    override fun playResult(index: Int) {
        val tracks = searchController.uiState.value.searchResults
        if (index !in tracks.indices) return
        playbackQueue.playTracks(tracks, index)
        closeSearch()
    }

    override fun addToUpNext(track: MusicTrack) = playbackQueue.addToUpNext(track)

    override fun download(track: MusicTrack) = downloads.download(track)

    override fun deleteDownload(track: MusicTrack) = downloads.deleteDownload(track)

    override fun openArtist(track: MusicTrack) = providerTrackActions.openTrackArtist(track)

    override fun openAlbum(track: MusicTrack) = providerTrackActions.openTrackAlbum(track)

    override fun openTrackDetail(track: MusicTrack) {
        navigator.navigate(AppRoute.TrackDetail(track.toNavigationTrack()))
    }

    override fun canAddToPlaylist(track: MusicTrack): Boolean = playlists.canAddTrackToPlaylist(track)

    override fun openPlaylistTargetPicker(track: MusicTrack) = playlists.openPlaylistTargetPicker(track)

    override fun openMediaItem(item: ProviderMediaItem) {
        navigator.navigate(AppRoute.MediaItemDetail(item.toNavigationMediaItem()))
    }

    override fun openPlaylist(playlist: ProviderPlaylist) {
        navigator.navigate(AppRoute.PlaylistDetail(playlist.toNavigationPlaylist()))
    }

    override fun openVideo(video: ProviderVideo) {
        navigator.navigate(AppRoute.VideoDetail(video.toNavigationVideo()))
    }
}

/** Controller-free app-shell adapter for recognition result navigation. */
internal class DefaultRecognitionAppPort(
    private val isProviderEnabled: (String) -> Boolean,
    private val navigator: AppNavigator,
) : RecognitionAppPort {
    override fun canOpenNeteaseDetail(song: RecognizedSong): Boolean =
        isProviderEnabled(NETEASE_PROVIDER_ID) && !song.neteaseSongId.isNullOrBlank()

    override fun openNeteaseDetail(song: RecognizedSong) {
        if (!canOpenNeteaseDetail(song)) return
        val songId = song.neteaseSongId?.takeIf { it.isNotBlank() } ?: return
        val trackId = "$NETEASE_PROVIDER_ID:$songId"
        val routeTrack = MusicTrack(
            id = trackId,
            title = song.title,
            artists = song.artists.joinToString(" / "),
            album = song.album,
            source = NETEASE_PROVIDER_ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = song.coverUrl,
            providerId = trackId,
            providerName = "网易云音乐",
        )
        navigator.navigate(AppRoute.TrackDetail(routeTrack.toNavigationTrack()))
    }

    private companion object {
        const val NETEASE_PROVIDER_ID = "netease"
    }
}
