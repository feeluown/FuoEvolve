package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AndroidAppSettingsStore(context: Context) : AppSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        AppSettings(
            homeSection = enumValue(KEY_HOME_SECTION, HomeSection.Recommend),
            localMusicViewMode = enumValue(KEY_LOCAL_MUSIC_VIEW_MODE, LocalMusicViewMode.All),
            searchScope = enumValue(KEY_SEARCH_SCOPE, SearchScope.All),
            selectedSearchProviderId = preferences.getString(KEY_SELECTED_SEARCH_PROVIDER_ID, null),
            selectedSettingsProviderId = preferences.getString(KEY_SELECTED_SETTINGS_PROVIDER_ID, null),
            providerLoginMode = enumValue(KEY_PROVIDER_LOGIN_MODE, ProviderLoginMode.WebView),
            providerCookieInputs = readCookieInputs(),
            audioCacheLimitMb = preferences.getInt(KEY_AUDIO_CACHE_LIMIT_MB, DEFAULT_AUDIO_CACHE_LIMIT_MB),
            imageCacheLimitMb = preferences.getInt(KEY_IMAGE_CACHE_LIMIT_MB, DEFAULT_IMAGE_CACHE_LIMIT_MB),
        )
    }

    override suspend fun save(settings: AppSettings) {
        withContext(Dispatchers.IO) {
            preferences.edit()
                .putString(KEY_HOME_SECTION, settings.homeSection.name)
                .putString(KEY_LOCAL_MUSIC_VIEW_MODE, settings.localMusicViewMode.name)
                .putString(KEY_SEARCH_SCOPE, settings.searchScope.name)
                .putNullableString(KEY_SELECTED_SEARCH_PROVIDER_ID, settings.selectedSearchProviderId)
                .putNullableString(KEY_SELECTED_SETTINGS_PROVIDER_ID, settings.selectedSettingsProviderId)
                .putString(KEY_PROVIDER_LOGIN_MODE, settings.providerLoginMode.name)
                .putString(KEY_PROVIDER_COOKIE_INPUTS, cookieInputsJson(settings.providerCookieInputs))
                .putInt(KEY_AUDIO_CACHE_LIMIT_MB, settings.audioCacheLimitMb)
                .putInt(KEY_IMAGE_CACHE_LIMIT_MB, settings.imageCacheLimitMb)
                .apply()
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T {
        val raw = preferences.getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
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

    private fun android.content.SharedPreferences.Editor.putNullableString(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        private const val PREFS_NAME = "fuo_settings"
        private const val KEY_HOME_SECTION = "home_section"
        private const val KEY_LOCAL_MUSIC_VIEW_MODE = "local_music_view_mode"
        private const val KEY_SEARCH_SCOPE = "search_scope"
        private const val KEY_SELECTED_SEARCH_PROVIDER_ID = "selected_search_provider_id"
        private const val KEY_SELECTED_SETTINGS_PROVIDER_ID = "selected_settings_provider_id"
        private const val KEY_PROVIDER_LOGIN_MODE = "provider_login_mode"
        private const val KEY_PROVIDER_COOKIE_INPUTS = "provider_cookie_inputs"
        private const val KEY_AUDIO_CACHE_LIMIT_MB = "audio_cache_limit_mb"
        private const val KEY_IMAGE_CACHE_LIMIT_MB = "image_cache_limit_mb"
    }
}
