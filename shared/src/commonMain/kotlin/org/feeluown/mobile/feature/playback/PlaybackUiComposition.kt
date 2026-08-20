package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition-only wiring graph for playback UI dependencies.
 *
 * This is deliberately not a feature/action contract: player composables consume the narrow ports
 * below. The graph only lets the app composition root install the same owner instances once.
 */
data class PlaybackUiGraph(
    val navigation: PlaybackNavigationPort,
    val presentation: PlaybackPresentationPort,
    val queue: PlaybackQueueUiPort,
    val sleepTimer: PlaybackSleepTimerPort,
    val downloads: DownloadActionPort,
    val playlists: PlaylistActionPort,
    val providerTrackActions: ProviderTrackActionPort,
    val localMusicActions: LocalMusicActionPort,
    val replacement: ReplacementActionPort,
) {
    val isFullPlayerOpen: Boolean
        get() = navigation.isFullPlayerOpen
    val currentTrack: MusicTrack?
        get() = presentation.currentTrack
}

val LocalPlaybackUiPort = staticCompositionLocalOf<PlaybackUiGraph> {
    error("PlaybackUiGraph is not provided")
}
val LocalPlaybackNavigationPort = staticCompositionLocalOf<PlaybackNavigationPort> {
    error("PlaybackNavigationPort is not provided")
}
val LocalPlaybackPresentationPort = staticCompositionLocalOf<PlaybackPresentationPort> {
    error("PlaybackPresentationPort is not provided")
}
val LocalPlaybackQueueUiPort = staticCompositionLocalOf<PlaybackQueueUiPort> {
    error("PlaybackQueueUiPort is not provided")
}
val LocalPlaybackSleepTimerPort = staticCompositionLocalOf<PlaybackSleepTimerPort> {
    error("PlaybackSleepTimerPort is not provided")
}
val LocalDownloadActionPort = staticCompositionLocalOf<DownloadActionPort> {
    error("DownloadActionPort is not provided")
}
val LocalPlaylistActionPort = staticCompositionLocalOf<PlaylistActionPort> {
    error("PlaylistActionPort is not provided")
}
val LocalProviderTrackActionPort = staticCompositionLocalOf<ProviderTrackActionPort> {
    error("ProviderTrackActionPort is not provided")
}
val LocalLocalMusicActionPort = staticCompositionLocalOf<LocalMusicActionPort> {
    error("LocalMusicActionPort is not provided")
}
val LocalReplacementActionPort = staticCompositionLocalOf<ReplacementActionPort> {
    error("ReplacementActionPort is not provided")
}

@Composable
fun ProvideNarrowPlaybackUi(
    graph: PlaybackUiGraph = LocalPlaybackUiPort.current,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPlaybackNavigationPort provides graph.navigation,
        LocalPlaybackPresentationPort provides graph.presentation,
        LocalPlaybackQueueUiPort provides graph.queue,
        LocalPlaybackSleepTimerPort provides graph.sleepTimer,
        LocalDownloadActionPort provides graph.downloads,
        LocalPlaylistActionPort provides graph.playlists,
        LocalProviderTrackActionPort provides graph.providerTrackActions,
        LocalLocalMusicActionPort provides graph.localMusicActions,
        LocalReplacementActionPort provides graph.replacement,
        content = content,
    )
}

/** Controller-free MiniPlayer entry point used by app/feature screens. */
@Composable
fun PlaybackMiniPlayer() {
    val graph = LocalPlaybackUiPort.current
    RuntimeMiniPlayer(
        playbackSession = LocalPlaybackSession.current,
        isFullPlayerOpen = graph.navigation.isFullPlayerOpen,
        transitionDirection = graph.queue.trackChangeDirection,
        onOpenFullPlayer = graph.navigation::openFullPlayer,
    )
}
