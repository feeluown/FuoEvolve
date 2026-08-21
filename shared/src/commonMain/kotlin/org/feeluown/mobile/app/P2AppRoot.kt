package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun P2AppRoot(
    appViewModel: FuoAppViewModel,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onImportYtmusicOAuthFile: (() -> Unit)? = null,
    onStartYtmusicOAuth: (() -> Unit)? = null,
    onImportLocalPlaylistFile: (() -> Unit)? = null,
    onExportLocalPlaylistFile: ((String, String) -> Unit)? = null,
    onShareLocalPlaylistFile: ((String, String) -> Unit)? = null,
    onShareText: (String) -> Unit = {},
    appVersionInfo: String? = null,
    hasImagePermission: Boolean = true,
    onRequestImagePermission: () -> Unit = {},
) {
    val appUiState by appViewModel.uiState.collectAsStateWithLifecycle()
    val localPlaylistState by appViewModel.localPlaylistFeatureController.uiState.collectAsStateWithLifecycle()
    val playlistFeedback by appViewModel.playlistActionPort.feedback.collectAsStateWithLifecycle()
    val downloadState by appViewModel.downloadActionPort.managerState.collectAsStateWithLifecycle()
    val sleepFeedback by appViewModel.playbackSleepTimerPort.feedback.collectAsStateWithLifecycle()
    val playbackGraph = appViewModel.playbackUiPort

    FuoTheme(
        themeMode = appUiState.settings.settings.themeMode,
        themeColorScheme = appUiState.settings.settings.themeColorScheme,
        themePaletteStyle = appUiState.settings.settings.themePaletteStyle,
        themeColorSpec = appUiState.settings.settings.themeColorSpec,
    ) {
        if (!appUiState.isInitialized) {
            AppInitializationLoadingScreen()
            return@FuoTheme
        }
        if (!appUiState.onboardingCompleted) {
            val onboarding = appViewModel.onboardingFeatureController
            if (onboarding != null) {
                OnboardingFeatureScreen(
                    onboarding = onboarding,
                    settings = appViewModel.settingsFeatureController,
                    providerCatalog = appViewModel.providerCatalogFeatureController,
                    providerAuth = appViewModel.providerAuthFeatureController,
                    onOpenProviderWebLogin = onOpenProviderWebLogin,
                    onLogoutProvider = onLogoutProvider,
                    onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                    onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                    onStartYtmusicOAuth = onStartYtmusicOAuth,
                )
            } else {
                OnboardingScreen(
                    controller = appViewModel.controller,
                    onOpenProviderWebLogin = onOpenProviderWebLogin,
                    onLogoutProvider = onLogoutProvider,
                    onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                    onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                    onStartYtmusicOAuth = onStartYtmusicOAuth,
                )
            }
            return@FuoTheme
        }

        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(playlistFeedback) {
            val message = playlistFeedback ?: return@LaunchedEffect
            snackbar.showSnackbar(message)
            appViewModel.playlistActionPort.dismissFeedback(message)
        }
        LaunchedEffect(downloadState.queueFeedback) {
            val message = downloadState.queueFeedback ?: return@LaunchedEffect
            snackbar.showSnackbar(message)
            appViewModel.downloadActionPort.dismissQueueFeedback(message)
        }
        LaunchedEffect(sleepFeedback) {
            val message = sleepFeedback ?: return@LaunchedEffect
            snackbar.showSnackbar(message)
            appViewModel.playbackSleepTimerPort.dismissFeedback(message)
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val layoutInfo = remember(maxWidth, maxHeight) { appLayoutInfoFor(maxWidth, maxHeight) }
            CompositionLocalProvider(
                LocalPlaybackSession provides appViewModel.playbackSession,
                LocalPlaybackUiPort provides playbackGraph,
                LocalLocalMusicUiGraph provides LocalMusicUiGraph(
                    feature = appViewModel.localMusicFeatureController,
                    playbackQueue = appViewModel.playbackQueueUiPort,
                    downloads = appViewModel.downloadActionPort,
                    providerTrackActions = appViewModel.providerTrackActionPort,
                ),
                LocalProviderDetailUiGraph provides appViewModel.providerDetailUiGraph,
                LocalHomeFeatureUiGraph provides appViewModel.homeFeatureUiGraph,
                LocalShareHandler provides { onShareText(it.text) },
                LocalLocalPlaylistFileActions provides LocalPlaylistFileActions(
                    importFile = onImportLocalPlaylistFile,
                    exportFile = onExportLocalPlaylistFile,
                    shareFile = onShareLocalPlaylistFile,
                ),
                LocalAppLayoutInfo provides layoutInfo,
            ) {
                ProvideNarrowPlaybackUi(playbackGraph) {
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalPlayerSharedTransitionScope provides this) {
                            Box(Modifier.fillMaxSize()) {
                                NavDisplay(
                                    backStack = appUiState.backStack,
                                    modifier = Modifier.fillMaxSize(),
                                    onBack = { appViewModel.dispatch(AppIntent.NavigateBack) },
                                    entryProvider = { route ->
                                        NavEntry(key = route) {
                                            when (route) {
                                                AppRoute.Home -> HomeScreen(
                                                    home = appViewModel.homeFeatureController,
                                                    hasAudioPermission = hasAudioPermission,
                                                    onRequestAudioPermission = onRequestAudioPermission,
                                                    hasImagePermission = hasImagePermission,
                                                    onRequestImagePermission = onRequestImagePermission,
                                                    onOpenRecognition = appViewModel::openRecognition,
                                                )
                                                AppRoute.Search -> SearchRoute(appViewModel, appViewModel.searchAppPort)
                                                AppRoute.AudioRecognition -> RecognitionRoute(
                                                    appViewModel = appViewModel,
                                                    appPort = appViewModel.recognitionAppPort,
                                                    hasMicrophonePermission = hasMicrophonePermission,
                                                    onRequestMicrophonePermission = onRequestMicrophonePermission,
                                                )
                                                AppRoute.Settings -> SettingsFeatureScreen(
                                                    settingsController = appViewModel.settingsFeatureController,
                                                    providerCatalog = appViewModel.providerCatalogFeatureController,
                                                    providerAuth = appViewModel.providerAuthFeatureController,
                                                    appVersionInfo = appVersionInfo,
                                                    onOpenProviderWebLogin = onOpenProviderWebLogin,
                                                    onLogoutProvider = onLogoutProvider,
                                                    onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                                                    onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                                                    onStartYtmusicOAuth = onStartYtmusicOAuth,
                                                )
                                                AppRoute.DebugLogs -> DebugLogFeatureScreen(appViewModel.debugLogFeatureController, appViewModel::closeDebugLogs)
                                                AppRoute.DownloadManager -> DownloadManagerScreen(appViewModel.downloadActionPort, appViewModel::closeDownloadManager)
                                                is AppRoute.FeatureDetail -> ProviderFeatureDetailRoute(route.feature.toProviderFeature())
                                                is AppRoute.PlaylistDetail -> ProviderPlaylistDetailRoute(
                                                    playlist = route.playlist.toProviderPlaylist(),
                                                    category = route.category?.let { runCatching { ProviderFeatureCategory.valueOf(it) }.getOrNull() },
                                                )
                                                is AppRoute.TrackDetail -> ProviderTrackDetailRoute(route.track.toMusicTrack())
                                                is AppRoute.VideoDetail -> ProviderVideoDetailRoute(route.video.toProviderVideo())
                                                is AppRoute.MediaItemDetail -> ProviderMediaItemDetailRoute(route.item.toProviderMediaItem())
                                                AppRoute.LocalPlaylist -> LocalPlaylistScreen(
                                                    uiState = localPlaylistState,
                                                    actions = appViewModel.localPlaylistFeatureController,
                                                    playlist = localPlaylistState.selectedPlaylist,
                                                )
                                                AppRoute.LocalMusicCollection -> LocalMusicCollectionScreen()
                                                AppRoute.Feature, AppRoute.Playlist, AppRoute.Track, AppRoute.Video, AppRoute.MediaItem ->
                                                    EmptyHomeSection(Modifier.fillMaxSize(), "内容已迁移，请返回重试")
                                            }
                                        }
                                    },
                                )
                                AnimatedVisibility(
                                    visible = playbackGraph.isFullPlayerOpen,
                                    modifier = Modifier.fillMaxSize(),
                                    enter = slideInVertically(animationSpec = tween(FuoMotion.overlayEnterMillis)) { it / 2 } + fadeIn(tween(FuoMotion.overlayFadeMillis)),
                                    exit = slideOutVertically(animationSpec = tween(FuoMotion.overlayExitMillis)) { it / 2 } + fadeOut(tween(FuoMotion.overlayFadeMillis)),
                                ) { RuntimeFullPlayer() }
                                LocalMetadataDialog()
                                val legacy = appViewModel.controller
                                legacy.playlistTargetTrack?.let { PlaylistTargetDialog(legacy, it) }
                                legacy.artistTargetTrack?.let { TrackArtistTargetDialog(legacy, it) }
                                SnackbarHost(
                                    hostState = snackbar,
                                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
