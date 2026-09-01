package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberPredictiveBackPreference(): PredictiveBackPreference = remember {
    PredictiveBackPreference(
        isSupported = false,
        enabled = false,
        onEnabledChange = {},
    )
}

@Composable
internal actual fun PlatformLegacyBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
