package org.feeluown.mobile

import androidx.compose.runtime.staticCompositionLocalOf

data class HomeFeatureUiGraph(
    val home: HomeFeatureController,
    val providerCatalog: ProviderCatalogFeatureController,
    val playbackQueue: PlaybackQueueUiPort,
    val downloads: DownloadActionPort,
    val playlists: PlaylistActionPort,
    val providerTrackActions: ProviderTrackActionPort,
    val localPlaylist: LocalPlaylistFeatureController,
    val localMusic: LocalMusicFeatureController,
)

val LocalHomeFeatureUiGraph = staticCompositionLocalOf<HomeFeatureUiGraph> {
    error("HomeFeatureUiGraph is not installed")
}
