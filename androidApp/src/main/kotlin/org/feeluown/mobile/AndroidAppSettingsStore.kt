package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidAppSettingsStore(context: Context) : AppSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val rawHomeSection = preferences.getString(KEY_HOME_SECTION, null)
        AppSettings(
            homeSection = homeSectionValue(rawHomeSection),
            mineSection = mineSectionValue(rawHomeSection),
            localMusicViewMode = enumValue(KEY_LOCAL_MUSIC_VIEW_MODE, LocalMusicViewMode.All),
            excludedLocalMusicDirectoryIds = readStringSet(KEY_EXCLUDED_LOCAL_MUSIC_DIRECTORY_IDS),
            localMusicMinDurationSeconds = preferences.getInt(
                KEY_LOCAL_MUSIC_MIN_DURATION_SECONDS,
                DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
            ),
            searchScope = enumValue(KEY_SEARCH_SCOPE, SearchScope.All),
            selectedSearchProviderId = preferences.getString(KEY_SELECTED_SEARCH_PROVIDER_ID, null),
            selectedSettingsProviderId = preferences.getString(KEY_SELECTED_SETTINGS_PROVIDER_ID, null),
            providerLoginMode = enumValue(KEY_PROVIDER_LOGIN_MODE, ProviderLoginMode.WebView),
            providerCookieInputs = readCookieInputs(),
            providerHeaderInputs = readHeaderInputs(),
            enabledProviderIds = readStringSet(KEY_ENABLED_PROVIDER_IDS).ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS },
            providerOrderIds = readStringList(KEY_PROVIDER_ORDER_IDS).ifEmpty { DEFAULT_PROVIDER_ORDER_IDS },
            audioCacheLimitMb = preferences.getInt(KEY_AUDIO_CACHE_LIMIT_MB, DEFAULT_AUDIO_CACHE_LIMIT_MB),
            imageCacheLimitMb = preferences.getInt(KEY_IMAGE_CACHE_LIMIT_MB, DEFAULT_IMAGE_CACHE_LIMIT_MB),
            wifiAudioQualityPolicy = enumValue(KEY_WIFI_AUDIO_QUALITY_POLICY, DEFAULT_WIFI_AUDIO_QUALITY_POLICY),
            cellularAudioQualityPolicy = enumValue(
                KEY_CELLULAR_AUDIO_QUALITY_POLICY,
                DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY,
            ),
            unavailablePlaybackPolicy = enumValue(
                KEY_UNAVAILABLE_PLAYBACK_POLICY,
                DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
            ),
        )
    }

    override suspend fun save(settings: AppSettings) {
        withContext(Dispatchers.IO) {
            preferences.edit()
                .putString(KEY_HOME_SECTION, settings.homeSection.name)
                .putString(KEY_MINE_SECTION, settings.mineSection.name)
                .putString(KEY_LOCAL_MUSIC_VIEW_MODE, settings.localMusicViewMode.name)
                .putStringSet(KEY_EXCLUDED_LOCAL_MUSIC_DIRECTORY_IDS, settings.excludedLocalMusicDirectoryIds)
                .putInt(KEY_LOCAL_MUSIC_MIN_DURATION_SECONDS, settings.localMusicMinDurationSeconds)
                .putString(KEY_SEARCH_SCOPE, settings.searchScope.name)
                .putNullableString(KEY_SELECTED_SEARCH_PROVIDER_ID, settings.selectedSearchProviderId)
                .putNullableString(KEY_SELECTED_SETTINGS_PROVIDER_ID, settings.selectedSettingsProviderId)
                .putString(KEY_PROVIDER_LOGIN_MODE, settings.providerLoginMode.name)
                .putString(KEY_PROVIDER_COOKIE_INPUTS, cookieInputsJson(settings.providerCookieInputs))
                .putString(KEY_PROVIDER_HEADER_INPUTS, headerInputsJson(settings.providerHeaderInputs))
                .putStringSet(KEY_ENABLED_PROVIDER_IDS, settings.enabledProviderIds)
                .putString(KEY_PROVIDER_ORDER_IDS, stringListJson(settings.providerOrderIds))
                .putInt(KEY_AUDIO_CACHE_LIMIT_MB, settings.audioCacheLimitMb)
                .putInt(KEY_IMAGE_CACHE_LIMIT_MB, settings.imageCacheLimitMb)
                .putString(KEY_WIFI_AUDIO_QUALITY_POLICY, settings.wifiAudioQualityPolicy.name)
                .putString(KEY_CELLULAR_AUDIO_QUALITY_POLICY, settings.cellularAudioQualityPolicy.name)
                .putString(KEY_UNAVAILABLE_PLAYBACK_POLICY, settings.unavailablePlaybackPolicy.name)
                .apply()
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T {
        val raw = preferences.getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    private fun homeSectionValue(raw: String?): HomeSection {
        if (raw == "Local") return HomeSection.Mine
        return runCatching { enumValueOf<HomeSection>(raw.orEmpty()) }.getOrDefault(HomeSection.Recommend)
    }

    private fun mineSectionValue(rawHomeSection: String?): MineSection {
        if (rawHomeSection == "Local") return MineSection.LocalMusic
        return enumValue(KEY_MINE_SECTION, MineSection.Playlists)
    }

    private fun readCookieInputs(): Map<String, String> {
        val raw = preferences.getString(KEY_PROVIDER_COOKIE_INPUTS, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) {
                        put(key, value)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun cookieInputsJson(inputs: Map<String, String>): String {
        val json = JSONObject()
        inputs.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                json.put(key, value)
            }
        }
        return json.toString()
    }

    private fun readHeaderInputs(): Map<String, ProviderHeaderInput> {
        val raw = preferences.getString(KEY_PROVIDER_HEADER_INPUTS, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val providerId = keys.next()
                    val value = json.optJSONObject(providerId) ?: continue
                    val input = ProviderHeaderInput(
                        authorization = value.optString("authorization"),
                        cookie = value.optString("cookie"),
                    )
                    if (providerId.isNotBlank() && (input.authorization.isNotBlank() || input.cookie.isNotBlank())) {
                        put(providerId, input)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun headerInputsJson(inputs: Map<String, ProviderHeaderInput>): String {
        val json = JSONObject()
        inputs.forEach { (providerId, input) ->
            if (providerId.isNotBlank() && (input.authorization.isNotBlank() || input.cookie.isNotBlank())) {
                json.put(
                    providerId,
                    JSONObject()
                        .put("authorization", input.authorization)
                        .put("cookie", input.cookie),
                )
            }
        }
        return json.toString()
    }

    private fun readStringSet(key: String): Set<String> {
        return preferences.getStringSet(key, emptySet()).orEmpty().filter { it.isNotBlank() }.toSet()
    }

    private fun readStringList(key: String): List<String> {
        val raw = preferences.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.optString(index) }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun stringListJson(values: List<String>): String {
        val array = JSONArray()
        values.filter { it.isNotBlank() }.distinct().forEach { array.put(it) }
        return array.toString()
    }

    private fun android.content.SharedPreferences.Editor.putNullableString(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        private const val PREFS_NAME = "fuo_settings"
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
        private const val KEY_AUDIO_CACHE_LIMIT_MB = "audio_cache_limit_mb"
        private const val KEY_IMAGE_CACHE_LIMIT_MB = "image_cache_limit_mb"
        private const val KEY_WIFI_AUDIO_QUALITY_POLICY = "wifi_audio_quality_policy"
        private const val KEY_CELLULAR_AUDIO_QUALITY_POLICY = "cellular_audio_quality_policy"
        private const val KEY_UNAVAILABLE_PLAYBACK_POLICY = "unavailable_playback_policy"
    }
}
