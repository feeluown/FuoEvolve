package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Temporary Home integration bridge.
 *
 * Home remains a controller-owned surface until P2-4. Local Music itself is already
 * controller-free and consumes [LocalLocalMusicUiGraph] installed by the app shell.
 */
@Composable
fun LocalMusicSection(
    @Suppress("UNUSED_PARAMETER") controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasImagePermission: Boolean,
    onRequestImagePermission: () -> Unit,
    showModeFilter: Boolean,
    modifier: Modifier,
) {
    LocalMusicSection(
        hasAudioPermission = hasAudioPermission,
        onRequestAudioPermission = onRequestAudioPermission,
        hasImagePermission = hasImagePermission,
        onRequestImagePermission = onRequestImagePermission,
        showModeFilter = showModeFilter,
        modifier = modifier,
    )
}

@Composable
fun LocalMusicViewModeTabs(
    @Suppress("UNUSED_PARAMETER") controller: FuoPlayerController,
) {
    LocalMusicViewModeTabs()
}
