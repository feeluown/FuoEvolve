package org.feeluown.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

const val APP_SETTINGS_FILE_NAME = "app_settings.preferences_pb"

fun createSettingsDataStore(storage: Storage<Preferences>): DataStore<Preferences> =
    DataStoreFactory.create(storage = storage)

fun interface LegacyAppSettingsLoader {
    suspend fun load(): AppSettings
}

class DataStoreAppSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacyLoader: LegacyAppSettingsLoader?,
    private val scope: CoroutineScope,
) : AppSettingsRepository {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val updateMutex = Mutex()
    private val ready = CompletableDeferred<AppSettings>()
    private val mutableState = MutableStateFlow(SettingsState())

    override val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    init {
        scope.launchSettingsInitialization()
    }

    override suspend fun awaitSettings(): AppSettings = ready.await()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        ready.await()
        updateMutex.withLock {
            var updated = mutableState.value.settings
            dataStore.edit { preferences ->
                val current = preferences[SETTINGS_JSON_KEY]
                    ?.let { raw -> runCatching { decodeSettings(raw) }.getOrNull() }
                    ?: mutableState.value.settings
                updated = transform(current).withoutProviderCredentials()
                preferences[SETTINGS_JSON_KEY] = json.encodeToString(updated)
            }
            mutableState.value = SettingsState(isLoaded = true, settings = updated)
        }
    }

    private fun CoroutineScope.launchSettingsInitialization() = launch {
        runCatching { loadOrMigrate() }
            .onSuccess { settings ->
                mutableState.value = SettingsState(isLoaded = true, settings = settings)
                ready.complete(settings)
            }
            .onFailure { throwable ->
                val fallback = AppSettings()
                mutableState.value = SettingsState(
                    isLoaded = true,
                    settings = fallback,
                    errorMessage = throwable.message ?: throwable::class.simpleName,
                )
                ready.complete(fallback)
            }
    }

    private suspend fun loadOrMigrate(): AppSettings {
        val stored = dataStore.data.first()[SETTINGS_JSON_KEY]
        if (!stored.isNullOrBlank()) {
            return runCatching { decodeSettings(stored) }.getOrElse {
                AppSettings().also { fallback ->
                    dataStore.edit { preferences ->
                        preferences[SETTINGS_JSON_KEY] = json.encodeToString(fallback)
                    }
                }
            }
        }

        val migrated = legacyLoader
            ?.load()
            ?.withoutProviderCredentials()
            ?: AppSettings()
        dataStore.edit { preferences ->
            preferences[SETTINGS_JSON_KEY] = json.encodeToString(migrated)
        }
        return migrated
    }

    private fun decodeSettings(raw: String): AppSettings = json.decodeFromString(raw)

    private fun AppSettings.withoutProviderCredentials(): AppSettings = copy(
        providerCookieInputs = emptyMap(),
        providerHeaderInputs = emptyMap(),
    )

    private companion object {
        val SETTINGS_JSON_KEY = stringPreferencesKey("app_settings_json_v1")
    }
}
