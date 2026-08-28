package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val lyrics: PlaybackLyricsPort,
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
val LocalPlaybackLyricsPort = staticCompositionLocalOf<PlaybackLyricsPort> {
    error("PlaybackLyricsPort is not provided")
}
val LocalReplacementActionPort = staticCompositionLocalOf<ReplacementActionPort> {
    error("ReplacementActionPort is not provided")
}

@Composable
fun ProvideNarrowPlaybackUi(
    graph: PlaybackUiGraph = LocalPlaybackUiPort.current,
    content: @Composable () -> Unit,
) {
    val queueStateFlow = graph.queue.queueStateFlow
    val observedQueueState = if (queueStateFlow != null) {
        val state by queueStateFlow.collectAsStateWithLifecycle()
        state
    } else {
        null
    }
    val sleepTimerStateFlow = graph.sleepTimer.sleepTimerStateFlow
    val observedSleepTimerState = if (sleepTimerStateFlow != null) {
        val state by sleepTimerStateFlow.collectAsStateWithLifecycle()
        state
    } else {
        graph.sleepTimer.sleepTimerState
    }
    val replacementCandidateStateFlow = graph.replacement.replacementCandidateStateFlow
    val observedReplacementCandidateState = if (replacementCandidateStateFlow != null) {
        val state by replacementCandidateStateFlow.collectAsStateWithLifecycle()
        state
    } else {
        graph.replacement.replacementCandidateState
    }
    val observedQueue = observedQueueState?.let { queueState ->
        remember(graph.queue, queueState) {
            object : PlaybackQueueUiPort by graph.queue {
                override val currentQueueTrack: MusicTrack? = queueState.currentTrack()
                override val queue: List<MusicTrack> = queueState.displayQueue()
                override val displayUpNextCount: Int = queueState.upNextQueue.size
                override val isShuffleEnabled: Boolean = queueState.shuffleEnabled
                override val repeatMode: RepeatMode = queueState.repeatMode
                override val isFmQueueActive: Boolean = queueState.isFmQueue
            }
        }
    } ?: graph.queue
    val observedSleepTimer = remember(graph.sleepTimer, observedSleepTimerState) {
        object : PlaybackSleepTimerPort by graph.sleepTimer {
            override val sleepTimerState: SleepTimerState = observedSleepTimerState
        }
    }
    val observedReplacement = remember(graph.replacement, observedReplacementCandidateState) {
        object : ReplacementActionPort by graph.replacement {
            override val replacementCandidateState: ReplacementCandidateState = observedReplacementCandidateState
        }
    }
    CompositionLocalProvider(
        LocalPlaybackNavigationPort provides graph.navigation,
        LocalPlaybackPresentationPort provides graph.presentation,
        LocalPlaybackQueueUiPort provides observedQueue,
        LocalPlaybackSleepTimerPort provides observedSleepTimer,
        LocalDownloadActionPort provides graph.downloads,
        LocalPlaylistActionPort provides graph.playlists,
        LocalProviderTrackActionPort provides graph.providerTrackActions,
        LocalLocalMusicActionPort provides graph.localMusicActions,
        LocalPlaybackLyricsPort provides graph.lyrics,
        LocalReplacementActionPort provides observedReplacement,
        content = content,
    )
}

/** Controller-free MiniPlayer entry point used by app/feature screens. */
@Composable
fun PlaybackMiniPlayer() {
    val graph = LocalPlaybackUiPort.current
    PlaybackDynamicColorTheme(emphasis = PlaybackColorEmphasis.Ambient) {
        RuntimeMiniPlayer(
            playbackSession = LocalPlaybackSession.current,
            isFullPlayerOpen = graph.navigation.isFullPlayerOpen,
            transitionDirection = graph.queue.trackChangeDirection,
            onOpenFullPlayer = graph.navigation::openFullPlayer,
        )
    }
}
