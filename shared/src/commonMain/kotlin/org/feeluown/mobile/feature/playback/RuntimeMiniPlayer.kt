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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220))
            .fuoInteractive()
            .clickable(role = Role.Button, onClick = onOpenFullPlayer),
        shape = if (isWideLayout) MaterialTheme.shapes.medium else MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = if (isWideLayout) 8.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.currentTrack?.let { track ->
                    RuntimeMiniPlayerCover(
                        track = track,
                        heroEnabled = !isFullPlayerOpen,
                        transitionDirection = transitionDirection,
                        isLoading = isLoadingAudio,
                        cornerRadius = if (isWideLayout) 10.dp else 12.dp,
                        modifier = Modifier.size(if (isWideLayout) 44.dp else 56.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.currentTrack?.title ?: "未播放",
                        style = MaterialTheme.typography.titleSmall,
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControlButton(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                        onClick = playbackSession::previous,
                        size = 48.dp,
                        iconSize = 24.dp,
                    )
                    PlayPauseButton(
                        isPlaying = state.status == PlaybackSessionStatus.Playing,
                        isLoading = state.status == PlaybackSessionStatus.Loading,
                        onClick = playbackSession::toggle,
                        size = 48.dp,
                        iconSize = 26.dp,
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
            RuntimeMiniPlayerProgress(state, isLoadingAudio)
        }
    }
}

@Composable
private fun RuntimeMiniPlayerProgress(
    state: PlaybackSessionState,
    isLoadingAudio: Boolean,
) {
    val duration = state.durationMs.takeIf { it > 0 }
    if (isLoadingAudio || duration != null) {
        val targetProgress = duration?.let {
            state.positionMs.coerceIn(0, it).toFloat() / it
        } ?: 0f
        val progress by animateFloatAsState(
            targetValue = targetProgress,
            animationSpec = tween(FuoMotion.progressAnimationMillis),
            label = "mini player progress",
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}

@Composable
private fun RuntimeMiniPlayerLyricLine(state: PlaybackSessionState) {
    val lines = remember(state.lyrics) { parseLyrics(state.lyrics) }
    val currentIndex = currentLyricIndex(lines, state.lyricsPositionMs)
    val currentLine = lines.getOrNull(currentIndex)?.text?.takeIf { it.isNotBlank() } ?: return

    AnimatedContent(
        targetState = RuntimeMiniPlayerLyricState(currentIndex, currentLine),
        transitionSpec = {
            val direction = if (targetState.index >= initialState.index) 1 else -1
            val enterOffset: (Int) -> Int = { height -> if (direction > 0) height else -height }
            val exitOffset: (Int) -> Int = { height -> if (direction > 0) -height else height }
            (slideInVertically(
                animationSpec = tween(180),
                initialOffsetY = enterOffset,
            ) + fadeIn(animationSpec = tween(180))) togetherWith
                (slideOutVertically(
                    animationSpec = tween(180),
                    targetOffsetY = exitOffset,
                ) + fadeOut(animationSpec = tween(180)))
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
    heroEnabled: Boolean,
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
            transitionSpec = { runtimeMiniPlayerCoverTransition(transitionDirection) },
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

private fun runtimeMiniPlayerCoverTransition(direction: TrackChangeDirection): ContentTransform {
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

private fun runtimeArtistAlbumLabel(track: TrackRef): String =
    listOf(track.artists, track.album)
        .filter(String::isNotBlank)
        .joinToString(" · ")
