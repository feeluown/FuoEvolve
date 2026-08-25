package org.feeluown.mobile

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsRepositoryTest {
    @Test
    fun legacySettingsMigrateOnceAndDiscardLoginDrafts() = runTest {
        val store = FakeSettingsSnapshotStore()
        var migrationCount = 0
        val legacy = AppSettings(
            localMusicMinDurationSeconds = 42,
            providerCookieInputs = mapOf("netease" to "secret"),
            providerHeaderInputs = mapOf("qqmusic" to ProviderHeaderInput(cookie = "secret")),
        )
        val first = PersistentAppSettingsRepository(
            store = store,
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

        val second = PersistentAppSettingsRepository(
            store = store,
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
        val repository = PersistentAppSettingsRepository(
            store = FakeSettingsSnapshotStore(),
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
        val repository = PersistentAppSettingsRepository(
            store = FakeSettingsSnapshotStore(SettingsSnapshotReadResult.Corrupted),
            legacyLoader = null,
            scope = backgroundScope,
        )

        assertEquals(AppSettings(), repository.awaitSettings())
        repository.update { it.copy(themeMode = ThemeMode.Dark) }

        assertEquals(ThemeMode.Dark, repository.state.value.settings.themeMode)
    }

    @Test
    fun missingDynamicCoverSettingUsesDisabledDefault() = runTest {
        val repository = PersistentAppSettingsRepository(
            store = FakeSettingsSnapshotStore(
                SettingsSnapshotReadResult.Loaded(PersistedSettingsV1(onboardingCompleted = true)),
            ),
            legacyLoader = null,
            scope = backgroundScope,
        )

        val settings = repository.awaitSettings()

        assertTrue(settings.onboardingCompleted)
        assertFalse(settings.dynamicCoverColorEnabled)
        assertFalse(settings.statusBarLyricsEnabled)
        assertTrue(settings.pauseOnOtherAppPlayback)
        assertTrue(settings.smartReplacementSelections.isEmpty())
        assertTrue(settings.lyricsAlignmentOffsetsMs.isEmpty())
    }

    @Test
    fun statusBarLyricsSettingDefaultsOffAndRoundTrips() = runTest {
        val store = FakeSettingsSnapshotStore()
        val first = PersistentAppSettingsRepository(
            store = store,
            legacyLoader = null,
            scope = backgroundScope,
        )

        assertFalse(first.awaitSettings().statusBarLyricsEnabled)
        first.update { it.copy(statusBarLyricsEnabled = true) }

        val restored = PersistentAppSettingsRepository(
            store = store,
            legacyLoader = null,
            scope = backgroundScope,
        ).awaitSettings()
        assertTrue(restored.statusBarLyricsEnabled)
    }

    @Test
    fun playlistPlaybackStatsRoundTripWithProviderPlaylistKey() = runTest {
        val store = FakeSettingsSnapshotStore()
        val first = PersistentAppSettingsRepository(
            store = store,
            legacyLoader = null,
            scope = backgroundScope,
        )
        first.awaitSettings()
        val stat = PlaylistPlaybackStat(playCount = 3, lastPlayedAtMillis = 1234)

        first.update { settings ->
            settings.copy(
                playlistPlaybackStatsVersion = 1,
                playlistPlaybackStats = mapOf("netease::playlist:123" to stat),
            )
        }
        val restored = PersistentAppSettingsRepository(
            store = store,
            legacyLoader = null,
            scope = backgroundScope,
        ).awaitSettings()

        assertEquals(stat, restored.playlistPlaybackStats["netease::playlist:123"])
    }

    @Test
    fun smartReplacementSelectionRoundTripsAcrossSettingsStorage() = runTest {
        val store = FakeSettingsSnapshotStore()
        val selection = SmartReplacementSelection(
            replacementId = "qqmusic:456",
            replacementTitle = "候选歌曲",
            replacementArtists = "候选歌手",
            replacementAlbum = "候选专辑",
            replacementSource = "qqmusic",
            replacementProviderName = "QQ 音乐",
            replacementCoverUrl = "https://example.com/cover.jpg",
            replacementDurationMs = 192_000,
            replacementScore = 0.86,
        )
        val first = PersistentAppSettingsRepository(
            store = store,
            legacyLoader = null,
            scope = backgroundScope,
        )
        first.awaitSettings()
        first.update { it.copy(smartReplacementSelections = mapOf("netease:123" to selection)) }

        val restored = PersistentAppSettingsRepository(
            store = store,
            legacyLoader = null,
            scope = backgroundScope,
        ).awaitSettings()

        assertEquals(selection, restored.smartReplacementSelections["netease:123"])
    }

    @Test
    fun lyricsAssociationsAndAlignmentOffsetsRoundTrip() = runTest {
        val store = FakeSettingsSnapshotStore()
        val first = PersistentAppSettingsRepository(
            store = store,
            legacyLoader = null,
            scope = backgroundScope,
        )
        first.awaitSettings()
        first.update {
            it.copy(
                lyricsAssociations = mapOf("bilibili:BVdemo" to "netease:123"),
                lyricsAlignmentOffsetsMs = mapOf("bilibili:BVdemo" to 1_250L),
            )
        }

        val restored = PersistentAppSettingsRepository(
            store = store,
            legacyLoader = null,
            scope = backgroundScope,
        ).awaitSettings()

        assertEquals("netease:123", restored.lyricsAssociations["bilibili:BVdemo"])
        assertEquals(1_250L, restored.lyricsAlignmentOffsetsMs["bilibili:BVdemo"])
    }

    @Test
    fun incompatibleOrInvalidPlaylistPlaybackStatsAreDiscarded() {
        val legacy = AppSettings(
            playlistPlaybackStats = mapOf(
                "netease\u0000playlist:123" to PlaylistPlaybackStat(2, 1234),
            ),
        )
        val currentWithInvalidEntry = AppSettings(
            playlistPlaybackStatsVersion = 1,
            playlistPlaybackStats = mapOf(
                "netease\u0000playlist:123" to PlaylistPlaybackStat(2, 1234),
                "netease::playlist:456" to PlaylistPlaybackStat(3, 5678),
            ),
        )

        assertTrue(normalizedPlaylistPlaybackStats(legacy).isEmpty())
        assertEquals(
            setOf("netease::playlist:456"),
            normalizedPlaylistPlaybackStats(currentWithInvalidEntry).keys,
        )
    }

    private class FakeSettingsSnapshotStore(
        initial: SettingsSnapshotReadResult = SettingsSnapshotReadResult.Missing,
    ) : SettingsSnapshotStore {
        private var result: SettingsSnapshotReadResult = initial

        override suspend fun read(): SettingsSnapshotReadResult = result

        override suspend fun write(snapshot: PersistedSettingsV1) {
            result = SettingsSnapshotReadResult.Loaded(snapshot)
        }
    }
}
