package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun AppGlobalOverlays(uiGraph: AppUiGraph) {
    val playback = uiGraph.playback
    AnimatedVisibility(
        visible = playback.isFullPlayerOpen,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(animationSpec = tween(FuoMotion.overlayEnterMillis)) { it / 2 } +
            fadeIn(tween(FuoMotion.overlayFadeMillis)),
        exit = slideOutVertically(animationSpec = tween(FuoMotion.overlayExitMillis)) { it / 2 } +
            fadeOut(tween(FuoMotion.overlayFadeMillis)),
    ) {
        RuntimeFullPlayer()
    }
    LocalMetadataDialog()
    PlaylistTargetFeatureDialog(
        actions = playback.playlists,
        localPlaylist = uiGraph.localPlaylist,
    )
    TrackArtistTargetFeatureDialog(playback.providerTrackActions)
}
