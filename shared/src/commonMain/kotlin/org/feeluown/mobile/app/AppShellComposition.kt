package org.feeluown.mobile

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun appLayoutInfoFor(maxWidth: Dp, maxHeight: Dp): AppLayoutInfo {
    val isLandscape = maxWidth > maxHeight
    return AppLayoutInfo(
        isLandscape = isLandscape,
        useWideLayout = isLandscape && maxWidth >= 640.dp,
        gridColumns = when {
            maxWidth >= 980.dp -> 6
            maxWidth >= 760.dp -> 5
            maxWidth >= 640.dp -> 4
            else -> 3
        },
    )
}

internal fun AppRoute.showsMiniPlayer(
    hasCurrentTrack: Boolean,
    hasQueueTrack: Boolean,
    isVideoFullscreen: Boolean,
): Boolean = when (this) {
    AppRoute.Home -> hasCurrentTrack
    AppRoute.PlaybackHistory,
    AppRoute.LocalPlaylist,
    AppRoute.LocalMusicCollection,
    is AppRoute.FeatureDetail,
    is AppRoute.PlaylistDetail,
    is AppRoute.TrackDetail,
    is AppRoute.MediaItemDetail -> hasQueueTrack
    is AppRoute.VideoDetail -> hasQueueTrack && !isVideoFullscreen
    AppRoute.Search,
    AppRoute.AudioRecognition,
    AppRoute.Settings,
    AppRoute.DebugLogs,
    AppRoute.DownloadManager,
    AppRoute.Feature,
    AppRoute.Playlist,
    AppRoute.Track,
    AppRoute.Video,
    AppRoute.MediaItem -> false
}
