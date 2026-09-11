package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class RecognitionFeatureActions(
    val dispatch: (RecognitionAction) -> Unit,
    val onBack: () -> Unit,
    val onSearchSong: (RecognizedSong) -> Unit,
    val canOpenNeteaseDetail: (RecognizedSong) -> Boolean,
    val onOpenNeteaseDetail: (RecognizedSong) -> Unit,
)

/** Recognition UI depends only on feature state/actions and narrow cross-feature callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRecognitionFeatureScreen(
    uiState: RecognitionUiState,
    actions: RecognitionFeatureActions,
    audioRecognitionAccess: AudioRecognitionAccess,
) {
    DisposableEffect(Unit) {
        onDispose { actions.dispatch(RecognitionAction.CancelIfInProgress) }
    }
    LaunchedEffect(audioRecognitionAccess.isAvailable, uiState) {
        if (audioRecognitionAccess.isAvailable && uiState == RecognitionUiState.Idle) {
            actions.dispatch(RecognitionAction.Start)
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("听歌识曲") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            if (uiState is RecognitionUiState.Capturing || uiState is RecognitionUiState.Matching) {
                Surface(tonalElevation = 3.dp) {
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        onClick = { actions.dispatch(RecognitionAction.Cancel) },
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("停止识别")
                    }
                }
            }
        },
    ) { paddingValues ->
        if (audioRecognitionAccess.isAvailable) {
            RecognitionContent(
                uiState = uiState,
                actions = actions,
                source = audioRecognitionAccess.source,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        } else {
            AudioRecognitionAccessContent(
                source = audioRecognitionAccess.source,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                onRequestPermission = audioRecognitionAccess.requestAccess,
            )
        }
    }
}

@Composable
private fun AudioRecognitionAccessContent(
    source: AudioRecognitionSource,
    modifier: Modifier,
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RecognitionIcon()
        Spacer(Modifier.size(24.dp))
        Text(
            when (source) {
                AudioRecognitionSource.Microphone -> "需要麦克风权限"
                AudioRecognitionSource.SystemOutput -> "需要系统音频权限"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = when (source) {
                AudioRecognitionSource.Microphone -> "录音仅在内存中用于生成音频指纹，不会保存或上传原始音频。"
                AudioRecognitionSource.SystemOutput -> "仅采集当前系统播放的声音用于生成音频指纹，不会保存或上传原始音频。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onRequestPermission) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("授权并开始识别")
        }
    }
}

@Composable
private fun RecognitionContent(
    uiState: RecognitionUiState,
    actions: RecognitionFeatureActions,
    source: AudioRecognitionSource,
    modifier: Modifier,
) {
    when (val state = uiState) {
        RecognitionUiState.Idle -> ListeningContent(
            modifier = modifier,
            title = when (source) {
                AudioRecognitionSource.Microphone -> "正在准备麦克风"
                AudioRecognitionSource.SystemOutput -> "正在准备系统音频"
            },
            subtitle = when (source) {
                AudioRecognitionSource.Microphone -> "录音不会保存到设备"
                AudioRecognitionSource.SystemOutput -> "不会暂停当前播放"
            },
            progress = null,
        )
        is RecognitionUiState.Capturing -> ListeningContent(
            modifier = modifier,
            title = when (source) {
                AudioRecognitionSource.Microphone -> "正在聆听"
                AudioRecognitionSource.SystemOutput -> "正在聆听系统音频"
            },
            subtitle = when (source) {
                AudioRecognitionSource.Microphone -> "请靠近声音来源，并保持周围环境安静"
                AudioRecognitionSource.SystemOutput -> "请保持音乐继续通过系统默认输出播放"
            },
            progress = (state.capturedMs.toFloat() / state.windowDurationMs).coerceIn(0f, 1f),
        )
        RecognitionUiState.Matching -> ListeningContent(
            modifier = modifier,
            title = "正在寻找这首歌",
            subtitle = "马上就好，请继续让音乐播放",
            progress = null,
        )
        is RecognitionUiState.Success -> RecognitionResults(
            actions = actions,
            songs = state.songs,
            modifier = modifier,
        )
        RecognitionUiState.NoResult -> RecognitionMessage(
            modifier = modifier,
            title = "暂未识别到歌曲",
            message = when (source) {
                AudioRecognitionSource.Microphone -> "可以让手机更靠近声音来源，或换到安静一点的环境再试一次。"
                AudioRecognitionSource.SystemOutput -> "请确认音乐正在通过系统默认输出播放，再重试。"
            },
            actionLabel = "重新识别",
            onAction = { actions.dispatch(RecognitionAction.Retry) },
        )
        is RecognitionUiState.Error -> RecognitionMessage(
            modifier = modifier,
            title = "识别失败",
            message = state.message,
            actionLabel = "重试",
            onAction = { actions.dispatch(RecognitionAction.Retry) },
        )
        RecognitionUiState.Cancelled -> RecognitionMessage(
            modifier = modifier,
            title = "已停止识别",
            message = "准备好后，可以再次开始识别。",
            actionLabel = "重新识别",
            onAction = { actions.dispatch(RecognitionAction.Retry) },
        )
    }
}

@Composable
private fun ListeningContent(
    modifier: Modifier,
    title: String,
    subtitle: String,
    progress: Float?,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RecognitionIcon()
        Spacer(Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(24.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CircularProgressIndicator()
        }
        Spacer(Modifier.size(16.dp))
        Text(
            "只会向识别接口发送音频指纹",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecognitionResults(
    actions: RecognitionFeatureActions,
    songs: List<RecognizedSong>,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "识别到 ${songs.size} 首歌曲",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(songs, key = { it.neteaseSongId ?: "${it.title}:${it.artists}" }) { song ->
            RecognizedSongCard(actions, song)
        }
        item { Spacer(Modifier.size(16.dp)) }
    }
}

@Composable
private fun RecognizedSongCard(
    actions: RecognitionFeatureActions,
    song: RecognizedSong,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverBox(
                    track = MusicTrack(
                        id = song.neteaseSongId.orEmpty(),
                        title = song.title,
                        artists = song.artists.joinToString(" / "),
                        album = song.album,
                        source = "netease",
                        sourceType = TrackSourceType.Provider,
                        coverUrl = song.coverUrl,
                    ),
                    modifier = Modifier.size(64.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song.title.ifBlank { "未知歌曲" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artists.joinToString(" / ").ifBlank { "未知歌手" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (song.album.isNotBlank()) {
                        Text(
                            song.album,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onSearchSong(song) }) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("搜索")
                }
                if (actions.canOpenNeteaseDetail(song)) {
                    OutlinedButton(onClick = { actions.onOpenNeteaseDetail(song) }) {
                        Text("查看网易云详情")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecognitionMessage(
    modifier: Modifier,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RecognitionIcon()
        Spacer(Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onAction) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(actionLabel)
        }
    }
}

@Composable
private fun RecognitionIcon() {
    Surface(
        modifier = Modifier.size(112.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.padding(28.dp),
        )
    }
}
