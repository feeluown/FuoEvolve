package org.feeluown.mobile

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val CompactWidthUpperBound = 600.dp
private val MediumWidthUpperBound = 840.dp
private val ExpandedWidthUpperBound = 1200.dp
private val LargeWidthUpperBound = 1600.dp
private val CompactHeightUpperBound = 480.dp
private val MediumHeightUpperBound = 900.dp
private val PersistentNavigationMinWidth = 900.dp
private val FullPlayerTwoPaneMinHeight = 600.dp

internal fun appLayoutInfoFor(maxWidth: Dp, maxHeight: Dp): AppLayoutInfo {
    val widthSizeClass = when {
        maxWidth < CompactWidthUpperBound -> AppWidthSizeClass.Compact
        maxWidth < MediumWidthUpperBound -> AppWidthSizeClass.Medium
        maxWidth < ExpandedWidthUpperBound -> AppWidthSizeClass.Expanded
        maxWidth < LargeWidthUpperBound -> AppWidthSizeClass.Large
        else -> AppWidthSizeClass.ExtraLarge
    }
    val heightSizeClass = when {
        maxHeight < CompactHeightUpperBound -> AppHeightSizeClass.Compact
        maxHeight < MediumHeightUpperBound -> AppHeightSizeClass.Medium
        else -> AppHeightSizeClass.Expanded
    }
    val hasExpandedWidth = widthSizeClass >= AppWidthSizeClass.Expanded
    val hasMediumWidth = widthSizeClass >= AppWidthSizeClass.Medium
    val hasUsableWideHeight = heightSizeClass != AppHeightSizeClass.Compact
    val gridColumns = if (heightSizeClass == AppHeightSizeClass.Compact) {
        // Short landscape windows are usually phones. Keep cards large enough to remain tappable
        // instead of deriving an aggressive desktop-like column count from width alone.
        if (maxWidth >= MediumWidthUpperBound) 4 else 3
    } else {
        when {
            maxWidth >= 1600.dp -> 8
            maxWidth >= 1200.dp -> 7
            maxWidth >= 980.dp -> 6
            maxWidth >= 760.dp -> 5
            maxWidth >= 640.dp -> 4
            else -> 3
        }
    }

    return AppLayoutInfo(
        widthSizeClass = widthSizeClass,
        heightSizeClass = heightSizeClass,
        isLandscape = maxWidth > maxHeight,
        useWideLayout = hasExpandedWidth && hasUsableWideHeight,
        // Material list-detail scenes are useful from medium width upward, but short phone
        // landscape windows remain single-pane even when their raw width crosses 600dp.
        useListDetailNavigation = hasMediumWidth && hasUsableWideHeight,
        // Keep enough room for the destination's own wide pane after reserving the 64dp rail.
        usePersistentNavigation = maxWidth >= PersistentNavigationMinWidth && hasUsableWideHeight,
        useFullPlayerTwoPane = hasExpandedWidth && maxHeight >= FullPlayerTwoPaneMinHeight,
        gridColumns = gridColumns,
    )
}

internal fun shouldShowShellNavigationRail(
    layoutInfo: AppLayoutInfo,
    isFullPlayerOpen: Boolean,
    isVideoFullscreen: Boolean,
): Boolean = layoutInfo.usePersistentNavigation &&
    !isFullPlayerOpen &&
    !isVideoFullscreen

internal fun AppRoute.showsMiniPlayer(
    hasCurrentTrack: Boolean,
    hasQueueTrack: Boolean,
): Boolean = when (this) {
    AppRoute.Home -> hasCurrentTrack
    AppRoute.PlaybackHistory,
    AppRoute.PlaylistMigration,
    AppRoute.LocalPlaylist,
    AppRoute.LocalMusicCollection,
    is AppRoute.FeatureDetail,
    is AppRoute.PlaylistDetail,
    is AppRoute.TrackDetail,
    is AppRoute.MediaItemDetail -> hasQueueTrack
    is AppRoute.VideoDetail -> false
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
