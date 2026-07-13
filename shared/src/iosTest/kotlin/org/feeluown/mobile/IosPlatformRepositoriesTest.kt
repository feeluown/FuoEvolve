package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IosPlatformRepositoriesTest {
    @Test
    fun resolveUsesAudioQualityForCurrentNetwork() = runTest {
        val runtime = RecordingPythonRuntime()
        val networkStatus = FakeNetworkStatusOutput(isCellular = true)
        val repository = IosFuoCoreBridge(runtime, networkStatus)
        repository.updateAudioQualityPolicies(AudioQualityPolicy.Highest, AudioQualityPolicy.Low)

        repository.resolve(providerTrack(), UnavailablePlaybackPolicy.Skip, emptySet(), 0.5, false, false)
        assertEquals(AudioQualityPolicy.Low.policy, runtime.resolveArguments?.get(1))

        networkStatus.isCellular = false
        repository.resolve(providerTrack(), UnavailablePlaybackPolicy.Skip, emptySet(), 0.5, false, false)
        assertEquals(AudioQualityPolicy.Highest.policy, runtime.resolveArguments?.get(1))
    }

    @Test
    fun relaxedScanSettingsRestoreTracksFromUnfilteredCache() = runTest {
        val mediaLibrary = FakeMediaLibraryOutput()
        val repository = IosLocalMusicRepository(mediaLibrary)
        repository.refreshDatabase()

        repository.updateScanSettings(LocalMusicScanSettings(minDurationSeconds = 60))
        assertEquals(listOf("Long track"), repository.tracks().map { it.title })

        repository.updateScanSettings(
            LocalMusicScanSettings(excludedDirectoryIds = setOf("ios-media-library")),
        )
        assertEquals(emptyList(), repository.tracks())

        repository.updateScanSettings(LocalMusicScanSettings())
        assertEquals(listOf("Short track", "Long track"), repository.tracks().map { it.title })
        assertEquals(1, mediaLibrary.trackRequests)
    }

    private fun providerTrack() = MusicTrack(
        id = "netease:1",
        title = "Track",
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )

    private class FakeNetworkStatusOutput(
        var isCellular: Boolean,
    ) : IosNetworkStatusOutput {
        override fun isCellularConnection(): Boolean = isCellular
    }

    private class RecordingPythonRuntime : IosPythonRuntime {
        var resolveArguments: List<String>? = null

        override fun createBridge(enabledProvidersJson: String): String = "{}"

        override fun call(method: String, arguments: List<String>): String {
            if (method == "resolve") resolveArguments = arguments
            return "{\"url\":\"https://example.com/audio.mp3\"}"
        }
    }

    private class FakeMediaLibraryOutput : IosMediaLibraryOutput {
        var trackRequests = 0

        override fun hasPermission(): Boolean = true

        override fun requestPermission(completionHandler: (Boolean) -> Unit) = completionHandler(true)

        override fun tracksJson(): String {
            trackRequests += 1
            return """
                {
                  "tracks": [
                    {
                      "id": "short",
                      "title": "Short track",
                      "artists": "Artist",
                      "album": "Album",
                      "duration_ms": 30000,
                      "local_uri": "ipod-library://short"
                    },
                    {
                      "id": "long",
                      "title": "Long track",
                      "artists": "Artist",
                      "album": "Album",
                      "duration_ms": 120000,
                      "local_uri": "ipod-library://long"
                    }
                  ]
                }
            """.trimIndent()
        }
    }
}
