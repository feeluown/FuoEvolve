package org.feeluown.mobile

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal data class AppBackTarget(
    val active: Flow<Boolean>,
    val isActiveNow: () -> Boolean,
    val dismiss: () -> Unit,
)

/**
 * Owns app-shell back precedence without making platform hosts aware of individual overlays.
 * Transient UI is dismissed before the current typed route is closed.
 */
class AppBackCoordinator internal constructor(
    private val navigator: AppNavigator,
    private val transientTargets: List<AppBackTarget>,
    private val closeRoute: (AppRoute) -> Boolean,
) {
    val hasTransientBack: Flow<Boolean> = if (transientTargets.isEmpty()) {
        flowOf(false)
    } else {
        combine(transientTargets.map { it.active }) { active -> active.any { it } }
            .distinctUntilChanged()
    }

    val hasTransientBackNow: Boolean
        get() = transientTargets.any { it.isActiveNow() }

    fun onBack(): Boolean {
        transientTargets.firstOrNull { it.isActiveNow() }?.let { target ->
            target.dismiss()
            return true
        }
        return closeRoute(navigator.currentEntry)
    }
}

fun createAppBackCoordinator(
    navigator: AppNavigator,
    playbackNavigationPort: PlaybackNavigationPort,
    playlistActionPort: PlaylistActionPort,
    providerTrackActionPort: ProviderTrackActionPort,
    localMusicFeatureController: LocalMusicFeatureController,
    providerDetailOwners: ProviderDetailOwners,
    searchAppPort: SearchAppPort,
    recognitionController: RecognitionFeatureController,
    settingsFeatureController: SettingsFeatureController,
    localPlaylistFeatureController: LocalPlaylistFeatureController,
): AppBackCoordinator {
    val transientTargets = listOf(
        AppBackTarget(
            active = playlistActionPort.targetPickerState.map { it.track != null }.distinctUntilChanged(),
            isActiveNow = { playlistActionPort.targetPickerState.value.track != null },
            dismiss = playlistActionPort::closePlaylistTargetPicker,
        ),
        AppBackTarget(
            active = providerTrackActionPort.artistTargetPickerState.map { it.track != null }.distinctUntilChanged(),
            isActiveNow = { providerTrackActionPort.artistTargetPickerState.value.track != null },
            dismiss = providerTrackActionPort::closeArtistTargetPicker,
        ),
        AppBackTarget(
            active = localMusicFeatureController.uiState.map { it.metadataEditorTrack != null }.distinctUntilChanged(),
            isActiveNow = { localMusicFeatureController.uiState.value.metadataEditorTrack != null },
            dismiss = localMusicFeatureController::closeMetadataEditor,
        ),
        AppBackTarget(
            active = snapshotFlow { playbackNavigationPort.isQueueOpen }.distinctUntilChanged(),
            isActiveNow = { playbackNavigationPort.isQueueOpen },
            dismiss = playbackNavigationPort::toggleQueue,
        ),
        AppBackTarget(
            active = snapshotFlow { playbackNavigationPort.isFullPlayerOpen }.distinctUntilChanged(),
            isActiveNow = { playbackNavigationPort.isFullPlayerOpen },
            dismiss = playbackNavigationPort::closeFullPlayer,
        ),
        AppBackTarget(
            active = providerDetailOwners.video.uiState.map { it.isFullscreen }.distinctUntilChanged(),
            isActiveNow = { providerDetailOwners.video.uiState.value.isFullscreen },
            dismiss = providerDetailOwners.video::toggleFullscreen,
        ),
    )

    fun closeRecognition(): Boolean {
        recognitionController.dispatch(RecognitionAction.Close)
        navigator.pop(AppRoute.AudioRecognition)
        return true
    }

    return AppBackCoordinator(
        navigator = navigator,
        transientTargets = transientTargets,
        closeRoute = { route ->
            when (route) {
                AppRoute.Home -> false
                AppRoute.Search -> {
                    searchAppPort.closeSearch()
                    true
                }
                AppRoute.AudioRecognition -> closeRecognition()
                AppRoute.Settings -> {
                    settingsFeatureController.close()
                    true
                }
                AppRoute.DebugLogs -> navigator.pop(AppRoute.DebugLogs)
                AppRoute.DownloadManager -> navigator.pop(AppRoute.DownloadManager)
                AppRoute.LocalPlaylist -> {
                    localPlaylistFeatureController.close()
                    true
                }
                AppRoute.LocalMusicCollection -> {
                    localMusicFeatureController.closeCollection()
                    true
                }
                is AppRoute.FeatureDetail -> {
                    providerDetailOwners.feature.close()
                    true
                }
                is AppRoute.PlaylistDetail -> {
                    providerDetailOwners.playlist.close()
                    true
                }
                is AppRoute.TrackDetail -> {
                    providerDetailOwners.track.close()
                    true
                }
                is AppRoute.VideoDetail -> {
                    providerDetailOwners.video.close()
                    true
                }
                is AppRoute.MediaItemDetail -> {
                    providerDetailOwners.mediaItem.close()
                    true
                }
                AppRoute.Feature,
                AppRoute.Playlist,
                AppRoute.Track,
                AppRoute.Video,
                AppRoute.MediaItem -> navigator.pop()
            }
        },
    )
}
