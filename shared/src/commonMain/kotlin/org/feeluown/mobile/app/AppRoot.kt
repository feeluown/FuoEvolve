package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

val LocalShareHandler = staticCompositionLocalOf<(SharePayload) -> Unit> { {} }
val LocalAppLayoutInfo = staticCompositionLocalOf { AppLayoutInfo() }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalPlayerSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

data class AppLayoutInfo(
    val isLandscape: Boolean = false,
    val useWideLayout: Boolean = false,
    val gridColumns: Int = 3,
)

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

private data class SnackbarFeedbackEvent(
    val message: String,
    val dismiss: () -> Unit,
)

private fun Flow<String?>.toSnackbarFeedbackEvents(
    lifecycle: Lifecycle,
    dismissFeedback: (String) -> Unit,
): Flow<SnackbarFeedbackEvent> = mapNotNull { message ->
    if (message == null) {
        null
    } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        SnackbarFeedbackEvent(message) { dismissFeedback(message) }
    } else {
        dismissFeedback(message)
        null
    }
}

private fun pageTransition(
    initialOffsetX: (Int) -> Int,
    targetOffsetX: (Int) -> Int,
): ContentTransform = (
    slideInHorizontally(
        initialOffsetX = initialOffsetX,
        animationSpec = tween(FuoMotion.pageTransitionMillis),
    ) + fadeIn(animationSpec = tween(FuoMotion.pageFadeMillis))
    ) togetherWith (
    slideOutHorizontally(
        targetOffsetX = targetOffsetX,
        animationSpec = tween(FuoMotion.pageTransitionMillis),
    ) + fadeOut(animationSpec = tween(FuoMotion.pageFadeMillis))
    )

private fun forwardPageTransition(): ContentTransform =
    pageTransition(initialOffsetX = { it }, targetOffsetX = { -it })

private fun popPageTransition(): ContentTransform =
    pageTransition(initialOffsetX = { -it }, targetOffsetX = { it })

private fun settingsForwardPageTransition(): ContentTransform =
    pageTransition(initialOffsetX = { -it }, targetOffsetX = { it })

private fun settingsPopPageTransition(): ContentTransform =
    pageTransition(initialOffsetX = { it }, targetOffsetX = { -it })

private fun settingsNavigationMetadata(): Map<String, Any> =
    NavDisplay.transitionSpec { settingsForwardPageTransition() } +
        NavDisplay.popTransitionSpec { settingsPopPageTransition() } +
        NavDisplay.predictivePopTransitionSpec { settingsPopPageTransition() }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(
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
    val playbackGraph = appViewModel.playbackUiPort

    FuoTheme(
        themeMode = appUiState.themeMode,
        themeColorScheme = appUiState.themeColorScheme,
        themePaletteStyle = appUiState.themePaletteStyle,
        themeColorSpec = appUiState.themeColorSpec,
    ) {
        if (!appUiState.isInitialized) {
            AppInitializationLoadingScreen()
            return@FuoTheme
        }
        if (!appUiState.onboardingCompleted) {
            val onboarding = requireNotNull(appViewModel.onboardingFeatureController) {
                "Onboarding feature owner is not installed"
            }
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
            return@FuoTheme
        }

        val snackbar = remember { SnackbarHostState() }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val snackbarFeedbackEvents = remember(appViewModel, lifecycle) {
            merge(
                appViewModel.playlistActionPort.feedback.toSnackbarFeedbackEvents(
                    lifecycle,
                    appViewModel.playlistActionPort::dismissFeedback,
                ),
                appViewModel.playbackQueueUiPort.feedback.toSnackbarFeedbackEvents(
                    lifecycle,
                    appViewModel.playbackQueueUiPort::dismissFeedback,
                ),
                appViewModel.providerTrackActionPort.feedback.toSnackbarFeedbackEvents(
                    lifecycle,
                    appViewModel.providerTrackActionPort::dismissFeedback,
                ),
                appViewModel.downloadActionPort.managerState
                    .map { it.queueFeedback }
                    .distinctUntilChanged()
                    .toSnackbarFeedbackEvents(lifecycle, appViewModel.downloadActionPort::dismissQueueFeedback),
                appViewModel.playbackSleepTimerPort.feedback.toSnackbarFeedbackEvents(
                    lifecycle,
                    appViewModel.playbackSleepTimerPort::dismissFeedback,
                ),
                appViewModel.appFeedback.toSnackbarFeedbackEvents(lifecycle, appViewModel::dismissFeedback),
                appViewModel.sharedResourceActionPort.feedback.toSnackbarFeedbackEvents(
                    lifecycle,
                    appViewModel.sharedResourceActionPort::dismissFeedback,
                ),
            )
        }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            snackbar.currentSnackbarData?.dismiss()
        }
        LaunchedEffect(snackbarFeedbackEvents, lifecycle, snackbar) {
            snackbarFeedbackEvents.collect { event ->
                try {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        snackbar.showSnackbar(event.message)
                    }
                } finally {
                    event.dismiss()
                }
            }
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
                                    transitionSpec = { forwardPageTransition() },
                                    popTransitionSpec = { popPageTransition() },
                                    predictivePopTransitionSpec = { popPageTransition() },
                                    entryProvider = { route ->
                                        NavEntry(
                                            key = route,
                                            metadata = if (route == AppRoute.Settings) {
                                                settingsNavigationMetadata()
                                            } else {
                                                emptyMap()
                                            },
                                        ) {
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
                                                is AppRoute.FeatureDetail -> ProviderFeatureParityDetailRoute(route.feature.toProviderFeature())
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
                                                AppRoute.Feature,
                                                AppRoute.Playlist,
                                                AppRoute.Track,
                                                AppRoute.Video,
                                                AppRoute.MediaItem -> StaleRouteKindGuard(appViewModel)
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
                                PlaylistTargetFeatureDialog(
                                    actions = appViewModel.playlistActionPort,
                                    localPlaylist = appViewModel.localPlaylistFeatureController,
                                )
                                TrackArtistTargetFeatureDialog(appViewModel.providerTrackActionPort)
                                SnackbarHost(
                                    hostState = snackbar,
                                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                                ) { data ->
                                    Snackbar(
                                        snackbarData = data,
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun StaleRouteKindGuard(appViewModel: FuoAppViewModel) {
    LaunchedEffect(Unit) { appViewModel.dispatch(AppIntent.NavigateBack) }
}
