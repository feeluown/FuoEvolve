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
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlayerSharedTransitionScope provides this) {
                        Box(Modifier.fillMaxSize()) {
                            AppNavHost(
                                backStack = appUiState.backStack,
                                appViewModel = appViewModel,
                                uiGraph = uiGraph,
                                platform = platform,
                                modifier = Modifier.fillMaxSize(),
                            )
                            AppGlobalOverlays(uiGraph)
                            AppFeedbackHost(
                                appViewModel = appViewModel,
                                uiGraph = uiGraph,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(
                                        start = 16.dp,
                                        top = 16.dp,
                                        end = 16.dp,
                                        bottom = snackbarBottomPadding,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
