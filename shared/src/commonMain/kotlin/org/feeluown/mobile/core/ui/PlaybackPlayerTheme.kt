package org.feeluown.mobile

import androidx.compose.runtime.Composable

/**
 * Full-player theme entry point kept at the existing call site while the cover palette is now
 * produced once by [ProvidePlaybackColorEnvironment] at the app shell.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun PlayerDynamicColorTheme(
    themeMode: ThemeMode,
    dynamicCoverColorEnabled: Boolean,
    coverImageUrl: String?,
    isLoading: Boolean,
    content: @Composable () -> Unit,
) {
    PlaybackDynamicColorTheme(
        emphasis = PlaybackColorEmphasis.Immersive,
        content = content,
    )
}
