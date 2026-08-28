package org.feeluown.mobile

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

private fun pageTransition(
    initialOffsetX: (Int) -> Int,
    targetOffsetX: (Int) -> Int,
    spatialSpec: FiniteAnimationSpec<IntOffset>,
    effectsSpec: FiniteAnimationSpec<Float>,
): ContentTransform = (
    slideInHorizontally(
        initialOffsetX = initialOffsetX,
        animationSpec = spatialSpec,
    ) + fadeIn(animationSpec = effectsSpec)
    ) togetherWith (
    slideOutHorizontally(
        targetOffsetX = targetOffsetX,
        animationSpec = spatialSpec,
    ) + fadeOut(animationSpec = effectsSpec)
    )

private fun forwardPageTransition(
    spatialSpec: FiniteAnimationSpec<IntOffset>,
    effectsSpec: FiniteAnimationSpec<Float>,
): ContentTransform = pageTransition(
    initialOffsetX = { it },
    targetOffsetX = { -it },
    spatialSpec = spatialSpec,
    effectsSpec = effectsSpec,
)

private fun popPageTransition(
    spatialSpec: FiniteAnimationSpec<IntOffset>,
    effectsSpec: FiniteAnimationSpec<Float>,
): ContentTransform = pageTransition(
    initialOffsetX = { -it },
    targetOffsetX = { it },
    spatialSpec = spatialSpec,
    effectsSpec = effectsSpec,
)

private fun settingsForwardPageTransition(
    spatialSpec: FiniteAnimationSpec<IntOffset>,
    effectsSpec: FiniteAnimationSpec<Float>,
): ContentTransform = pageTransition(
    initialOffsetX = { -it },
    targetOffsetX = { it },
    spatialSpec = spatialSpec,
    effectsSpec = effectsSpec,
)

private fun settingsPopPageTransition(
    spatialSpec: FiniteAnimationSpec<IntOffset>,
    effectsSpec: FiniteAnimationSpec<Float>,
): ContentTransform = pageTransition(
    initialOffsetX = { it },
    targetOffsetX = { -it },
    spatialSpec = spatialSpec,
    effectsSpec = effectsSpec,
)

private fun settingsNavigationMetadata(
    spatialSpec: FiniteAnimationSpec<IntOffset>,
    effectsSpec: FiniteAnimationSpec<Float>,
): Map<String, Any> =
    NavDisplay.transitionSpec { settingsForwardPageTransition(spatialSpec, effectsSpec) } +
        NavDisplay.popTransitionSpec { settingsPopPageTransition(spatialSpec, effectsSpec) } +
        NavDisplay.predictivePopTransitionSpec { settingsPopPageTransition(spatialSpec, effectsSpec) }

@Composable
internal fun AppNavHost(
    backStack: List<AppRoute>,
    appViewModel: FuoAppViewModel,
    uiGraph: AppUiGraph,
    platform: AppPlatformBindings,
    modifier: Modifier = Modifier,
) {
    val localPlaylistState by uiGraph.localPlaylist.uiState.collectAsStateWithLifecycle()
    val activeRoute = backStack.lastOrNull()
    val pageSpatialSpec = FuoMotion.defaultSpatialSpec<IntOffset>()
    val pageEffectsSpec = FuoMotion.fastEffectsSpec<Float>()

    LaunchedEffect(activeRoute, uiGraph.playback.queue) {
        uiGraph.playback.queue.setPlaybackContextHint(activeRoute?.toPlaybackContextSnapshot())
    }

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { appViewModel.onBack() },
        transitionSpec = { forwardPageTransition(pageSpatialSpec, pageEffectsSpec) },
        popTransitionSpec = { popPageTransition(pageSpatialSpec, pageEffectsSpec) },
        predictivePopTransitionSpec = { popPageTransition(pageSpatialSpec, pageEffectsSpec) },
        entryProvider = { route ->
            NavEntry(
                key = route,
                metadata = if (route == AppRoute.Settings) {
                    settingsNavigationMetadata(pageSpatialSpec, pageEffectsSpec)
                } else {
                    emptyMap()
                },
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
                    AppRoute.PlaybackHistory -> ListeningHistoryScreen(
                        repository = uiGraph.home.listeningHistory,
                        onBack = { appViewModel.onBack() },
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
                    AppRoute.MediaItem -> StaleRouteKindGuard { appViewModel.onBack() }
                }
            }
        },
    )
}

private fun AppRoute.toPlaybackContextSnapshot(): PlaybackContextSnapshot? = when (this) {
    is AppRoute.FeatureDetail -> PlaybackContextSnapshot(
        type = PlaybackContextType.Feature,
        sourceId = feature.providerId,
        resourceId = feature.id,
        title = feature.title,
        subtitle = feature.providerName,
    )
    is AppRoute.PlaylistDetail -> PlaybackContextSnapshot(
        type = PlaybackContextType.Playlist,
        sourceId = playlist.providerId,
        resourceId = playlist.id,
        title = playlist.title,
        subtitle = playlist.providerName,
        coverUrl = playlist.coverUrl,
    )
    is AppRoute.MediaItemDetail -> when (item.type) {
        MediaRefType.Album.name -> PlaybackContextSnapshot(
            type = PlaybackContextType.Album,
            sourceId = item.providerId,
            resourceId = item.id,
            title = item.title,
            subtitle = item.providerName,
            coverUrl = item.coverUrl,
        )
        MediaRefType.Artist.name -> PlaybackContextSnapshot(
            type = PlaybackContextType.Artist,
            sourceId = item.providerId,
            resourceId = item.id,
            title = item.title,
            subtitle = item.providerName,
            coverUrl = item.coverUrl,
        )
        else -> null
    }
    else -> null
}

@Composable
private fun StaleRouteKindGuard(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
