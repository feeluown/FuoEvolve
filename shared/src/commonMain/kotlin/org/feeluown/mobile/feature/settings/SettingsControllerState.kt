package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class SettingsControllerState {
    var cacheUsage by mutableStateOf(CacheUsage())
    var audioCacheLimitMb by mutableStateOf(DEFAULT_AUDIO_CACHE_LIMIT_MB)
    var imageCacheLimitMb by mutableStateOf(DEFAULT_IMAGE_CACHE_LIMIT_MB)
    var wifiAudioQualityPolicy by mutableStateOf(DEFAULT_WIFI_AUDIO_QUALITY_POLICY)
    var cellularAudioQualityPolicy by mutableStateOf(DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY)
    var unavailablePlaybackPolicy by mutableStateOf(DEFAULT_UNAVAILABLE_PLAYBACK_POLICY)
    var smartReplacementProviderIds by mutableStateOf<Set<String>>(emptySet())
    var smartReplacementMinScore by mutableStateOf(DEFAULT_SMART_REPLACEMENT_MIN_SCORE)
    var lyricFontSize by mutableStateOf(LyricFontSize.Small)
    var themeMode by mutableStateOf(ThemeMode.System)
    var themeColorScheme by mutableStateOf(ThemeColorScheme.Dynamic)
    var dynamicCoverColorEnabled by mutableStateOf(false)
    var pauseOnOtherAppPlayback by mutableStateOf(DEFAULT_PAUSE_ON_OTHER_APP_PLAYBACK)
    var debugLogLines by mutableStateOf<List<String>>(emptyList())
    var debugLogLevelFilters by mutableStateOf(setOf(DebugLogLevel.Info, DebugLogLevel.Warning, DebugLogLevel.Error))
    var debugLogError by mutableStateOf<String?>(null)
}
