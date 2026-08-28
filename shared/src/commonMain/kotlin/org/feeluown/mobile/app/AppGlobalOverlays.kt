package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AppGlobalOverlays(uiGraph: AppUiGraph) {
    val playback = uiGraph.playback
    val overlaySpatialSpec = FuoMotion.defaultSpatialSpec<IntOffset>()
    val overlayEffectsSpec = FuoMotion.fastEffectsSpec<Float>()
    AnimatedVisibility(
        visible = playback.isFullPlayerOpen,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(animationSpec = overlaySpatialSpec) { it / 2 } +
            fadeIn(animationSpec = overlayEffectsSpec),
        exit = slideOutVertically(animationSpec = overlaySpatialSpec) { it / 2 } +
            fadeOut(animationSpec = overlayEffectsSpec),
    ) {
        // Full player keeps its normal overlay motion; shared-cover Hero is intentionally disabled.
        CompositionLocalProvider(LocalAppSharedTransitionScope provides null) {
            RuntimeFullPlayer()
        }
    }
    LocalMetadataDialog()
    PlaylistTargetFeatureDialog(
        actions = playback.playlists,
        localPlaylist = uiGraph.localPlaylist,
    )
    TrackArtistTargetFeatureDialog(playback.providerTrackActions)
}