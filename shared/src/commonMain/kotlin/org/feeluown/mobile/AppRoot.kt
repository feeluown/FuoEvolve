package org.feeluown.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

val LocalShareHandler = staticCompositionLocalOf<(SharePayload) -> Unit> { {} }
val LocalAppLayoutInfo = staticCompositionLocalOf { AppLayoutInfo() }

data class AppLayoutInfo(
    val isLandscape: Boolean = false,
    val useWideLayout: Boolean = false,
    val gridColumns: Int = 3,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onShareText: (String) -> Unit = {},
    appVersionInfo: String? = null,
) {
    FuoEvolveTheme(
        themeMode = controller.themeMode,
        themeColorScheme = controller.themeColorScheme,
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        val playlistOperationFeedback = controller.playlistOperationFeedback
        LaunchedEffect(playlistOperationFeedback) {
            playlistOperationFeedback ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(playlistOperationFeedback)
            controller.dismissPlaylistOperationFeedback(playlistOperationFeedback)
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

            val destination = appDestination(controller)
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
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = destination,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (slideInHorizontally(animationSpec = tween(220)) { it / 5 * direction } + fadeIn(tween(180)))
                                .togetherWith(
                                    slideOutHorizontally(animationSpec = tween(180)) { -it / 6 * direction } +
                                        fadeOut(tween(160)),
                                )
                                .using(SizeTransform(clip = false))
                        },
                    ) { target ->
                        when (target) {
                            AppDestination.Home -> HomeScreen(
                                controller = controller,
                                hasAudioPermission = hasAudioPermission,
                                onRequestAudioPermission = onRequestAudioPermission,
                            )
                            AppDestination.DebugLogs -> DebugLogScreen(controller)
                            AppDestination.Settings -> SettingsScreen(
                                controller,
                                onOpenProviderWebLogin,
                                onLogoutProvider,
                                onImportYtmusicHeaderFile,
                                appVersionInfo,
                            )
                            AppDestination.Search -> SearchScreen(controller)
                            AppDestination.Feature -> ProviderFeatureScreen(controller, currentFeature ?: lastFeature)
                            AppDestination.Track -> ProviderTrackScreen(controller, currentTrack ?: lastTrack)
                            AppDestination.Video -> ProviderVideoScreen(controller, currentVideo ?: lastVideo)
                            AppDestination.Playlist -> ProviderPlaylistScreen(controller, currentPlaylist ?: lastPlaylist)
                            AppDestination.MediaItem -> ProviderMediaItemScreen(controller, currentMediaItem ?: lastMediaItem)
                        }
                    }
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
private enum class AppDestination {
    Home,
    Feature,
    Track,
    Video,
    Playlist,
    Search,
    Settings,
    DebugLogs,
    MediaItem,
}

private fun appDestination(controller: FuoPlayerController): AppDestination {
    return when {
        controller.isDebugLogOpen -> AppDestination.DebugLogs
        controller.isSettingsOpen -> AppDestination.Settings
        controller.selectedVideo != null -> AppDestination.Video
        controller.selectedTrack != null -> AppDestination.Track
        controller.selectedMediaItem != null -> AppDestination.MediaItem
        controller.selectedPlaylist != null -> AppDestination.Playlist
        controller.selectedFeature != null -> AppDestination.Feature
        controller.isSearchOpen -> AppDestination.Search
        else -> AppDestination.Home
    }
}
