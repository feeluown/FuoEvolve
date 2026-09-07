package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AppGlobalOverlays(uiGraph: AppUiGraph) {
    val playback = uiGraph.playback
    val predictiveBackPreference = rememberPredictiveBackPreference()
    val density = LocalDensity.current
    val overlaySpatialSpec = FuoMotion.defaultSpatialSpec<IntOffset>()
    val overlayEffectsSpec = FuoMotion.fastEffectsSpec<Float>()
    val predictiveReturnSpec = FuoMotion.fastSpatialSpec<Float>()
    val maxVerticalGestureDistancePx = with(density) { 240.dp.toPx() }
    val maxVerticalFollowPx = with(density) { 18.dp.toPx() }
    val horizontalFollowPx = with(density) { 6.dp.toPx() }

    var predictiveProgress by remember { mutableFloatStateOf(0f) }
    var predictiveVerticalOffsetPx by remember { mutableFloatStateOf(0f) }
    var predictiveHorizontalOffsetPx by remember { mutableFloatStateOf(0f) }
    var predictiveGestureStartTouchY by remember { mutableStateOf<Float?>(null) }
    var predictiveSwipeEdge by remember { mutableStateOf(PredictiveBackSwipeEdge.None) }
    var predictiveGestureActive by remember { mutableStateOf(false) }
    var predictiveBackCommitted by remember { mutableStateOf(false) }

    val renderedPredictiveProgress by animateFloatAsState(
        targetValue = predictiveProgress,
        animationSpec = if (predictiveGestureActive) snap() else predictiveReturnSpec,
        label = "Full player predictive back progress",
    )
    val renderedVerticalOffsetPx by animateFloatAsState(
        targetValue = predictiveVerticalOffsetPx,
        animationSpec = if (predictiveGestureActive) snap() else predictiveReturnSpec,
        label = "Full player predictive back vertical follow",
    )
    val renderedHorizontalOffsetPx by animateFloatAsState(
        targetValue = predictiveHorizontalOffsetPx,
        animationSpec = if (predictiveGestureActive) snap() else predictiveReturnSpec,
        label = "Full player predictive back horizontal follow",
    )

    fun resetPredictiveGestureTargets() {
        predictiveGestureActive = false
        predictiveProgress = 0f
        predictiveVerticalOffsetPx = 0f
        predictiveHorizontalOffsetPx = 0f
        predictiveGestureStartTouchY = null
    }

    LaunchedEffect(playback.isFullPlayerOpen) {
        if (!playback.isFullPlayerOpen) {
            resetPredictiveGestureTargets()
            predictiveSwipeEdge = PredictiveBackSwipeEdge.None
            predictiveBackCommitted = false
        }
    }

    PlatformPredictiveBackHandler(
        enabled = predictiveBackPreference.isSupported &&
            predictiveBackPreference.enabled &&
            playback.isFullPlayerOpen &&
            !playback.navigation.isQueueOpen,
        onProgress = { event ->
            if (!predictiveGestureActive && event.progress > 0f) {
                predictiveGestureActive = true
                predictiveGestureStartTouchY = event.touchY
            }
            predictiveProgress = event.progress
            predictiveSwipeEdge = event.swipeEdge

            val startTouchY = predictiveGestureStartTouchY ?: event.touchY
            val rawDeltaY = event.touchY - startTouchY
            val normalizedDelta = (abs(rawDeltaY) / maxVerticalGestureDistancePx).coerceIn(0f, 1f)
            val easedDelta = normalizedDelta * (2f - normalizedDelta)
            val direction = when {
                rawDeltaY > 0f -> 1f
                rawDeltaY < 0f -> -1f
                else -> 0f
            }
            predictiveVerticalOffsetPx = direction * maxVerticalFollowPx * easedDelta * event.progress
            predictiveHorizontalOffsetPx = when (event.swipeEdge) {
                PredictiveBackSwipeEdge.Left -> horizontalFollowPx * event.progress
                PredictiveBackSwipeEdge.Right -> -horizontalFollowPx * event.progress
                PredictiveBackSwipeEdge.None -> 0f
            }
        },
        onCancelled = ::resetPredictiveGestureTargets,
        onBack = {
            if (predictiveGestureActive) {
                predictiveBackCommitted = true
                predictiveProgress = 1f
            }
            predictiveGestureActive = false
            playback.navigation.closeFullPlayer()
        },
    )

    AnimatedVisibility(
        visible = playback.isFullPlayerOpen,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(animationSpec = overlaySpatialSpec) { it / 2 } +
            fadeIn(animationSpec = overlayEffectsSpec),
        exit = if (predictiveBackCommitted) {
            ExitTransition.None
        } else {
            slideOutVertically(animationSpec = overlaySpatialSpec) { it / 2 } +
                fadeOut(animationSpec = overlayEffectsSpec)
        },
    ) {
        val progress = renderedPredictiveProgress.coerceIn(0f, 1f)
        val cornerProgress = ((progress - 0.06f) / 0.94f).coerceIn(0f, 1f)
        val predictiveShape = RoundedCornerShape(28.dp * cornerProgress)
        val transformOrigin = when (predictiveSwipeEdge) {
            PredictiveBackSwipeEdge.Left -> TransformOrigin(0.18f, 0.5f)
            PredictiveBackSwipeEdge.Right -> TransformOrigin(0.82f, 0.5f)
            PredictiveBackSwipeEdge.None -> TransformOrigin.Center
        }

        // The predictive surface behaves like a restrained window: it recedes slightly, gains
        // rounded corners as it detaches from fullscreen, and follows vertical finger movement only
        // within a small bounded range rather than tracking the pointer 1:1.
        CompositionLocalProvider(LocalAppSharedTransitionScope provides null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1f - (0.018f * progress)
                        scaleX = scale
                        scaleY = scale
                        translationX = renderedHorizontalOffsetPx
                        translationY = renderedVerticalOffsetPx
                        this.transformOrigin = transformOrigin
                        shape = predictiveShape
                        clip = cornerProgress > 0f
                    },
            ) {
                RuntimeFullPlayer()
            }
        }
    }
    LocalMetadataDialog()
    PlaylistTargetFeatureDialog(
        actions = playback.playlists,
        localPlaylist = uiGraph.localPlaylist,
    )
    TrackArtistTargetFeatureDialog(playback.providerTrackActions)
}
