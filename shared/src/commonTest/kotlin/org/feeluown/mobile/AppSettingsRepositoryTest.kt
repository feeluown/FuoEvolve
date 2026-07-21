package org.feeluown.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsRepositoryTest {
    @Test
    fun legacySettingsMigrateOnceAndDiscardLoginDrafts() = runTest {
        val dataStore = FakePreferencesDataStore()
        var migrationCount = 0
        val legacy = AppSettings(
            localMusicMinDurationSeconds = 42,
            providerCookieInputs = mapOf("netease" to "secret"),
            providerHeaderInputs = mapOf("qqmusic" to ProviderHeaderInput(cookie = "secret")),
        )
        val first = DataStoreAppSettingsRepository(
            dataStore = dataStore,
            legacyLoader = LegacyAppSettingsLoader {
                migrationCount += 1
                legacy
            },
            scope = backgroundScope,
        )

        val migrated = first.awaitSettings()

        assertEquals(42, migrated.localMusicMinDurationSeconds)
        assertTrue(migrated.providerCookieInputs.isEmpty())
        assertTrue(migrated.providerHeaderInputs.isEmpty())

        val second = DataStoreAppSettingsRepository(
            dataStore = dataStore,
            legacyLoader = LegacyAppSettingsLoader {
                migrationCount += 1
                AppSettings(localMusicMinDurationSeconds = 99)
            },
            scope = backgroundScope,
        )

        assertEquals(42, second.awaitSettings().localMusicMinDurationSeconds)
        assertEquals(1, migrationCount)
    }

    @Test
    fun concurrentTransformsDoNotLoseUpdates() = runTest {
        val repository = DataStoreAppSettingsRepository(
            dataStore = FakePreferencesDataStore(),
            legacyLoader = null,
            scope = backgroundScope,
        )
        repository.awaitSettings()

        List(20) {
            async {
                repository.update { settings ->
                    settings.copy(localMusicMinDurationSeconds = settings.localMusicMinDurationSeconds + 1)
                }
            }
        }.awaitAll()

        assertEquals(
            DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS + 20,
            repository.state.value.settings.localMusicMinDurationSeconds,
        )
    }

    @Test
    fun corruptedPayloadRecoversAndAcceptsFutureUpdates() = runTest {
        val dataStore = FakePreferencesDataStore(
            mutablePreferencesOf(stringPreferencesKey("app_settings_json_v1") to "not-json"),
        )
        val repository = DataStoreAppSettingsRepository(
            dataStore = dataStore,
            legacyLoader = null,
            scope = backgroundScope,
        )

        assertEquals(AppSettings(), repository.awaitSettings())
        repository.update { it.copy(themeMode = ThemeMode.Dark) }

        assertEquals(ThemeMode.Dark, repository.state.value.settings.themeMode)
    }

    private class FakePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val mutableData = MutableStateFlow(initial)

        override val data: Flow<Preferences> = mutableData

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(mutableData.value).also { mutableData.value = it }
            }
    }
}
