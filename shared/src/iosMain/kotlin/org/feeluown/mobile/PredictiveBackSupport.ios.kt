package org.feeluown.mobile

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberPredictiveBackPreference(): PredictiveBackPreference =
    PredictiveBackPreference(
        isSupported = false,
        enabled = false,
        onEnabledChange = {},
    )

@Composable
internal actual fun PlatformLegacyBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit

@Composable
internal actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (PredictiveBackGestureEvent) -> Unit,
    onCancelled: () -> Unit,
    onBack: () -> Unit,
) = Unit
