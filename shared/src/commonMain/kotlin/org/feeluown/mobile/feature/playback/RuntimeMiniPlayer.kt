package org.feeluown.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

private val MiniPlayerPreviousControlBreakpoint = 420.dp

/**
 * Controller-free MiniPlayer implementation backed by the app-scoped playback session.
 *
 * Full-player visibility and cover-transition direction are still app-shell presentation details;
 * C2 will move those alongside the remaining FullPlayer/queue/lyrics UI state.
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun RuntimeMiniPlayer(
    playbackSession: PlaybackSession,
    isFullPlayerOpen: Boolean,
    transitionDirection: TrackChangeDirection,
    onOpenFullPlayer: () -> Unit,
) {
    val state by playbackSession.state.collectAsStateWithLifecycle()
    val isLoadingAudio = state.status == PlaybackSessionStatus.Loading
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    val openPlayerInteractionSource = remember { MutableInteractionSource() }
    val contentSizeSpec = FuoMotion.defaultSpatialSpec<IntSize>()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isWideLayout) 0.dp else 8.dp,
                vertical = if (isWideLayout) 0.dp else 4.dp,
            )
            .animateContentSize(animationSpec = contentSizeSpec)
            .fuoInteractive()
            .fuoPressFeedback(
                interactionSource = openPlayerInteractionSource,
                pressedScale = FuoMotion.prominentPressedScale,
            )
            .clickable(
                interactionSource = openPlayerInteractionSource,
                indication = null,
                role = Role.Button,
                onClick = onOpenFullPlayer,
            ),
        shape = if (isWideLayout) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        color = if (isWideLayout) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (isWideLayout) 3.dp else 5.dp,
    ) {
        Column {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val showPrevious = shouldShowMiniPlayerPreviousControl(maxWidth, isWideLayout)
                Row(
                    modifier = Modifier.padding(
                        horizontal = if (isWideLayout) 12.dp else 14.dp,
                        vertical = if (isWideLayout) 8.dp else 8.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (isWideLayout) 12.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.currentTrack?.let { track ->
                        RuntimeMiniPlayerCover(
                            track = track,
                            heroVisible = !isFullPlayerOpen,
                            transitionDirection = transitionDirection,
                            isLoading = isLoadingAudio,
                            cornerRadius = if (isWideLayout) 10.dp else 18.dp,
                            modifier = Modifier.size(if (isWideLayout) 48.dp else 64.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = state.currentTrack?.title ?: "未播放",
                            style = if (isWideLayout) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isLoadingAudio) {
                                "正在加载音频"
                            } else {
                                state.currentTrack?.let(::runtimeArtistAlbumLabel) ?: "选择一首音乐开始播放"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLoadingAudio) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RuntimeMiniPlayerLyricLine(state)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showPrevious) {
                            RoundControlButton(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "上一首",
                                onClick = playbackSession::previous,
                                size = 48.dp,
                                iconSize = 24.dp,
                            )
                        }
                        PlayPauseButton(
                            isPlaying = state.status == PlaybackSessionStatus.Playing,
                            isLoading = state.status == PlaybackSessionStatus.Loading,
                            onClick = playbackSession::toggle,
                            size = if (isWideLayout) 48.dp else 56.dp,
                            iconSize = if (isWideLayout) 26.dp else 30.dp,
                            prominent = !isWideLayout,
                        )
                        RoundControlButton(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "下一首",
                            onClick = playbackSession::next,
                            size = 48.dp,
                            iconSize = 24.dp,
                        )
                    }
                }
            }
            RuntimeMiniPlayerProgress(
                state = state,
                isLoadingAudio = isLoadingAudio,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isWideLayout) 12.dp else 16.dp)
                    .padding(bottom = if (isWideLayout) 6.dp else 8.dp),
            )
        }
    }
}

internal fun shouldShowMiniPlayerPreviousControl(maxWidth: Dp, isWideLayout: Boolean): Boolean =
    isWideLayout || maxWidth >= MiniPlayerPreviousControlBreakpoint

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RuntimeMiniPlayerProgress(
    state: PlaybackSessionState,
    isLoadingAudio: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isLoadingAudio) {
        LinearProgressIndicator(
            modifier = modifier.height(4.dp),
        )
        return
    }
    val duration = state.durationMs.takeIf { it > 0 } ?: return
    val targetProgress = state.positionMs.coerceIn(0, duration).toFloat() / duration
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = FuoMotion.fastEffectsSpec(),
        label = "mini player progress",
    )
    LinearWavyProgressIndicator(
        progress = { progress },
        modifier = modifier.height(5.dp),
        amplitude = { value ->
            if (state.status == PlaybackSessionStatus.Playing) {
                WavyProgressIndicatorDefaults.indicatorAmplitude(value)
            } else {
                0f
            }
        },
    )
}

@Composable
private fun RuntimeMiniPlayerLyricLine(state: PlaybackSessionState) {
    val lines = remember(state.lyrics) { parseLyrics(state.lyrics) }
    val currentIndex = currentLyricIndex(lines, state.lyricsPositionMs)
    val currentLine = lines.getOrNull(currentIndex)?.text?.takeIf { it.isNotBlank() } ?: return
    val lyricSpatialSpec = FuoMotion.defaultSpatialSpec<IntOffset>()
    val lyricEffectsSpec = FuoMotion.fastEffectsSpec<Float>()

    AnimatedContent(
        targetState = RuntimeMiniPlayerLyricState(currentIndex, currentLine),
        transitionSpec = {
            val direction = if (targetState.index >= initialState.index) 1 else -1
            val enterOffset: (Int) -> Int = { height -> if (direction > 0) height else -height }
            val exitOffset: (Int) -> Int = { height -> if (direction > 0) -height else height }
            (slideInVertically(
                animationSpec = lyricSpatialSpec,
                initialOffsetY = enterOffset,
            ) + fadeIn(animationSpec = lyricEffectsSpec)) togetherWith
                (slideOutVertically(
                    animationSpec = lyricSpatialSpec,
                    targetOffsetY = exitOffset,
                ) + fadeOut(animationSpec = lyricEffectsSpec))
        },
        modifier = Modifier.fillMaxWidth(),
        label = "mini player lyric line",
    ) { lyric ->
        Text(
            text = lyric.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class RuntimeMiniPlayerLyricState(
    val index: Int,
    val text: String,
)

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun RuntimeMiniPlayerCover(
    track: TrackRef,
    heroVisible: Boolean,
    transitionDirection: TrackChangeDirection,
    isLoading: Boolean,
    cornerRadius: Dp,
    modifier: Modifier,
) {
    val targetCoverImage = rememberPlatformCoverImage(track.coverUrl)
    val hasCoverUrl = !track.coverUrl.isNullOrBlank()
    var displayedTrack by remember { mutableStateOf(track) }
    LaunchedEffect(track.id, track.coverUrl, isLoading, targetCoverImage) {
        if (!isLoading || (hasCoverUrl && targetCoverImage != null)) {
            displayedTrack = track
        }
    }
    val sharedTransitionScope = LocalAppSharedTransitionScope.current
    val sharedModifier = if (sharedTransitionScope == null) {
        modifier
    } else {
        with(sharedTransitionScope) {
            modifier.sharedElementWithCallerManagedVisibility(
                sharedContentState = rememberSharedContentState("player-cover:${track.id}"),
                visible = heroVisible,
            )
        }
    }
    val coverSpatialSpec = FuoMotion.slowSpatialSpec<IntOffset>()
    val coverEffectsSpec = FuoMotion.defaultEffectsSpec<Float>()
    Box(modifier = sharedModifier) {
        AnimatedContent(
            targetState = displayedTrack,
            transitionSpec = {
                runtimeMiniPlayerCoverTransition(
                    direction = transitionDirection,
                    spatialSpec = coverSpatialSpec,
                    effectsSpec = coverEffectsSpec,
                )
            },
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.coverUrl },
            label = "player cover transition",
        ) { animatedTrack ->
            PlatformCoverArt(
                title = animatedTrack.title,
                imageUrl = animatedTrack.coverUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cornerRadius)),
                placeholder = CoverPlaceholder.Song,
            )
        }
    }
}

private fun runtimeMiniPlayerCoverTransition(
    direction: TrackChangeDirection,
    spatialSpec: FiniteAnimationSpec<IntOffset>,
    effectsSpec: FiniteAnimationSpec<Float>,
): ContentTransform {
    val directionMultiplier = if (direction == TrackChangeDirection.Next) 1 else -1
    return (
        slideInHorizontally(
            initialOffsetX = { width -> width * directionMultiplier },
            animationSpec = spatialSpec,
        ) + fadeIn(animationSpec = effectsSpec)
        ) togetherWith (
        slideOutHorizontally(
            targetOffsetX = { width -> -width * directionMultiplier },
            animationSpec = spatialSpec,
        ) + fadeOut(animationSpec = effectsSpec)
        )
}

private fun runtimeArtistAlbumLabel(track: TrackRef): String =
    listOf(track.artists, track.album)
        .filter(String::isNotBlank)
        .joinToString(" · ")
