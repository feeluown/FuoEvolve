package org.feeluown.mobile

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

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

@Composable
internal fun AppNavHost(
    backStack: List<AppRoute>,
    appViewModel: FuoAppViewModel,
    uiGraph: AppUiGraph,
    platform: AppPlatformBindings,
    modifier: Modifier = Modifier,
) {
    val localPlaylistState by uiGraph.localPlaylist.uiState.collectAsStateWithLifecycle()

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { appViewModel.onBack() },
        transitionSpec = { forwardPageTransition() },
        popTransitionSpec = { popPageTransition() },
        predictivePopTransitionSpec = { popPageTransition() },
        entryProvider = { route ->
            NavEntry(
                key = route,
                metadata = if (route == AppRoute.Settings) settingsNavigationMetadata() else emptyMap(),
            ) {
                when (route) {
                    AppRoute.Home -> HomeScreen(
                        home = uiGraph.home.home,
                        hasAudioPermission = platform.hasAudioPermission,
                        onRequestAudioPermission = platform.onRequestAudioPermission,
                        hasImagePermission = platform.hasImagePermission,
                        onRequestImagePermission = platform.onRequestImagePermission,
                        onOpenRecognition = appViewModel::openRecognition,
                    )
                    AppRoute.Search -> SearchRoute(
                        graph = uiGraph.search,
                        onOpenRecognition = appViewModel::openRecognition,
                    )
                    AppRoute.AudioRecognition -> RecognitionRoute(
                        graph = uiGraph.recognition,
                        onBack = appViewModel::closeRecognition,
                        onSearchSong = uiGraph.search.controller::searchRecognizedSong,
                        hasMicrophonePermission = platform.hasMicrophonePermission,
                        onRequestMicrophonePermission = platform.onRequestMicrophonePermission,
                    )
                    AppRoute.Settings -> SettingsFeatureScreen(
                        settingsController = uiGraph.settings,
                        providerCatalog = uiGraph.providerCatalog,
                        providerAuth = uiGraph.providerAuth,
                        appVersionInfo = platform.appVersionInfo,
                        onOpenProviderWebLogin = platform.onOpenProviderWebLogin,
                        onLogoutProvider = platform.onLogoutProvider,
                        onImportYtmusicHeaderFile = platform.onImportYtmusicHeaderFile,
                        onImportYtmusicOAuthFile = platform.onImportYtmusicOAuthFile,
                        onStartYtmusicOAuth = platform.onStartYtmusicOAuth,
                    )
                    AppRoute.DebugLogs -> DebugLogFeatureScreen(
                        uiGraph.debugLogs,
                        onBack = { appViewModel.onBack() },
                    )
                    AppRoute.DownloadManager -> DownloadManagerScreen(
                        uiGraph.playback.downloads,
                        onBack = { appViewModel.onBack() },
                    )
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
                        actions = uiGraph.localPlaylist,
                        playlist = localPlaylistState.selectedPlaylist,
                    )
                    AppRoute.LocalMusicCollection -> LocalMusicCollectionScreen()
                    AppRoute.Feature,
                    AppRoute.Playlist,
                    AppRoute.Track,
                    AppRoute.Video,
                    AppRoute.MediaItem -> StaleRouteKindGuard(appViewModel::onBack)
                }
            }
        },
    )
}

@Composable
private fun StaleRouteKindGuard(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
