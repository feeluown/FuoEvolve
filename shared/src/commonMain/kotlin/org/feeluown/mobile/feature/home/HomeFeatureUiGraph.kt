package org.feeluown.mobile

import androidx.compose.runtime.staticCompositionLocalOf

internal data class HomeFeatureUiGraph(
    val home: HomeFeatureController,
    val playbackQueue: PlaybackQueueUiPort,
    val downloads: DownloadActionPort,
    val playlists: PlaylistActionPort,
    val providerTrackActions: ProviderTrackActionPort,
    val localPlaylist: LocalPlaylistFeatureController,
    val localMusic: LocalMusicFeatureController,
)

internal val LocalHomeFeatureUiGraph = staticCompositionLocalOf<HomeFeatureUiGraph> {
    error("HomeFeatureUiGraph is not installed")
}
