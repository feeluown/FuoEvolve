package org.feeluown.mobile

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberSceneState
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

internal fun AppRoute.supportsAdaptiveListPane(): Boolean = when (this) {
    AppRoute.Search,
    AppRoute.PlaybackHistory,
    is AppRoute.FeatureDetail,
    is AppRoute.PlaylistDetail,
    is AppRoute.MediaItemDetail,
    AppRoute.LocalPlaylist,
    AppRoute.LocalMusicCollection -> true

    else -> false
}

internal fun AppRoute.supportsAdaptiveDetailPane(): Boolean = when (this) {
    is AppRoute.PlaylistDetail,
    is AppRoute.TrackDetail,
    is AppRoute.MediaItemDetail,
    AppRoute.LocalPlaylist,
    AppRoute.LocalMusicCollection -> true

    else -> false
}

internal fun hasAdaptiveListDetailPair(
    layoutInfo: AppLayoutInfo,
    backStack: List<AppRoute>,
): Boolean {
    val activeRoute = backStack.lastOrNull()
    return layoutInfo.useListDetailNavigation &&
        activeRoute?.supportsAdaptiveDetailPane() == true &&
        backStack.dropLast(1).any { it.supportsAdaptiveListPane() }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun AppRoute.adaptivePaneMetadata(
    activeRoute: AppRoute?,
    adaptivePairActive: Boolean,
): Map<String, Any> = when {
    !adaptivePairActive -> emptyMap()
    this == activeRoute && supportsAdaptiveDetailPane() -> ListDetailSceneStrategy.detailPane()
    supportsAdaptiveListPane() -> ListDetailSceneStrategy.listPane()
    else -> emptyMap()
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
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
    val rootLayoutInfo = LocalAppLayoutInfo.current
    val adaptivePairActive = hasAdaptiveListDetailPair(rootLayoutInfo, backStack)
    val pageSpatialSpec = FuoMotion.defaultSpatialSpec<IntOffset>()
    val pageEffectsSpec = FuoMotion.fastEffectsSpec<Float>()

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
            NavEntry(
                key = route,
                // listPane/detailPane share one role metadata key. Assign exactly one role per
                // entry only while a real list-detail pair exists. The active resource is detail;
                // previous resource details become list panes when navigation drills deeper.
                metadata = route.adaptivePaneMetadata(activeRoute, adaptivePairActive),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                        val paneLayoutInfo = remember(maxWidth, maxHeight, adaptivePairActive, route) {
                            val measured = appLayoutInfoFor(maxWidth, maxHeight)
                            if (
                                adaptivePairActive &&
                                (route.supportsAdaptiveListPane() || route.supportsAdaptiveDetailPane())
                            ) {
                                measured.copy(
                                    // Material adaptive navigation already owns the horizontal pane
                                    // split. Keep each pane internally compact to avoid nested splits.
                                    useWideLayout = false,
                                    usePersistentNavigation = false,
                                )
                            } else {
                                measured
                            }
                        }
                        val isAdaptiveDetailPane = adaptivePairActive &&
                            route == activeRoute &&
                            route.supportsAdaptiveDetailPane()
                        CompositionLocalProvider(
                            LocalAppLayoutInfo provides paneLayoutInfo,
                            LocalAppIsAdaptiveDetailPane provides isAdaptiveDetailPane,
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
                                AppRoute.PlaylistMigration,
                                is AppRoute.PlaylistMigrationDetail -> {
                                    val migration = uiGraph.playlistMigration
                                    if (migration == null) {
                                        StaleRouteKindGuard { appViewModel.onBack() }
                                    } else {
                                        val detail = route as? AppRoute.PlaylistMigrationDetail
                                        PlaylistMigrationScreen(
                                            controller = migration,
                                            onBack = { appViewModel.onBack() },
                                            initialTaskId = detail?.taskId,
                                            initialTarget = detail?.target,
                                            onPrepareBackgroundWork = platform.onPreparePlaylistMigrationBackground,
                                        )
                                    }
                                }
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
                                    desktopVideoSettingsAvailable = platform.desktopVideoSettingsAvailable,
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
                                    category = route.category?.let {
                                        runCatching { ProviderFeatureCategory.valueOf(it) }.getOrNull()
                                    },
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
            }
        },
    )
    val listDetailSceneStrategy = rememberListDetailSceneStrategy<AppRoute>()
    val sceneState = rememberSceneState(
        entries = entries,
        sceneStrategies = if (rootLayoutInfo.useListDetailNavigation) {
            listOf(listDetailSceneStrategy, SinglePaneSceneStrategy())
        } else {
            listOf(SinglePaneSceneStrategy())
        },
        onBack = { appViewModel.onBack() },
    )
    NavDisplay(
        sceneState = sceneState,
        modifier = modifier,
        transitionSpec = { forwardPageTransition(pageSpatialSpec, pageEffectsSpec) },
        popTransitionSpec = { popPageTransition(pageSpatialSpec, pageEffectsSpec) },
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
