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
