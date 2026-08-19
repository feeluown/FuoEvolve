package org.feeluown.mobile

import androidx.compose.runtime.Composable

/** Temporary app-shell bridge for callers that still compose search through the legacy facade. */
@Composable
internal fun SearchRoute(
    appViewModel: FuoAppViewModel,
    controller: FuoPlayerController,
) {
    SearchRoute(
        appViewModel = appViewModel,
        dependencies = SearchRouteDependencies(
            providers = { controller.providers },
            downloadStates = { controller.downloadStates },
            onBack = controller::closeSearch,
            onPlayResult = controller::playFromSearch,
            onAddToUpNext = controller::addToUpNext,
            onDownload = controller::download,
            onDeleteDownload = controller::deleteDownload,
            onOpenArtist = controller::openTrackArtist,
            onOpenAlbum = controller::openTrackAlbum,
            onOpenTrackDetail = controller::openTrackDetail,
            canAddToPlaylist = controller::canAddTrackToPlaylist,
            onOpenPlaylistTargetPicker = controller::openPlaylistTargetPicker,
            onOpenMediaItem = controller::openMediaItem,
            onOpenPlaylist = { playlist -> controller.openPlaylist(playlist) },
            onOpenVideo = controller::openVideo,
        ),
    )
}
