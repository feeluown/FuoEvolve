package org.feeluown.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun PlayerSharedCover(
    track: MusicTrack,
    heroEnabled: Boolean,
    transitionDirection: TrackChangeDirection = TrackChangeDirection.Next,
    isLoading: Boolean = false,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    val targetCoverImage = rememberPlatformCoverImage(track.coverUrl)
    val hasCoverUrl = !track.coverUrl.isNullOrBlank()
    var displayedTrack by remember { mutableStateOf(track) }
    LaunchedEffect(track.id, track.coverUrl, isLoading, targetCoverImage) {
        if (!isLoading || (hasCoverUrl && targetCoverImage != null)) {
            displayedTrack = track
        }
    }
    val sharedTransitionScope = LocalPlayerSharedTransitionScope.current
    val sharedModifier = if (!heroEnabled || sharedTransitionScope == null) {
        modifier
    } else {
        with(sharedTransitionScope) {
            modifier.sharedElementWithCallerManagedVisibility(
                sharedContentState = rememberSharedContentState("player-cover:${track.id}"),
                visible = true,
            )
        }
    }
    Box(modifier = sharedModifier) {
        AnimatedContent(
            targetState = displayedTrack,
            transitionSpec = { playerCoverTransition(transitionDirection) },
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.coverUrl },
            label = "player cover transition",
        ) { animatedTrack ->
            CoverBox(
                track = animatedTrack,
                cornerRadius = cornerRadius,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun playerCoverTransition(direction: TrackChangeDirection): ContentTransform {
    val directionMultiplier = if (direction == TrackChangeDirection.Next) 1 else -1
    return (
        slideInHorizontally(
            initialOffsetX = { width -> width * directionMultiplier },
            animationSpec = tween(FuoMotion.coverTransitionMillis),
        ) + fadeIn(animationSpec = tween(FuoMotion.coverFadeMillis))
        ) togetherWith (
        slideOutHorizontally(
            targetOffsetX = { width -> -width * directionMultiplier },
            animationSpec = tween(FuoMotion.coverTransitionMillis),
        ) + fadeOut(animationSpec = tween(FuoMotion.coverFadeMillis))
        )
}

enum class PlayerVisualTab(val title: String) {
    Cover("封面"),
    Lyrics("歌词"),
}

@Composable
fun QueueRepeatModeHeader(
    isFmQueue: Boolean,
    repeatMode: RepeatMode,
    onRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "播放模式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isFmQueue) {
                FmModeBadge()
            } else {
                RepeatModeTextButton(
                    repeatMode = repeatMode,
                    onRepeat = onRepeat,
                )
            }
        }
    }
}

