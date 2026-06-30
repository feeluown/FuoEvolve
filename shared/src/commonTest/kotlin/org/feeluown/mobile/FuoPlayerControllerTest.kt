package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class FuoPlayerControllerTest {
    @Test
    fun downloadedProviderTrackUsesLocalPayload() = runTest {
        val provider = FakeProviderRepository(listOf(providerTrack("provider:1", "First")))
        val downloads = FakeDownloadRepository(
            mapOf("provider:1" to DownloadState.Downloaded("content://downloads/first")),
        )
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = downloads,
                playbackEngine = engine,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onQueryChange("first")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()
            controller.playFromSearch(0)
            advanceUntilIdle()

            assertEquals("content://downloads/first", engine.lastPayload?.url)
            assertEquals(0, provider.resolveCount)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun nextUsesKotlinQueueOrder() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val provider = FakeProviderRepository(tracks)
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onQueryChange("song")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()
            controller.playFromSearch(0)
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()

            assertEquals("provider:2", engine.lastTrack?.id)
            assertEquals(2, provider.resolveCount)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun playAllFromSelectedPlaylistStartsFirstTrackAndKeepsQueue() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val provider = FakeProviderRepository(emptyList(), playlistTracks = tracks)
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.openPlaylist(
                ProviderPlaylist(
                    id = "playlist:netease:1",
                    title = "榜单",
                    providerId = "netease",
                    providerName = "网易云音乐",
                ),
            )
            advanceUntilIdle()
            controller.playAllFromSelectedPlaylist()
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()

            assertEquals("provider:2", engine.lastTrack?.id)
            assertEquals(2, provider.resolveCount)
        } finally {
            controllerScope.cancel()
        }
    }

    private fun providerTrack(id: String, title: String): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        providerId = id,
        providerName = "网易云音乐",
    )

    private class FakeProviderRepository(
        private val tracks: List<MusicTrack>,
        private val playlistTracks: List<MusicTrack> = emptyList(),
    ) : ProviderMusicRepository {
        var resolveCount = 0

        override suspend fun initialize() = Unit

        override suspend fun providers(): List<ProviderInfo> = listOf(
            ProviderInfo(providerId = "netease", providerName = "网易云音乐"),
        )

        override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> = tracks

        override suspend fun resolve(track: MusicTrack): PlaybackPayload {
            resolveCount += 1
            return PlaybackPayload(
                url = "https://example.com/${track.id}.mp3",
                title = track.title,
                artists = track.artists,
                album = track.album,
                source = track.source,
            )
        }

        override suspend fun authState(providerId: String): ProviderAuthState = ProviderAuthState(
            providerId = providerId,
            providerName = providerId,
            isLoggedIn = false,
        )

        override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
            ProviderAuthState(
                providerId = providerId,
                providerName = providerId,
                isLoggedIn = true,
                userName = "tester",
            )

        override suspend fun features(): List<ProviderFeature> = emptyList()

        override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
            ProviderContentSection(feature)

        override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = playlistTracks
    }

    private class FakeLocalMusicRepository : LocalMusicRepository {
        override suspend fun scan(): List<MusicTrack> = emptyList()

        override suspend fun search(keyword: String): List<MusicTrack> = emptyList()
    }

    private class FakeDownloadRepository(
        initialStates: Map<String, DownloadState>,
    ) : DownloadRepository {
        private val mutableStates = MutableStateFlow(initialStates)
        override val states = mutableStates

        override suspend fun load() = Unit

        override suspend fun download(track: MusicTrack) = Unit

        override suspend fun deleteDownloaded(track: MusicTrack) = Unit
    }

    private class FakePlaybackEngine : PlaybackEngine {
        private val mutableState = MutableStateFlow(PlaybackState())
        override val state = mutableState
        var lastTrack: MusicTrack? = null
        var lastPayload: PlaybackPayload? = null

        override fun play(track: MusicTrack, payload: PlaybackPayload) {
            lastTrack = track
            lastPayload = payload
            mutableState.value = PlaybackState(
                status = PlayerStatus.Playing,
                currentTrack = track,
                durationMs = payload.durationMs ?: 0,
                lyrics = payload.lyrics,
            )
        }

        override fun pause() {
            mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
        }

        override fun resume() {
            mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
        }

        override fun stop() {
            mutableState.value = PlaybackState()
        }

        override fun seekTo(positionMs: Long) {
            mutableState.value = mutableState.value.copy(positionMs = positionMs)
        }
    }
}
