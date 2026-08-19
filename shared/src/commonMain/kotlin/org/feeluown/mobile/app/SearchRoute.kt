package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Narrow app-shell dependencies for the search feature. */
internal data class SearchRouteDependencies(
    val providers: () -> List<ProviderInfo>,
    val downloadStates: () -> Map<String, DownloadState>,
    val onBack: () -> Unit,
    val onPlayResult: (Int) -> Unit,
    val onAddToUpNext: (MusicTrack) -> Unit,
    val onDownload: (MusicTrack) -> Unit,
    val onDeleteDownload: (MusicTrack) -> Unit,
    val onOpenArtist: (MusicTrack) -> Unit,
    val onOpenAlbum: (MusicTrack) -> Unit,
    val onOpenTrackDetail: (MusicTrack) -> Unit,
    val canAddToPlaylist: (MusicTrack) -> Boolean,
    val onOpenPlaylistTargetPicker: (MusicTrack) -> Unit,
    val onOpenMediaItem: (ProviderMediaItem) -> Unit,
    val onOpenPlaylist: (ProviderPlaylist) -> Unit,
    val onOpenVideo: (ProviderVideo) -> Unit,
)

/** App-shell composition for the search feature. */
@Composable
internal fun SearchRoute(
    appViewModel: FuoAppViewModel,
    dependencies: SearchRouteDependencies,
) {
    val uiState by appViewModel.searchUiState.collectAsStateWithLifecycle()

    SearchFeatureScreen(
        uiState = uiState,
        providers = dependencies.providers(),
        downloadStates = dependencies.downloadStates(),
        actions = SearchFeatureActions(
            dispatch = appViewModel::dispatchSearch,
            onBack = dependencies.onBack,
            onPlayResult = dependencies.onPlayResult,
            onAddToUpNext = dependencies.onAddToUpNext,
            onDownload = dependencies.onDownload,
            onDeleteDownload = dependencies.onDeleteDownload,
            onOpenArtist = dependencies.onOpenArtist,
            onOpenAlbum = dependencies.onOpenAlbum,
            onOpenTrackDetail = { track ->
                if (track.sourceType == TrackSourceType.Provider) {
                    { dependencies.onOpenTrackDetail(track) }
                } else {
                    null
                }
            },
            onAddToPlaylist = { track ->
                if (dependencies.canAddToPlaylist(track)) {
                    { dependencies.onOpenPlaylistTargetPicker(track) }
                } else {
                    null
                }
            },
            onOpenMediaItem = dependencies.onOpenMediaItem,
            onOpenPlaylist = dependencies.onOpenPlaylist,
            onOpenVideo = dependencies.onOpenVideo,
        ),
        onOpenRecognition = appViewModel::openRecognition,
    )
}
