package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

@Composable
internal fun AppGlobalOverlays(uiGraph: AppUiGraph) {
    val playback = uiGraph.playback
    val layoutInfo = LocalAppLayoutInfo.current
    val overlaySpatialSpec = FuoMotion.defaultSpatialSpec<IntOffset>()
    val overlayEffectsSpec = FuoMotion.fastEffectsSpec<Float>()

    PlatformBackHandler(
        enabled = playback.isFullPlayerOpen && !playback.navigation.isQueueOpen,
        onBack = playback.navigation::closeFullPlayer,
    )

    AnimatedVisibility(
        visible = playback.isFullPlayerOpen,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(animationSpec = overlaySpatialSpec) { it / 2 } +
            fadeIn(animationSpec = overlayEffectsSpec),
        exit = slideOutVertically(animationSpec = overlaySpatialSpec) { it / 2 } +
            fadeOut(animationSpec = overlayEffectsSpec),
    ) {
        // Full player keeps a regular overlay transition. Its layout follows usable pane space
        // instead of raw device orientation, while the rest of the app keeps physical orientation.
        CompositionLocalProvider(
            LocalAppLayoutInfo provides layoutInfo.copy(isLandscape = layoutInfo.useFullPlayerTwoPane),
        ) {
            Box(Modifier.fillMaxSize()) {
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
