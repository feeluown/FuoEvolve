package org.feeluown.mobile

import androidx.compose.runtime.Composable

internal data class PredictiveBackPreference(
    val isSupported: Boolean,
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
)

@Composable
internal expect fun rememberPredictiveBackPreference(): PredictiveBackPreference

@Composable
internal expect fun PlatformLegacyBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
