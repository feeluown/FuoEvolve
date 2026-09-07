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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AppGlobalOverlays(uiGraph: AppUiGraph) {
    val playback = uiGraph.playback
    val predictiveBackPreference = rememberPredictiveBackPreference()
    val overlaySpatialSpec = FuoMotion.defaultSpatialSpec<IntOffset>()
    val overlayEffectsSpec = FuoMotion.fastEffectsSpec<Float>()
    val predictiveReturnSpec = FuoMotion.fastSpatialSpec<Float>()
    var predictiveProgress by remember { mutableFloatStateOf(0f) }
    var predictiveGestureActive by remember { mutableStateOf(false) }
    var predictiveBackCommitted by remember { mutableStateOf(false) }
    val renderedPredictiveProgress by animateFloatAsState(
        targetValue = predictiveProgress,
        animationSpec = if (predictiveGestureActive) snap() else predictiveReturnSpec,
        label = "Full player predictive back progress",
    )

    LaunchedEffect(playback.isFullPlayerOpen) {
        if (!playback.isFullPlayerOpen) {
            predictiveProgress = 0f
            predictiveGestureActive = false
            predictiveBackCommitted = false
        }
    }

    PlatformPredictiveBackHandler(
        enabled = predictiveBackPreference.isSupported &&
            predictiveBackPreference.enabled &&
            playback.isFullPlayerOpen,
        onProgress = { progress ->
            predictiveGestureActive = true
            predictiveProgress = progress
        },
        onCancelled = {
            predictiveGestureActive = false
            predictiveProgress = 0f
        },
        onBack = {
            predictiveBackCommitted = true
            predictiveProgress = 1f
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
        // Full player keeps its normal overlay motion for explicit controls. During a predictive
        // system-back gesture it follows the finger, revealing the route underneath before commit.
        CompositionLocalProvider(LocalAppSharedTransitionScope provides null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1f - (0.04f * progress)
                        scaleX = scale
                        scaleY = scale
                        translationY = 32.dp.toPx() * progress
                        alpha = 1f - (0.08f * progress)
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
