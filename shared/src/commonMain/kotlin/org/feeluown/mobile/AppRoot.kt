package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(
    appViewModel: FuoAppViewModel,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onShareText: (String) -> Unit = {},
    appVersionInfo: String? = null,
) {
    val appUiState by appViewModel.uiState.collectAsStateWithLifecycle()
    val controller = appViewModel.controller
    FuoEvolveTheme(
        themeMode = appUiState.settings.settings.themeMode,
        themeColorScheme = appUiState.settings.settings.themeColorScheme,
    ) {
        if (!controller.isSettingsLoaded) {
            AppInitializationLoadingScreen()
            return@FuoEvolveTheme
        }
        if (!controller.onboardingCompleted) {
            OnboardingScreen(
                controller = controller,
                onOpenProviderWebLogin = onOpenProviderWebLogin,
                onLogoutProvider = onLogoutProvider,
                onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
            )
            return@FuoEvolveTheme
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val playlistOperationFeedback = controller.playlistOperationFeedback
        val downloadQueueFeedback = controller.downloadQueueFeedback
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layoutInfo = remember(maxWidth, maxHeight) {
                val isLandscape = maxWidth > maxHeight
                AppLayoutInfo(
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

            val currentFeature = controller.selectedFeature
            val currentTrack = controller.selectedTrack
            val currentVideo = controller.selectedVideo
            val currentPlaylist = controller.selectedPlaylist
            val currentMediaItem = controller.selectedMediaItem
            var lastFeature by remember { mutableStateOf<ProviderFeature?>(null) }
            var lastTrack by remember { mutableStateOf<MusicTrack?>(null) }
            var lastVideo by remember { mutableStateOf<ProviderVideo?>(null) }
            var lastPlaylist by remember { mutableStateOf<ProviderPlaylist?>(null) }
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
            LaunchedEffect(currentMediaItem) {
                if (currentMediaItem != null) {
                    lastMediaItem = currentMediaItem
                }
            }

            CompositionLocalProvider(
                LocalShareHandler provides { onShareText(it.text) },
                LocalAppLayoutInfo provides layoutInfo,
            ) {
                SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlayerSharedTransitionScope provides this) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NavDisplay(
                                backStack = appUiState.backStack,
                                modifier = Modifier.fillMaxSize(),
                                onBack = { appViewModel.dispatch(AppIntent.NavigateBack) },
                                entryProvider = { route ->
                                    NavEntry(route) {
                                        when (route) {
                                            AppRoute.Home -> HomeScreen(
                                                controller = controller,
                                                hasAudioPermission = hasAudioPermission,
                                                onRequestAudioPermission = onRequestAudioPermission,
                                            )
                                            AppRoute.DebugLogs -> DebugLogScreen(controller)
                                            AppRoute.DownloadManager -> DownloadManagerScreen(controller)
                                            AppRoute.Settings -> SettingsScreen(
                                                controller,
                                                onOpenProviderWebLogin,
                                                onLogoutProvider,
                                                onImportYtmusicHeaderFile,
                                                appVersionInfo,
                                            )
                                            AppRoute.Search -> SearchScreen(controller)
                                            AppRoute.Feature -> ProviderFeatureScreen(controller, currentFeature ?: lastFeature)
                                            AppRoute.Track -> ProviderTrackScreen(controller, currentTrack ?: lastTrack)
                                            AppRoute.Video -> ProviderVideoScreen(controller, currentVideo ?: lastVideo)
                                            AppRoute.Playlist -> ProviderPlaylistScreen(controller, currentPlaylist ?: lastPlaylist)
                                            AppRoute.MediaItem -> ProviderMediaItemScreen(controller, currentMediaItem ?: lastMediaItem)
                                        }
                                    }
                                },
                            )
                            AnimatedVisibility(
                                visible = controller.isFullPlayerOpen,
                                modifier = Modifier.fillMaxSize(),
                                enter = slideInVertically(animationSpec = tween(260)) { it / 2 } + fadeIn(tween(180)),
                                exit = slideOutVertically(animationSpec = tween(220)) { it / 2 } + fadeOut(tween(160)),
                            ) {
                                FullPlayer(controller)
                            }
                            controller.localMetadataEditorTrack?.let { track ->
                                LocalMetadataDialog(controller = controller, track = track)
                            }
                            controller.playlistTargetTrack?.let { track ->
                                ProviderPlaylistTargetDialog(controller = controller, track = track)
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
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { controller.openArtistTarget(target) }
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
fun ProviderPlaylistTargetDialog(controller: FuoPlayerController, track: MusicTrack) {
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
                if (controller.isLoading && controller.playlistOperationTargets.isEmpty()) {
                    Text(
                        text = controller.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                controller.playlistOperationError?.let {
                    ProviderContentMessage(it)
                }
                controller.playlistOperationTargets.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { controller.addTrackToProviderPlaylist(playlist) }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverBox(
                            track = playlist.toDisplayTrack(),
                            modifier = Modifier.size(40.dp),
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = controller::closePlaylistTargetPicker) {
                Text("取消")
            }
        },
    )
}
