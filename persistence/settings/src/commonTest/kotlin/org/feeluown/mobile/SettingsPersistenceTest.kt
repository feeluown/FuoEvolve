package org.feeluown.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SettingsPersistenceTest {
    @Test
    fun readsHistoricalSettingsJsonWithoutDependingOnFeatureEnums() = runTest {
        val raw = """{
            "onboardingCompleted":true,
            "homeSection":"Music",
            "providerLoginMode":"Headers",
            "providerCookieInputs":{"netease":"secret"},
            "providerHeaderInputs":{"qqmusic":{"cookie":"secret"}},
            "wifiAudioQualityPolicy":"Highest",
            "themeMode":"Dark",
            "statusBarLyricsEnabled":true
        }""".trimIndent()
        val dataStore = FakePreferencesDataStore(
            mutablePreferencesOf(DataStoreSettingsSnapshotStore.SETTINGS_JSON_KEY to raw),
        )
        val store = DataStoreSettingsSnapshotStore(dataStore)

        val result = assertIs<SettingsSnapshotReadResult.Loaded>(store.read())

        assertEquals(true, result.snapshot.onboardingCompleted)
        assertEquals("Music", result.snapshot.homeSection)
        assertEquals("Headers", result.snapshot.providerLoginMode)
        assertEquals("Highest", result.snapshot.wifiAudioQualityPolicy)
        assertEquals("Dark", result.snapshot.themeMode)
        assertEquals(true, result.snapshot.statusBarLyricsEnabled)
        assertEquals(null, result.snapshot.lyricsAlignmentOffsetsMs)
    }

    @Test
    fun corruptedPayloadIsReportedSeparatelyFromMissingPayload() = runTest {
        val corrupted = DataStoreSettingsSnapshotStore(
            FakePreferencesDataStore(
                mutablePreferencesOf(DataStoreSettingsSnapshotStore.SETTINGS_JSON_KEY to "not-json"),
            ),
        )
        val missing = DataStoreSettingsSnapshotStore(FakePreferencesDataStore())

        assertIs<SettingsSnapshotReadResult.Corrupted>(corrupted.read())
        assertIs<SettingsSnapshotReadResult.Missing>(missing.read())
    }

    @Test
    fun snapshotRoundTripsThroughDataStore() = runTest {
        val dataStore = FakePreferencesDataStore()
        val store = DataStoreSettingsSnapshotStore(dataStore)
        val expected = PersistedSettingsV1(
            homeSection = "Recommend",
            smartReplacementSelections = mapOf(
                "netease:1" to PersistedSmartReplacementSelection(
                    replacementId = "qqmusic:2",
                    replacementTitle = "song",
                    replacementArtists = "artist",
                    replacementSource = "qqmusic",
                    replacementScore = 0.9,
                ),
            ),
            lyricsAssociations = mapOf("bilibili:BVdemo" to "netease:1"),
            lyricsAlignmentOffsetsMs = mapOf("bilibili:BVdemo" to -750L),
            playlistPlaybackStats = mapOf(
                "netease::playlist:1" to PersistedPlaylistPlaybackStat(2, 1234),
            ),
        )

        store.write(expected)

        val restored = assertIs<SettingsSnapshotReadResult.Loaded>(store.read()).snapshot
        assertEquals(expected, restored)
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
