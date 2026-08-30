package org.feeluown.mobile

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AppShell(
    appViewModel: FuoAppViewModel,
    uiGraph: AppUiGraph,
    appUiState: AppUiState,
    platform: AppPlatformBindings,
) {
    val videoDetailState by uiGraph.providerDetail.owners.video.uiState.collectAsStateWithLifecycle()
    val playback = uiGraph.playback
    val isPlaybackLoading by remember(uiGraph.playbackSession) {
        uiGraph.playbackSession.state
            .map { it.status == PlaybackSessionStatus.Loading }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = uiGraph.playbackSession.state.value.status == PlaybackSessionStatus.Loading,
    )
    val resourceHeroCoordinator = remember { ResourceHeroCoordinator() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layoutInfo = remember(maxWidth, maxHeight) { appLayoutInfoFor(maxWidth, maxHeight) }
        val miniPlayerVisible = !playback.isFullPlayerOpen &&
            appUiState.backStack.lastOrNull()?.showsMiniPlayer(
                hasCurrentTrack = playback.currentTrack != null,
                hasQueueTrack = playback.queue.currentQueueTrack != null,
                isVideoFullscreen = videoDetailState.isFullscreen,
            ) == true
        val snackbarBottomPadding = if (miniPlayerVisible) {
            if (layoutInfo.useWideLayout) 80.dp else 96.dp
        } else {
            16.dp
        }

        CompositionLocalProvider(
            LocalPlaybackSession provides uiGraph.playbackSession,
            LocalPlaybackUiPort provides playback,
            LocalLocalMusicUiGraph provides LocalMusicUiGraph(
                feature = uiGraph.localMusic,
                playbackQueue = playback.queue,
                downloads = playback.downloads,
                providerTrackActions = playback.providerTrackActions,
            ),
            LocalProviderDetailUiGraph provides uiGraph.providerDetail,
            LocalHomeFeatureUiGraph provides uiGraph.home,
            LocalShareHandler provides { platform.onShareText(it.text) },
            LocalLocalPlaylistFileActions provides LocalPlaylistFileActions(
                importFile = platform.onImportLocalPlaylistFile,
                exportFile = platform.onExportLocalPlaylistFile,
                shareFile = platform.onShareLocalPlaylistFile,
            ),
            LocalAppLayoutInfo provides layoutInfo,
        ) {
            ProvideNarrowPlaybackUi(playback) {
                ProvidePlaybackColorEnvironment(
                    themeMode = playback.presentation.themeMode,
                    dynamicCoverColorEnabled = playback.presentation.dynamicCoverColorEnabled,
                    coverImageUrl = playback.presentation.currentTrack?.coverUrl,
                    isLoading = isPlaybackLoading,
                ) {
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                        // NavDisplay may start predictive-pop visuals before onBack is dispatched.
                        // While the full player owns Back, keep underlying routes out of the shared
                        // transition scope so their resource-cover Hero cannot render above it.
                        val appSharedTransitionScope = if (playback.isFullPlayerOpen) null else this
                        CompositionLocalProvider(
                            LocalAppSharedTransitionScope provides appSharedTransitionScope,
                            LocalResourceHeroCoordinator provides resourceHeroCoordinator,
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                AppNavHost(
                                    backStack = appUiState.backStack,
                                    appViewModel = appViewModel,
                                    uiGraph = uiGraph,
                                    platform = platform,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AppGlobalOverlays(uiGraph)
                                val feedbackModifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(
                                        start = 16.dp,
                                        top = 16.dp,
                                        end = 16.dp,
                                        bottom = snackbarBottomPadding,
                                    )
                                if (playback.isFullPlayerOpen || miniPlayerVisible) {
                                    PlaybackDynamicColorTheme(
                                        emphasis = if (playback.isFullPlayerOpen) {
                                            PlaybackColorEmphasis.Immersive
                                        } else {
                                            PlaybackColorEmphasis.Ambient
                                        },
                                    ) {
                                        AppFeedbackHost(
                                            appViewModel = appViewModel,
                                            uiGraph = uiGraph,
                                            modifier = feedbackModifier,
                                        )
                                    }
                                } else {
                                    AppFeedbackHost(
                                        appViewModel = appViewModel,
                                        uiGraph = uiGraph,
                                        modifier = feedbackModifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
