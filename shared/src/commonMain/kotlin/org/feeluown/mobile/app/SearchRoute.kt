package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** App-shell composition for the search feature. */
@Composable
internal fun SearchRoute(
    appViewModel: FuoAppViewModel,
    appPort: SearchAppPort,
) {
    val uiState by appViewModel.searchUiState.collectAsStateWithLifecycle()

    SearchFeatureScreen(
        uiState = uiState,
        providers = appPort.providers,
        downloadStates = appPort.downloadStates,
        actions = SearchFeatureActions(
            dispatch = appViewModel::dispatchSearch,
            onBack = appPort::closeSearch,
            onPlayResult = appPort::playResult,
            onAddToUpNext = appPort::addToUpNext,
            onDownload = appPort::download,
            onDeleteDownload = appPort::deleteDownload,
            onOpenArtist = appPort::openArtist,
            onOpenAlbum = appPort::openAlbum,
            onOpenTrackDetail = { track ->
                if (track.sourceType == TrackSourceType.Provider) {
                    { appPort.openTrackDetail(track) }
                } else {
                    null
                }
            },
            onAddToPlaylist = { track ->
                if (appPort.canAddToPlaylist(track)) {
                    { appPort.openPlaylistTargetPicker(track) }
                } else {
                    null
                }
            },
            onOpenMediaItem = appPort::openMediaItem,
            onOpenPlaylist = appPort::openPlaylist,
            onOpenVideo = appPort::openVideo,
        ),
        onOpenRecognition = appViewModel::openRecognition,
    )
}
