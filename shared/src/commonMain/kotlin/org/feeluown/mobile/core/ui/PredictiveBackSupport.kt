package org.feeluown.mobile

import androidx.compose.runtime.Composable

internal data class PredictiveBackPreference(
    val isSupported: Boolean,
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
)

internal enum class PredictiveBackSwipeEdge {
    Left,
    Right,
    None,
}

internal data class PredictiveBackGestureEvent(
    val progress: Float,
    val touchX: Float,
    val touchY: Float,
    val swipeEdge: PredictiveBackSwipeEdge,
)

@Composable
internal expect fun rememberPredictiveBackPreference(): PredictiveBackPreference

@Composable
internal expect fun PlatformLegacyBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

@Composable
internal expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (PredictiveBackGestureEvent) -> Unit,
    onCancelled: () -> Unit,
    onBack: () -> Unit,
)