@Composable
fun FmModeBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = "FM",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
fun RepeatModeTextButton(
    repeatMode: RepeatMode,
    onRepeat: () -> Unit,
) {
    val repeatIcon = when (repeatMode) {
        RepeatMode.OFF -> Icons.Filled.Repeat
        RepeatMode.QUEUE -> Icons.Filled.Repeat
        RepeatMode.SINGLE -> Icons.Filled.RepeatOne
    }
    FilterChip(
        selected = repeatMode != RepeatMode.OFF,
        onClick = onRepeat,
        label = { Text(repeatMode.label, maxLines = 1) },
        leadingIcon = {
            Icon(
                imageVector = repeatIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

fun emptyDisplayTrack() = MusicTrack(
    id = "empty",
    title = "FeelUOwn",
    artists = "",
    album = "",
    source = "",
    sourceType = TrackSourceType.LocalMediaStore,
)

@Composable
fun PlayerControls(
    state: PlaybackState,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    dense: Boolean = false,
    shuffleEnabled: Boolean = false,
    repeatMode: RepeatMode = RepeatMode.QUEUE,
    shuffleAvailable: Boolean = true,
    onShuffle: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null,
    sleepTimerAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.animateContentSize(animationSpec = tween(220)),
        horizontalArrangement = if (compact) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact && onShuffle != null) {
            RoundControlButton(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = if (shuffleAvailable) "随机播放" else "私人 FM 使用顺序播放",
                onClick = onShuffle,
                size = 48.dp,
                iconSize = 24.dp,
                selected = shuffleEnabled,
                enabled = shuffleAvailable,
            )
        }
        RoundControlButton(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = "上一首",
            onClick = onPrevious,
            size = 48.dp,
            iconSize = if (compact) 24.dp else 26.dp,
        )
        PlayPauseButton(
            isPlaying = state.status == PlayerStatus.Playing,
            isLoading = state.status == PlayerStatus.Loading,
            onClick = onToggle,
            size = when {
                compact -> 48.dp
                dense -> 56.dp
                else -> 64.dp
            },
            iconSize = when {
                compact -> 26.dp
                dense -> 30.dp
                else -> 34.dp
            },
            prominent = !compact,
        )
        RoundControlButton(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = "下一首",
            onClick = onNext,
            size = 48.dp,
            iconSize = if (compact) 24.dp else 26.dp,
        )
        if (!compact) {
            sleepTimerAction?.invoke()
            if (onRepeat != null) {
                RepeatModeTextButton(
                    repeatMode = repeatMode,
                    onRepeat = onRepeat,
                )
            }
        }
    }
}

@Composable
fun RoundControlButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
    prominent: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val buttonSize = if (size < 48.dp) 48.dp else size
    val buttonModifier = Modifier.size(buttonSize)
    if (prominent || selected) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
    prominent: Boolean = false,
) {
    if (isLoading) {
        Surface(
            modifier = Modifier.size(if (size < 48.dp) 48.dp else size),
            color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            tonalElevation = if (prominent) 3.dp else 1.dp,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                    color = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                )
            }
        }
        return
    }
    RoundControlButton(
        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        contentDescription = if (isPlaying) "暂停" else "播放",
        onClick = onClick,
        size = size,
        iconSize = iconSize,
        prominent = prominent,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayingProgressIndicator(
    progress: () -> Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    LinearWavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        amplitude = { value ->
            if (isPlaying) {
                WavyProgressIndicatorDefaults.indicatorAmplitude(value)
            } else {
                0f
            }
        },
    )
}

@Composable
fun ProgressBlock(state: PlaybackState, onSeek: (Long) -> Unit) {
    val duration = state.durationMs.takeIf { it > 0 } ?: 1L
    val canSeek = state.currentTrack != null &&
        state.durationMs > 0 &&
        state.status != PlayerStatus.Idle &&
        state.status != PlayerStatus.Loading &&
        state.status != PlayerStatus.Error
    var isSeeking by remember(state.currentTrack?.id) { mutableStateOf(false) }
    var seekPosition by remember(state.currentTrack?.id) {
        mutableStateOf(state.positionMs.coerceIn(0, duration).toFloat())
    }
    val animatedSeekPosition by animateFloatAsState(
        targetValue = seekPosition.coerceIn(0f, duration.toFloat()),
        animationSpec = tween(FuoMotion.progressAnimationMillis),
        label = "player progress",
    )
    val displayedSeekPosition = if (isSeeking) seekPosition else animatedSeekPosition

    LaunchedEffect(state.positionMs, duration, isSeeking, canSeek) {
        if (!isSeeking || !canSeek) {
            seekPosition = state.positionMs.coerceIn(0, duration).toFloat()
            if (!canSeek) isSeeking = false
        }
    }

    Slider(
        value = displayedSeekPosition.coerceIn(0f, duration.toFloat()),
        onValueChange = {
            isSeeking = true
            seekPosition = it
        },
        onValueChangeFinished = {
            if (isSeeking && canSeek) {
                onSeek(seekPosition.toLong().coerceIn(0, duration))
                isSeeking = false
            }
        },
        enabled = canSeek,
        valueRange = 0f..duration.toFloat(),
        track = { sliderState ->
            PlayingProgressIndicator(
                progress = { sliderState.value / duration.toFloat() },
                isPlaying = canSeek && state.status == PlayerStatus.Playing,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            formatMs(if (isSeeking) seekPosition.toLong() else state.positionMs),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PlaybackPartList(
    parts: List<PlaybackPart>,
    currentPartIndex: Int,
    onPartClick: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "分 P 列表",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        parts.forEachIndexed { index, part ->
            val selected = index == currentPartIndex
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fuoInteractive()
                    .clickable(role = Role.Button) { onPartClick(index) },
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "P${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = part.title.ifBlank { "未命名分段" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    part.durationMs?.takeIf { it > 0 }?.let {
                        Text(
                            text = formatMs(it),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
