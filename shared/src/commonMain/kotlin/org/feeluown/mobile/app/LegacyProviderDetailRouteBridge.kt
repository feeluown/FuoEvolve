package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compatibility renderer for the payload-less detail routes used before P2 ownership migration.
 *
 * The old routes cannot reconstruct content after process death because they never encoded a
 * payload. While the matching feature owner is still alive we render its current detail; if the
 * owner no longer has a target, we remove the stale route instead of leaving the user on a dead
 * "content migrated" page.
 */
@Composable
internal fun LegacyProviderDetailRouteBridge(route: AppRoute, appViewModel: FuoAppViewModel) {
    when (route) {
        AppRoute.Feature -> {
            val state by appViewModel.providerDetailOwners.feature.uiState.collectAsStateWithLifecycle()
            val feature = state.feature
            if (feature != null) ProviderFeatureParityDetailRoute(feature) else PopStaleLegacyRoute(appViewModel)
        }
        AppRoute.Playlist -> {
            val state by appViewModel.providerDetailOwners.playlist.uiState.collectAsStateWithLifecycle()
            val playlist = state.playlist
            if (playlist != null) ProviderPlaylistDetailRoute(playlist, state.category) else PopStaleLegacyRoute(appViewModel)
        }
        AppRoute.Track -> {
            val state by appViewModel.providerDetailOwners.track.uiState.collectAsStateWithLifecycle()
            val track = state.track
            if (track != null) ProviderTrackDetailRoute(track) else PopStaleLegacyRoute(appViewModel)
        }
        AppRoute.Video -> {
            val state by appViewModel.providerDetailOwners.video.uiState.collectAsStateWithLifecycle()
            val video = state.video
            if (video != null) ProviderVideoDetailRoute(video) else PopStaleLegacyRoute(appViewModel)
        }
        AppRoute.MediaItem -> {
            val state by appViewModel.providerDetailOwners.mediaItem.uiState.collectAsStateWithLifecycle()
            val item = state.item
            if (item != null) ProviderMediaItemDetailRoute(item) else PopStaleLegacyRoute(appViewModel)
        }
        else -> Unit
    }
}

@Composable
private fun PopStaleLegacyRoute(appViewModel: FuoAppViewModel) {
    LaunchedEffect(Unit) { appViewModel.dispatch(AppIntent.NavigateBack) }
}
