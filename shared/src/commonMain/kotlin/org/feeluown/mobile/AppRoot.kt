package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

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

private const val PAGE_TRANSITION_DURATION_MILLIS = FuoMotion.pageTransitionMillis

private fun pageTransition(
    initialOffsetX: (Int) -> Int,
    targetOffsetX: (Int) -> Int,
): ContentTransform = (
    slideInHorizontally(
        initialOffsetX = initialOffsetX,
        animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS),
    ) + fadeIn(animationSpec = tween(FuoMotion.pageFadeMillis))
    ) togetherWith (
    slideOutHorizontally(
        targetOffsetX = targetOffsetX,
        animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS),
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
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
    val controller = appViewModel.controller
    FuoTheme(
        themeMode = appUiState.settings.settings.themeMode,
        themeColorScheme = appUiState.settings.settings.themeColorScheme,
        themePaletteStyle = appUiState.settings.settings.themePaletteStyle,
        themeColorSpec = appUiState.settings.settings.themeColorSpec,
    ) {
        if (!controller.isSettingsLoaded) {
            AppInitializationLoadingScreen()
            return@FuoTheme
        }
        if (!controller.onboardingCompleted) {
            OnboardingScreen(
                controller = controller,
                onOpenProviderWebLogin = onOpenProviderWebLogin,
                onLogoutProvider = onLogoutProvider,
                onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                onStartYtmusicOAuth = onStartYtmusicOAuth,
            )
            return@FuoTheme
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val playlistOperationFeedback = controller.playlistOperationFeedback
        val downloadQueueFeedback = controller.downloadQueueFeedback
        val playbackFeedback = controller.playbackFeedback
        LaunchedEffect(playlistOperationFeedback) {
            playlistOperationFeedback ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(playlistOperationFeedback)
            controller.dismissPlaylistOperationFeedback(playlistOperationFeedback)
        }
        LaunchedEffect(downloadQueueFeedback) {
            downloadQueueFeedback ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(downloadQueueFeedback)
            controller.dismissDownloadQueueFeedback(downloadQueueFeedback)
        }
        LaunchedEffect(playbackFeedback) {
            playbackFeedback ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(playbackFeedback)
            controller.dismissPlaybackFeedback(playbackFeedback)
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layoutInfo = remember(maxWidth, maxHeight) {
                appLayoutInfoFor(maxWidth, maxHeight)
            }

            val currentFeature = controller.selectedFeature
            val currentTrack = controller.selectedTrack
            val currentVideo = controller.selectedVideo
            val currentPlaylist = controller.selectedPlaylist
            val currentLocalPlaylist = controller.selectedLocalPlaylist
            val currentMediaItem = controller.selectedMediaItem
            var lastFeature by remember { mutableStateOf<ProviderFeature?>(null) }
            var lastTrack by remember { mutableStateOf<MusicTrack?>(null) }
            var lastVideo by remember { mutableStateOf<ProviderVideo?>(null) }
            var lastPlaylist by remember { mutableStateOf<ProviderPlaylist?>(null) }
            var lastLocalPlaylist by remember { mutableStateOf<LocalPlaylist?>(null) }
            var lastMediaItem by remember { mutableStateOf<ProviderMediaItem?>(null) }

            LaunchedEffect(currentFeature) {
                if (currentFeature != null) {
                    lastFeature = currentFeature
                }
            }
            LaunchedEffect(currentTrack) {
                if (currentTrack != null) {
                    lastTrack = currentTrack
                }
            }
            LaunchedEffect(currentVideo) {
                if (currentVideo != null) {
                    lastVideo = currentVideo
                }
            }
            LaunchedEffect(currentPlaylist) {
                if (currentPlaylist != null) {
                    lastPlaylist = currentPlaylist
                }
            }
            LaunchedEffect(currentLocalPlaylist) {
                if (currentLocalPlaylist != null) {
                    lastLocalPlaylist = currentLocalPlaylist
                }
            }
            LaunchedEffect(currentMediaItem) {
                if (currentMediaItem != null) {
                    lastMediaItem = currentMediaItem
                }
            }

            CompositionLocalProvider(
                LocalShareHandler provides { onShareText(it.text) },
                LocalLocalPlaylistFileActions provides LocalPlaylistFileActions(
                    importFile = onImportLocalPlaylistFile,
                    exportFile = onExportLocalPlaylistFile,
                    shareFile = onShareLocalPlaylistFile,
                ),
                LocalAppLayoutInfo provides layoutInfo,
            ) {
                SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlayerSharedTransitionScope provides this) {
                        Box(modifier = Modifier.fillMaxSize()) {
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
                                                controller = controller,
                                                hasAudioPermission = hasAudioPermission,
                                                onRequestAudioPermission = onRequestAudioPermission,
                                                hasImagePermission = hasImagePermission,
                                                onRequestImagePermission = onRequestImagePermission,
                                                onOpenRecognition = controller::openRecognition,
                                            )
                                            AppRoute.DebugLogs -> DebugLogScreen(controller)
                                            AppRoute.DownloadManager -> DownloadManagerScreen(controller)
                                            AppRoute.Settings -> SettingsScreenV2(
                                                controller = controller,
                                                themePaletteStyle = appUiState.settings.settings.themePaletteStyle,
                                                onThemePaletteStyleChange = {
                                                    appViewModel.dispatch(AppIntent.UpdateThemePaletteStyle(it))
                                                },
                                                themeColorSpec = appUiState.settings.settings.themeColorSpec,
                                                onThemeColorSpecChange = {
                                                    appViewModel.dispatch(AppIntent.UpdateThemeColorSpec(it))
                                                },
                                                onOpenProviderWebLogin = onOpenProviderWebLogin,
                                                onLogoutProvider = onLogoutProvider,
                                                appVersionInfo = appVersionInfo,
                                                onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                                                onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                                                onStartYtmusicOAuth = onStartYtmusicOAuth,
                                            )
                                            AppRoute.Search -> SearchScreen(
                                                controller = controller,
                                                onOpenRecognition = controller::openRecognition,
                                            )
                                            AppRoute.AudioRecognition -> AudioRecognitionScreen(
                                                controller = controller,
                                                hasMicrophonePermission = hasMicrophonePermission,
                                                onRequestMicrophonePermission = onRequestMicrophonePermission,
                                            )
                                            AppRoute.Feature -> ProviderFeatureScreen(controller, currentFeature ?: lastFeature)
                                            AppRoute.Track -> ProviderTrackScreen(controller, currentTrack ?: lastTrack)
                                            AppRoute.Video -> ProviderVideoScreen(controller, currentVideo ?: lastVideo)
                                            AppRoute.Playlist -> ProviderPlaylistScreen(controller, currentPlaylist ?: lastPlaylist)
                                            AppRoute.LocalPlaylist -> LocalPlaylistScreen(
                                                controller,
                                                currentLocalPlaylist ?: lastLocalPlaylist,
                                            )
                                            AppRoute.LocalMusicCollection -> LocalMusicCollectionScreen(controller)
                                            AppRoute.MediaItem -> ProviderMediaItemScreen(controller, currentMediaItem ?: lastMediaItem)
                                        }
                                    }
                                },
                            )
                            AnimatedVisibility(
                                visible = controller.isFullPlayerOpen,
                                modifier = Modifier.fillMaxSize(),
                                enter = slideInVertically(animationSpec = tween(FuoMotion.overlayEnterMillis)) { it / 2 } +
                                    fadeIn(tween(FuoMotion.overlayFadeMillis)),
                                exit = slideOutVertically(animationSpec = tween(FuoMotion.overlayExitMillis)) { it / 2 } +
                                    fadeOut(tween(FuoMotion.overlayFadeMillis)),
                            ) {
                                FullPlayer(controller)
                            }
                            controller.localMetadataEditorTrack?.let { track ->
                                LocalMetadataDialog(controller = controller, track = track)
                            }
                            controller.playlistTargetTrack?.let { track ->
                                PlaylistTargetDialog(controller = controller, track = track)
                            }
                            controller.artistTargetTrack?.let { track ->
                                TrackArtistTargetDialog(controller = controller, track = track)
                            }
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackArtistTargetDialog(controller: FuoPlayerController, track: MusicTrack) {
    AlertDialog(
        onDismissRequest = controller::closeArtistTargetPicker,
        title = { Text("查看歌手") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = track.title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                controller.artistTargets.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .fuoInteractive()
                            .clickable(role = Role.Button) { controller.openArtistTarget(target) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null)
                        Text(
                            text = target.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = controller::closeArtistTargetPicker) {
                Text("取消")
            }
        },
    )
}

@Composable
fun PlaylistTargetDialog(controller: FuoPlayerController, track: MusicTrack) {
    val canAddProvider = controller.canAddTrackToProviderPlaylist(track)
    val canAddLocal = controller.canAddTrackToLocalPlaylist(track)
    AlertDialog(
        onDismissRequest = controller::closePlaylistTargetPicker,
        title = {
            Text(
                text = "添加到歌单",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = track.title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (controller.playlistTargetPickerShowSwitcher && canAddProvider && canAddLocal) {
                    val targetTypes = listOf(
                        PlaylistTargetType.Provider to controller.playlistProviderName(track),
                        PlaylistTargetType.Local to "本地歌单",
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        targetTypes.forEachIndexed { index, (type, label) ->
                            SegmentedButton(
                                selected = controller.playlistTargetType == type,
                                onClick = { controller.selectPlaylistTargetType(type) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = targetTypes.size,
                                ),
                            ) {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                when (controller.playlistTargetType) {
                    PlaylistTargetType.Provider -> {
                        if (controller.isLoading && controller.playlistOperationTargets.isEmpty()) {
                            Text(
                                text = controller.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        controller.playlistOperationError?.let { ProviderContentMessage(it) }
                        controller.playlistOperationTargets.forEach { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .fuoInteractive()
                                    .clickable(role = Role.Button) {
                                        controller.addTrackToProviderPlaylist(playlist)
                                    }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverBox(
                                    track = playlist.toDisplayTrack(),
                                    modifier = Modifier.size(48.dp),
                                    placeholder = CoverPlaceholder.Playlist,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title.ifBlank { "未命名歌单" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = playlist.providerName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    PlaylistTargetType.Local -> {
                        if (controller.localPlaylists.isEmpty()) {
                            ProviderContentMessage("请先在“我的 → 歌单 → 本地”中新建歌单")
                        } else {
                            controller.localPlaylistOperationError?.let { ProviderContentMessage(it) }
                            controller.localPlaylists.forEach { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .fuoInteractive()
                                        .clickable(role = Role.Button) {
                                            controller.addTrackToLocalPlaylist(playlist)
                                        }
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CoverBox(
                                        track = playlist.toDisplayTrack(),
                                        modifier = Modifier.size(48.dp),
                                        placeholder = CoverPlaceholder.Playlist,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.title.ifBlank { "未命名歌单" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${playlist.tracks.size} 首",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = controller::closePlaylistTargetPicker) {
                Text("取消")
            }
        },
    )
}
