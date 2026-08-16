package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class SettingsController(
    private val providerRepository: ProviderMusicRepository,
    private val state: SettingsControllerState,
    private val scope: CoroutineScope,
    private val persistSettings: () -> Unit,
) {
    fun onWifiAudioQualityPolicyChange(value: AudioQualityPolicy) {
        state.wifiAudioQualityPolicy = value
        persistSettings()
        updateAudioQualityPolicies()
    }

    fun onCellularAudioQualityPolicyChange(value: AudioQualityPolicy) {
        state.cellularAudioQualityPolicy = value
        persistSettings()
        updateAudioQualityPolicies()
    }

    fun onPauseOnOtherAppPlaybackChange(value: Boolean) {
        state.pauseOnOtherAppPlayback = value
        persistSettings()
    }

    fun onLyricFontSizeChange(value: LyricFontSize) {
        state.lyricFontSize = value
        persistSettings()
    }

    fun onThemeModeChange(value: ThemeMode) {
        state.themeMode = value
        persistSettings()
    }

    fun onThemeColorSchemeChange(value: ThemeColorScheme) {
        state.themeColorScheme = value
        persistSettings()
    }

    fun onDynamicCoverColorEnabledChange(value: Boolean) {
        state.dynamicCoverColorEnabled = value
        persistSettings()
    }

    fun updateAudioQualityPolicies() {
        scope.launch {
            providerRepository.updateAudioQualityPolicies(
                state.wifiAudioQualityPolicy,
                state.cellularAudioQualityPolicy,
            )
        }
    }

    suspend fun updateAudioQualityPoliciesNow() {
        providerRepository.updateAudioQualityPolicies(
            state.wifiAudioQualityPolicy,
            state.cellularAudioQualityPolicy,
        )
    }
}
