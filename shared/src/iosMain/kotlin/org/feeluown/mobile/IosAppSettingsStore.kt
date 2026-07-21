package org.feeluown.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

internal class IosLegacySettingsLoader {
    private val defaults = NSUserDefaults.standardUserDefaults

    suspend fun load(): AppSettings = withContext(Dispatchers.Default) {
        AppSettings(
            onboardingCompleted = boolValue(KEY_ONBOARDING_COMPLETED, false),
            homeSection = enumValue(KEY_HOME_SECTION, HomeSection.Recommend),
            mineSection = enumValue(KEY_MINE_SECTION, MineSection.Playlists),
            localMusicViewMode = enumValue(KEY_LOCAL_MUSIC_VIEW_MODE, LocalMusicViewMode.All),
            excludedLocalMusicDirectoryIds = readStringSet(KEY_EXCLUDED_LOCAL_MUSIC_DIRECTORY_IDS),
            localMusicMinDurationSeconds = intValue(
                KEY_LOCAL_MUSIC_MIN_DURATION_SECONDS,
                DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
            ),
            searchScope = enumValue(KEY_SEARCH_SCOPE, SearchScope.All),
            selectedSearchProviderId = stringValue(KEY_SELECTED_SEARCH_PROVIDER_ID),
            selectedSettingsProviderId = stringValue(KEY_SELECTED_SETTINGS_PROVIDER_ID),
            providerLoginMode = enumValue(KEY_PROVIDER_LOGIN_MODE, ProviderLoginMode.WebView),
            providerCookieInputs = readStringMap(KEY_PROVIDER_COOKIE_INPUTS),
            providerHeaderInputs = readHeaderInputs(),
            enabledProviderIds = readStringSet(KEY_ENABLED_PROVIDER_IDS).ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS },
            providerOrderIds = readStringList(KEY_PROVIDER_ORDER_IDS).ifEmpty { DEFAULT_PROVIDER_ORDER_IDS },
            searchProviderIds = readStringSet(KEY_SEARCH_PROVIDER_IDS),
            recommendProviderIds = readStringSet(KEY_RECOMMEND_PROVIDER_IDS),
            exploreProviderIds = readStringSet(KEY_EXPLORE_PROVIDER_IDS),
            mineProviderIds = readStringSet(KEY_MINE_PROVIDER_IDS),
            audioCacheLimitMb = intValue(KEY_AUDIO_CACHE_LIMIT_MB, DEFAULT_AUDIO_CACHE_LIMIT_MB),
            imageCacheLimitMb = intValue(KEY_IMAGE_CACHE_LIMIT_MB, DEFAULT_IMAGE_CACHE_LIMIT_MB),
            downloadParallelism = intValue(KEY_DOWNLOAD_PARALLELISM, DEFAULT_DOWNLOAD_PARALLELISM).coerceIn(1, 5),
            wifiAudioQualityPolicy = enumValue(KEY_WIFI_AUDIO_QUALITY_POLICY, DEFAULT_WIFI_AUDIO_QUALITY_POLICY),
            cellularAudioQualityPolicy = enumValue(KEY_CELLULAR_AUDIO_QUALITY_POLICY, DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY),
            unavailablePlaybackPolicy = enumValue(KEY_UNAVAILABLE_PLAYBACK_POLICY, DEFAULT_UNAVAILABLE_PLAYBACK_POLICY),
            smartReplacementProviderIds = readStringSet(KEY_SMART_REPLACEMENT_PROVIDER_IDS),
            smartReplacementMinScore = doubleValue(
                KEY_SMART_REPLACEMENT_MIN_SCORE,
                DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
            ),
            smartReplacementUseReplacementMetadata = boolValue(KEY_SMART_REPLACEMENT_USE_REPLACEMENT_METADATA, false),
            smartReplacementUseReplacementLyrics = boolValue(KEY_SMART_REPLACEMENT_USE_REPLACEMENT_LYRICS, false),
            lyricFontSize = enumValue(KEY_LYRIC_FONT_SIZE, LyricFontSize.Small),
            playbackSpectrumStyle = if (boolValue(KEY_SHOW_PLAYBACK_SPECTRUM, true)) {
                enumValue(KEY_PLAYBACK_SPECTRUM_STYLE, PlaybackSpectrumStyle.None)
            } else {
                PlaybackSpectrumStyle.None
            },
            themeMode = enumValue(KEY_THEME_MODE, ThemeMode.System),
            themeColorScheme = enumValue(KEY_THEME_COLOR_SCHEME, ThemeColorScheme.Dynamic),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T {
        return runCatching { enumValueOf<T>(stringValue(key).orEmpty()) }.getOrDefault(fallback)
    }

    private fun stringValue(key: String): String? = defaults.stringForKey(key)?.takeIf { it.isNotBlank() }

    private fun intValue(key: String, fallback: Int): Int {
        if (defaults.objectForKey(key) == null) return fallback
        return defaults.integerForKey(key).toInt()
    }

    private fun doubleValue(key: String, fallback: Double): Double {
        if (defaults.objectForKey(key) == null) return fallback
        return defaults.doubleForKey(key)
    }

    private fun boolValue(key: String, fallback: Boolean): Boolean {
        if (defaults.objectForKey(key) == null) return fallback
        return defaults.boolForKey(key)
    }

    private fun readStringSet(key: String): Set<String> = readStringList(key).toSet()


    private fun readStringList(key: String): List<String> {
        return stringValue(key)
            ?.split(LIST_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
    }

    private fun readStringMap(key: String): Map<String, String> {
        return stringValue(key)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split(MAP_SEPARATOR, limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    parts[0] to parts[1]
                } else {
                    null
                }
            }
            ?.toMap()
            .orEmpty()
    }

    private fun readHeaderInputs(): Map<String, ProviderHeaderInput> {
        return readStringMap(KEY_PROVIDER_HEADER_INPUTS).mapValues { (_, raw) ->
            val parts = raw.split(HEADER_SEPARATOR, limit = 2)
            ProviderHeaderInput(
                authorization = parts.getOrElse(0) { "" },
                cookie = parts.getOrElse(1) { "" },
            )
        }
    }

    private companion object {
        private const val LIST_SEPARATOR = "\u001f"
        private const val MAP_SEPARATOR = "\u001e"
        private const val HEADER_SEPARATOR = "\u001d"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_HOME_SECTION = "home_section"
        private const val KEY_MINE_SECTION = "mine_section"
        private const val KEY_LOCAL_MUSIC_VIEW_MODE = "local_music_view_mode"
        private const val KEY_EXCLUDED_LOCAL_MUSIC_DIRECTORY_IDS = "excluded_local_music_directory_ids"
        private const val KEY_LOCAL_MUSIC_MIN_DURATION_SECONDS = "local_music_min_duration_seconds"
        private const val KEY_SEARCH_SCOPE = "search_scope"
        private const val KEY_SELECTED_SEARCH_PROVIDER_ID = "selected_search_provider_id"
        private const val KEY_SELECTED_SETTINGS_PROVIDER_ID = "selected_settings_provider_id"
        private const val KEY_PROVIDER_LOGIN_MODE = "provider_login_mode"
        private const val KEY_PROVIDER_COOKIE_INPUTS = "provider_cookie_inputs"
        private const val KEY_PROVIDER_HEADER_INPUTS = "provider_header_inputs"
        private const val KEY_ENABLED_PROVIDER_IDS = "enabled_provider_ids"
        private const val KEY_PROVIDER_ORDER_IDS = "provider_order_ids"
        private const val KEY_SEARCH_PROVIDER_IDS = "search_provider_ids"
        private const val KEY_RECOMMEND_PROVIDER_IDS = "recommend_provider_ids"
        private const val KEY_EXPLORE_PROVIDER_IDS = "explore_provider_ids"
        private const val KEY_MINE_PROVIDER_IDS = "mine_provider_ids"
        private const val KEY_AUDIO_CACHE_LIMIT_MB = "audio_cache_limit_mb"
        private const val KEY_IMAGE_CACHE_LIMIT_MB = "image_cache_limit_mb"
        private const val KEY_DOWNLOAD_PARALLELISM = "download_parallelism"
        private const val KEY_WIFI_AUDIO_QUALITY_POLICY = "wifi_audio_quality_policy"
        private const val KEY_CELLULAR_AUDIO_QUALITY_POLICY = "cellular_audio_quality_policy"
        private const val KEY_UNAVAILABLE_PLAYBACK_POLICY = "unavailable_playback_policy"
        private const val KEY_SMART_REPLACEMENT_PROVIDER_IDS = "smart_replacement_provider_ids"
        private const val KEY_SMART_REPLACEMENT_MIN_SCORE = "smart_replacement_min_score"
        private const val KEY_SMART_REPLACEMENT_USE_REPLACEMENT_METADATA = "smart_replacement_use_replacement_metadata"
        private const val KEY_SMART_REPLACEMENT_USE_REPLACEMENT_LYRICS = "smart_replacement_use_replacement_lyrics"
        private const val KEY_LYRIC_FONT_SIZE = "lyric_font_size"
        private const val KEY_SHOW_PLAYBACK_SPECTRUM = "show_playback_spectrum"
        private const val KEY_PLAYBACK_SPECTRUM_STYLE = "playback_spectrum_style"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR_SCHEME = "theme_color_scheme"
    }
}
