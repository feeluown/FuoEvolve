package org.feeluown.mobile

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState.InProgress
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.math.abs

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

/**
 * Keep the Navigation 3 seek transition restrained. The outgoing route's scale is still owned by
 * NavDisplay so gesture cancellation/completion hands off naturally; rounded corners and bounded
 * pointer following are applied to the route surface itself from the same NavigationEventState.
 */
private fun predictivePopPageTransition(
    swipeEdge: Int,
    spatialSpec: FiniteAnimationSpec<Float>,
    effectsSpec: FiniteAnimationSpec<Float>,
): ContentTransform {
    val gestureOrigin = if (swipeEdge == NavigationEvent.EDGE_RIGHT) {
        TransformOrigin(0.82f, 0.5f)
    } else {
        TransformOrigin(0.18f, 0.5f)
    }
    return (
        scaleIn(
            initialScale = 0.992f,
            animationSpec = spatialSpec,
        ) + fadeIn(
            initialAlpha = 0.96f,
            animationSpec = effectsSpec,
        )
        ) togetherWith scaleOut(
        targetScale = 0.965f,
        transformOrigin = gestureOrigin,
        animationSpec = spatialSpec,
    )
}

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
    val predictiveBackPreference = rememberPredictiveBackPreference()
    val density = LocalDensity.current
    val pageSpatialSpec = FuoMotion.defaultSpatialSpec<IntOffset>()
    val pageEffectsSpec = FuoMotion.fastEffectsSpec<Float>()
    val predictiveSpatialSpec = FuoMotion.defaultSpatialSpec<Float>()
    val predictiveEffectsSpec = FuoMotion.defaultEffectsSpec<Float>()
    val predictiveReturnSpec = FuoMotion.fastSpatialSpec<Float>()
    val maxVerticalGestureDistancePx = with(density) { 240.dp.toPx() }
    val maxVerticalFollowPx = with(density) { 18.dp.toPx() }
    val horizontalFollowPx = with(density) { 6.dp.toPx() }

    var predictiveRoute by remember { mutableStateOf<AppRoute?>(null) }
    var predictiveGestureStartTouchY by remember { mutableStateOf<Float?>(null) }
    var predictiveGestureActive by remember { mutableStateOf(false) }
    var predictiveBackCommitted by remember { mutableStateOf(false) }
    var predictiveProgressTarget by remember { mutableFloatStateOf(0f) }
    var lastSwipeEdge by remember { mutableStateOf(NavigationEvent.EDGE_NONE) }
    var verticalFollowTargetPx by remember { mutableFloatStateOf(0f) }
    var horizontalFollowTargetPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(activeRoute, uiGraph.playback.queue) {
        uiGraph.playback.queue.setPlaybackContextHint(activeRoute?.toPlaybackContextSnapshot())
    }
    LaunchedEffect(activeRoute) {
        if (activeRoute == AppRoute.Home) {
            uiGraph.home.home.refreshCurrentSectionIfNeeded()
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider = { route ->
            NavEntry(key = route) {
                PredictiveBackRouteSurface(
                    active = predictiveRoute == route,
                    gestureActive = predictiveGestureActive,
                    committed = predictiveBackCommitted,
                    progressTarget = predictiveProgressTarget,
                    swipeEdge = lastSwipeEdge,
                    horizontalOffsetPx = horizontalFollowTargetPx,
                    verticalOffsetPx = verticalFollowTargetPx,
                    returnSpec = predictiveReturnSpec,
                    onCommittedExitDisposed = {
                        if (predictiveRoute == route) {
                            predictiveRoute = null
                            predictiveBackCommitted = false
                            predictiveProgressTarget = 0f
                            lastSwipeEdge = NavigationEvent.EDGE_NONE
                            horizontalFollowTargetPx = 0f
                            verticalFollowTargetPx = 0f
                        }
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
                            audioRecognitionAccess = platform.audioRecognitionAccess,
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
            }
        },
    )
    val sceneState = rememberSceneState(
        entries = entries,
        sceneStrategies = listOf(SinglePaneSceneStrategy()),
        onBack = { appViewModel.onBack() },
    )
    val currentScene = sceneState.currentScene
    val navigationEventState = rememberNavigationEventState(
        currentInfo = SceneInfo(currentScene),
        backInfo = sceneState.previousScenes.map { SceneInfo(it) },
    )
    val gestureEvent = (navigationEventState.transitionState as? InProgress)?.latestEvent
    val gestureInProgress = gestureEvent != null

    LaunchedEffect(gestureInProgress) {
        if (gestureInProgress && gestureEvent != null) {
            predictiveRoute = activeRoute
            predictiveGestureStartTouchY = gestureEvent.touchY
            predictiveGestureActive = true
            predictiveBackCommitted = false
        } else if (!predictiveBackCommitted) {
            predictiveGestureStartTouchY = null
            predictiveGestureActive = false
        }
    }
    LaunchedEffect(gestureEvent?.progress, gestureEvent?.touchY, gestureEvent?.swipeEdge) {
        if (gestureEvent != null) {
            predictiveProgressTarget = gestureEvent.progress.coerceIn(0f, 1f)
            lastSwipeEdge = gestureEvent.swipeEdge
            val startTouchY = predictiveGestureStartTouchY ?: gestureEvent.touchY
            val rawDeltaY = gestureEvent.touchY - startTouchY
            val normalizedDelta = (abs(rawDeltaY) / maxVerticalGestureDistancePx).coerceIn(0f, 1f)
            val easedDelta = normalizedDelta * (2f - normalizedDelta)
            val direction = when {
                rawDeltaY > 0f -> 1f
                rawDeltaY < 0f -> -1f
                else -> 0f
            }
            verticalFollowTargetPx = direction * maxVerticalFollowPx * easedDelta * gestureEvent.progress
            horizontalFollowTargetPx = when (gestureEvent.swipeEdge) {
                NavigationEvent.EDGE_LEFT -> horizontalFollowPx * gestureEvent.progress
                NavigationEvent.EDGE_RIGHT -> -horizontalFollowPx * gestureEvent.progress
                else -> 0f
            }
        }
    }

    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = predictiveBackPreference.isSupported &&
            predictiveBackPreference.enabled &&
            currentScene.previousEntries.isNotEmpty(),
        onBackCancelled = {
            predictiveGestureActive = false
            predictiveBackCommitted = false
            predictiveGestureStartTouchY = null
            predictiveProgressTarget = 0f
            verticalFollowTargetPx = 0f
            horizontalFollowTargetPx = 0f
        },
        onBackCompleted = {
            predictiveGestureActive = false
            predictiveBackCommitted = true
            predictiveGestureStartTouchY = null
            predictiveProgressTarget = 1f
            verticalFollowTargetPx = 0f
            horizontalFollowTargetPx = when (lastSwipeEdge) {
                NavigationEvent.EDGE_LEFT -> horizontalFollowPx
                NavigationEvent.EDGE_RIGHT -> -horizontalFollowPx
                else -> 0f
            }
            appViewModel.onBack()
        },
    )

    NavDisplay(
        sceneState = sceneState,
        navigationEventState = navigationEventState,
        modifier = modifier,
        transitionSpec = { forwardPageTransition(pageSpatialSpec, pageEffectsSpec) },
        popTransitionSpec = { popPageTransition(pageSpatialSpec, pageEffectsSpec) },
        predictivePopTransitionSpec = { swipeEdge ->
            predictivePopPageTransition(swipeEdge, predictiveSpatialSpec, predictiveEffectsSpec)
        },
    )
}

@Composable
private fun PredictiveBackRouteSurface(
    active: Boolean,
    gestureActive: Boolean,
    committed: Boolean,
    progressTarget: Float,
    swipeEdge: Int,
    horizontalOffsetPx: Float,
    verticalOffsetPx: Float,
    returnSpec: FiniteAnimationSpec<Float>,
    onCommittedExitDisposed: () -> Unit,
    content: @Composable () -> Unit,
) {
    val renderedProgress by animateFloatAsState(
        targetValue = if (active) progressTarget.coerceIn(0f, 1f) else 0f,
        animationSpec = if (active && gestureActive) snap() else returnSpec,
        label = "Route predictive corner progress",
    )
    val renderedHorizontalOffsetPx by animateFloatAsState(
        targetValue = if (active) horizontalOffsetPx else 0f,
        animationSpec = if (active && gestureActive) snap() else returnSpec,
        label = "Route predictive horizontal follow",
    )
    val renderedVerticalOffsetPx by animateFloatAsState(
        targetValue = if (active) verticalOffsetPx else 0f,
        animationSpec = if (active && gestureActive) snap() else returnSpec,
        label = "Route predictive vertical follow",
    )
    val cornerProgress = ((renderedProgress - 0.06f) / 0.94f).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(28.dp * cornerProgress)
    val transformOrigin = when (swipeEdge) {
        NavigationEvent.EDGE_LEFT -> TransformOrigin(0.18f, 0.5f)
        NavigationEvent.EDGE_RIGHT -> TransformOrigin(0.82f, 0.5f)
        else -> TransformOrigin.Center
    }

    DisposableEffect(active, committed) {
        onDispose {
            if (active && committed) onCommittedExitDisposed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = renderedHorizontalOffsetPx
                translationY = renderedVerticalOffsetPx
                this.transformOrigin = transformOrigin
                this.shape = shape
                clip = active && cornerProgress > 0f
            },
    ) {
        content()
    }
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
