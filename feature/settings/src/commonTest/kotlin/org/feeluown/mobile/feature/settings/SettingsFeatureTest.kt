@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsFeatureTest {
    @Test
    fun appliesSavedAudioAndCachePreferencesOnStartup() = runSettingsTest { fixture ->
        advanceUntilIdle()

        assertEquals("high" to "low", fixture.audio.applied.single())
        assertEquals(512L * 1024L * 1024L to 128L * 1024L * 1024L, fixture.cache.limits.single())
        assertEquals(1, fixture.cache.refreshCount)
    }

    @Test
    fun coordinatesPreferenceAndRuntimeUpdates() = runSettingsTest { fixture ->
        fixture.owner.setWifiAudioQualityPolicy("highest")
        fixture.owner.setDownloadParallelism(9)
        fixture.owner.setAudioCacheLimitMb(1024)
        advanceUntilIdle()

        assertEquals("highest", fixture.preferences.state.value.wifiAudioQualityPolicy)
        assertEquals("highest" to "low", fixture.audio.applied.last())
        assertEquals(5, fixture.preferences.state.value.downloadParallelism)
        assertEquals(5, fixture.downloads.parallelism)
        assertEquals(1024, fixture.preferences.state.value.audioCacheLimitMb)
        assertEquals(1024L * 1024L * 1024L to 128L * 1024L * 1024L, fixture.cache.limits.last())
    }

    @Test
    fun ownsFeedbackNavigationAndLocalMusicCoordination() = runSettingsTest { fixture ->
        fixture.owner.setStatusBarLyricsAvailability(true)
        fixture.owner.refreshLocalMusicDirectories()
        fixture.owner.setLocalMusicDirectoryEnabled("music", false)
        fixture.owner.setLocalMusicMinDurationSeconds(30)
        fixture.owner.clearCache()
        advanceUntilIdle()

        assertTrue(fixture.owner.state.value.statusBarLyricsAvailable)
        assertEquals(1, fixture.localMusic.refreshCount)
        assertEquals("music" to false, fixture.localMusic.directoryChange)
        assertEquals(30, fixture.localMusic.recordedMinDurationSeconds)
        assertEquals("缓存已清理", fixture.owner.state.value.feedback)
        assertFalse(fixture.owner.state.value.isBusy)

        fixture.owner.openDownloadManager()
        fixture.owner.openDebugLogs()
        fixture.owner.close()
        assertEquals(listOf("download", "debug", "close"), fixture.navigation.events)
    }

    private fun runSettingsTest(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = FakePreferences()
        val audio = FakeAudio()
        val downloads = FakeDownloads()
        val cache = FakeCache()
        val localMusic = FakeLocalMusic()
        val navigation = FakeNavigation()
        val owner = createSettingsFeatureOwner(
            preferences = preferences,
            audioQuality = audio,
            downloads = downloads,
            cache = cache,
            localMusic = localMusic,
            navigation = navigation,
            debugLogViewerAvailable = true,
            scope = TestScope(dispatcher),
        )
        block(Fixture(owner, preferences, audio, downloads, cache, localMusic, navigation))
    }

    private data class Fixture(
        val owner: SettingsFeatureOwner<String, String, String, String, String, String, String, String, String, String>,
        val preferences: FakePreferences,
        val audio: FakeAudio,
        val downloads: FakeDownloads,
        val cache: FakeCache,
        val localMusic: FakeLocalMusic,
        val navigation: FakeNavigation,
    )

    private class FakePreferences : SettingsPreferencesPort<String, String, String, String, String, String, String> {
        private val mutableState = MutableStateFlow(
            SettingsFeaturePreferences(
                themeMode = "system",
                themeColorScheme = "dynamic",
                themePaletteStyle = "expressive",
                themeColorSpec = "2025",
                wifiAudioQualityPolicy = "high",
                cellularAudioQualityPolicy = "low",
                unavailablePlaybackPolicy = "replace",
                smartReplacementMinScore = 0.55,
                pauseOnOtherAppPlayback = true,
                lyricFontSize = "small",
                statusBarLyricsEnabled = false,
                dynamicCoverColorEnabled = false,
                downloadParallelism = 2,
                audioCacheLimitMb = 512,
                imageCacheLimitMb = 128,
            )
        )
        override val state: StateFlow<SettingsFeaturePreferences<String, String, String, String, String, String, String>> = mutableState
        override suspend fun awaitPreferences() = state.value
        override suspend fun setThemeMode(value: String) = update { copy(themeMode = value) }
        override suspend fun setThemeColorScheme(value: String) = update { copy(themeColorScheme = value) }
        override suspend fun setThemePaletteStyle(value: String) = update { copy(themePaletteStyle = value) }
        override suspend fun setThemeColorSpec(value: String) = update { copy(themeColorSpec = value) }
        override suspend fun setWifiAudioQualityPolicy(value: String) = update { copy(wifiAudioQualityPolicy = value) }
        override suspend fun setCellularAudioQualityPolicy(value: String) = update { copy(cellularAudioQualityPolicy = value) }
        override suspend fun setUnavailablePlaybackPolicy(value: String) = update { copy(unavailablePlaybackPolicy = value) }
        override suspend fun setSmartReplacementMinScore(value: Double) = update { copy(smartReplacementMinScore = value) }
        override suspend fun setPauseOnOtherAppPlayback(value: Boolean) = update { copy(pauseOnOtherAppPlayback = value) }
        override suspend fun setLyricFontSize(value: String) = update { copy(lyricFontSize = value) }
        override suspend fun setStatusBarLyricsEnabled(value: Boolean) = update { copy(statusBarLyricsEnabled = value) }
        override suspend fun setDynamicCoverColorEnabled(value: Boolean) = update { copy(dynamicCoverColorEnabled = value) }
        override suspend fun setDownloadParallelism(value: Int) = update { copy(downloadParallelism = value) }
        override suspend fun setCacheLimits(audioMb: Int, imageMb: Int) = update {
            copy(audioCacheLimitMb = audioMb, imageCacheLimitMb = imageMb)
        }

        private fun update(block: SettingsFeaturePreferences<String, String, String, String, String, String, String>.() -> SettingsFeaturePreferences<String, String, String, String, String, String, String>) {
            mutableState.value = mutableState.value.block()
        }
    }

    private class FakeAudio : SettingsAudioQualityPort<String> {
        val applied = mutableListOf<Pair<String, String>>()
        override suspend fun apply(wifi: String, cellular: String) {
            applied += wifi to cellular
        }
    }

    private class FakeDownloads : SettingsDownloadPort<String> {
        override val tasks = MutableStateFlow<List<String>>(emptyList())
        var parallelism = 0
        override suspend fun updateParallelism(value: Int) {
            parallelism = value
        }
    }

    private class FakeCache : SettingsCachePort<String> {
        override val usage = MutableStateFlow("0")
        val limits = mutableListOf<Pair<Long, Long>>()
        var refreshCount = 0
        override suspend fun updateLimit(audioMaxBytes: Long, imageMaxBytes: Long) {
            limits += audioMaxBytes to imageMaxBytes
        }
        override suspend fun clearAll() = Unit
        override suspend fun refreshUsage() {
            refreshCount += 1
        }
    }

    private class FakeLocalMusic : SettingsLocalMusicPort<String> {
        override val state = MutableStateFlow("local")
        var refreshCount = 0
        var directoryChange: Pair<String, Boolean>? = null
        var recordedMinDurationSeconds = 0
        override fun refreshDirectories() {
            refreshCount += 1
        }
        override fun setDirectoryEnabled(directoryId: String, enabled: Boolean) {
            directoryChange = directoryId to enabled
        }
        override fun setMinDurationSeconds(value: Int) {
            recordedMinDurationSeconds = value
        }
    }

    private class FakeNavigation : SettingsNavigationPort {
        val events = mutableListOf<String>()
        override fun close() { events += "close" }
        override fun openDownloadManager() { events += "download" }
        override fun openDebugLogs() { events += "debug" }
    }
}
