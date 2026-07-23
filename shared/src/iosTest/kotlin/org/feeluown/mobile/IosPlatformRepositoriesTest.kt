package org.feeluown.mobile

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun audioRecognitionTranslatesProgressAndResults() = runTest {
        val output = FakeAudioRecognitionOutput(
            resultJson = """
                [{
                  "netease_song_id":"42",
                  "title":"Song",
                  "artists":["Artist A","Artist B"],
                  "album":"Album",
                  "cover_url":"https://example.com/cover.jpg",
                  "match_start_time_ms":1234
                }]
            """.trimIndent(),
        )
        val repository = IosAudioRecognitionRepository(output)
        val events = mutableListOf<AudioRecognitionEvent>()

        val songs = repository.recognize(events::add)

        assertEquals("42", songs.single().neteaseSongId)
        assertEquals(listOf("Artist A", "Artist B"), songs.single().artists)
        assertEquals(1234L, songs.single().matchStartTimeMs)
        assertEquals(AudioRecognitionEvent.Capturing(1, 2_000), events[0])
        assertEquals(AudioRecognitionEvent.Matching(1), events[1])
        assertEquals(AudioRecognitionEvent.Success(songs), events[2])
    }

    @Test
    fun audioRecognitionPropagatesNativeErrorAndCancelsOutput() = runTest {
        val errorOutput = FakeAudioRecognitionOutput(error = "request timeout")
        val repository = IosAudioRecognitionRepository(errorOutput)

        val failure = runCatching { repository.recognize {} }.exceptionOrNull()

        assertEquals("request timeout", failure?.message)

        val waitingOutput = FakeAudioRecognitionOutput(waitForCancellation = true)
        val waitingRepository = IosAudioRecognitionRepository(waitingOutput)
        val job = launch { waitingRepository.recognize {} }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(waitingOutput.cancelled)
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

    private class FakeAudioRecognitionOutput(
        private val resultJson: String? = null,
        private val error: String? = null,
        private val waitForCancellation: Boolean = false,
    ) : IosAudioRecognitionOutput {
        var cancelled = false

        override fun hasPermission(): Boolean = true

        override fun requestPermission(completionHandler: (Boolean) -> Unit) {
            completionHandler(true)
        }

        override fun recognize(
            eventHandler: (String, String, String) -> Unit,
            completionHandler: (String?, String?) -> Unit,
        ) {
            if (waitForCancellation) return
            eventHandler("capturing", "1", "2000")
            eventHandler("matching", "1", "6000")
            completionHandler(resultJson, error)
        }

        override fun cancel() {
            cancelled = true
        }
    }
}
