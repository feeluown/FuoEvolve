package org.feeluown.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

private val AppShellHomeSections = listOf(
    HomeSection.Recommend to "推荐",
    HomeSection.Music to "探索",
    HomeSection.Mine to "我的",
)

@Composable
internal fun AppShell(
    appViewModel: FuoAppViewModel,
    uiGraph: AppUiGraph,
    appUiState: AppUiState,
    platform: AppPlatformBindings,
) {
    val videoDetailState by uiGraph.providerDetail.owners.video.uiState.collectAsStateWithLifecycle()
    val homeState by uiGraph.home.home.uiState.collectAsStateWithLifecycle()
    val playback = uiGraph.playback
    val isPlaybackLoading by remember(uiGraph.playbackSession) {
        uiGraph.playbackSession.state
            .map { it.status == PlaybackSessionStatus.Loading }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = uiGraph.playbackSession.state.value.status == PlaybackSessionStatus.Loading,
    )
    // Measure only the player, not its surrounding navigation-bar inset. Each route's Scaffold
    // already consumes its own system insets, so reserving the inset twice creates a blank gap.
    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layoutInfo = remember(maxWidth, maxHeight) { appLayoutInfoFor(maxWidth, maxHeight) }
        val activeRoute = appUiState.backStack.lastOrNull()
        val miniPlayerVisible = !playback.isFullPlayerOpen &&
            activeRoute?.showsMiniPlayer(
                hasCurrentTrack = playback.currentTrack != null,
                hasQueueTrack = playback.queue.currentQueueTrack != null,
            ) == true
        val miniPlayerBottomPadding = with(density) {
            miniPlayerContentPadding(miniPlayerVisible, miniPlayerHeightPx.toDp())
        }
        val showShellNavigationRail = shouldShowShellNavigationRail(
            layoutInfo = layoutInfo,
            isFullPlayerOpen = playback.isFullPlayerOpen,
            isVideoFullscreen = videoDetailState.isFullscreen,
        )
        val selectedHomeSectionIndex = AppShellHomeSections
            .indexOfFirst { it.first == homeState.homeSection }
            .coerceAtLeast(0)
        val snackbarBottomPadding = if (miniPlayerVisible) {
            if (layoutInfo.useWideLayout) 80.dp else 96.dp
        } else {
            16.dp
        }
        val bottomOverlayInsets = WindowInsets.navigationBars
            .union(WindowInsets.tappableElement)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        val shellRailInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)

        CompositionLocalProvider(
            LocalPlaybackSession provides uiGraph.playbackSession,
            LocalPlaybackUiPort provides playback,
            LocalPlaybackMiniPlayerHostedByShell provides true,
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
                    Row(Modifier.fillMaxSize()) {
                                if (showShellNavigationRail) {
                                    Box(Modifier.windowInsetsPadding(shellRailInsets)) {
                                        HomeSectionRail(
                                            sections = AppShellHomeSections,
                                            selectedIndex = selectedHomeSectionIndex,
                                            onSettings = uiGraph.home.home::openSettings,
                                            onRefresh = {
                                                when (homeState.homeSection) {
                                                    HomeSection.Mine -> uiGraph.home.home.refreshMine()
                                                    HomeSection.Recommend,
                                                    HomeSection.Music -> uiGraph.home.home.refreshHome(homeState.homeSection)
                                                }
                                            },
                                            onSearch = uiGraph.home.home::openSearch,
                                            onRecognition = appViewModel::openRecognition,
                                            onClick = { _, section ->
                                                uiGraph.home.home.setHomeSection(section)
                                                appViewModel.openHome()
                                            },
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                ) {
                                    // Reserve space for the floating player at the one shared
                                    // navigation boundary, including routes with a fixed bottom CTA.
                                    AppNavHost(
                                        backStack = appUiState.backStack,
                                        appViewModel = appViewModel,
                                        uiGraph = uiGraph,
                                        platform = platform,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(bottom = miniPlayerBottomPadding),
                                    )
                                    if (miniPlayerVisible) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .windowInsetsPadding(bottomOverlayInsets),
                                        ) {
                                            Box(Modifier.onSizeChanged { miniPlayerHeightPx = it.height }) {
                                                PlaybackMiniPlayerOverlay()
                                            }
                                        }
                                    }
                                    AppGlobalOverlays(uiGraph)
                                    val feedbackModifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .windowInsetsPadding(bottomOverlayInsets)
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
}
