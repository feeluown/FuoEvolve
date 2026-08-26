package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** App-shell composition for the search feature. */
@Composable
internal fun SearchRoute(
    graph: SearchRouteGraph,
    onOpenRecognition: () -> Unit,
) {
    val uiState by graph.controller.uiState.collectAsStateWithLifecycle()
    val appPort = graph.appPort

    SearchFeatureScreen(
        uiState = uiState,
        providers = appPort.providers,
        downloadStates = appPort.downloadStates,
        actions = SearchFeatureActions(
            dispatch = graph.controller::dispatch,
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
        onOpenRecognition = onOpenRecognition,
    )
}
