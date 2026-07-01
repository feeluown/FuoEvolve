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

    @Test
    fun dailyRecommendSongsLoadOnlyAfterOpeningFeature() = runTest {
        val dailyFeature = ProviderFeature(
            id = "netease_daily_songs",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "每日推荐歌曲",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val playlistFeature = ProviderFeature(
            id = "netease_daily_playlists",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "推荐歌单",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Playlists,
            requiresLogin = true,
        )
        val dailyTracks = listOf(providerTrack("provider:1", "First"))
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(dailyFeature, playlistFeature),
            featureSections = mapOf(
                dailyFeature.id to ProviderContentSection(dailyFeature, tracks = dailyTracks),
                playlistFeature.id to ProviderContentSection(playlistFeature),
            ),
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()

            assertEquals(listOf(playlistFeature.id), provider.loadedFeatureIds)
            assertEquals(emptyList(), controller.recommendSections.first { it.feature.id == dailyFeature.id }.tracks)

            controller.openFeature(dailyFeature)
            advanceUntilIdle()

            assertEquals(listOf(playlistFeature.id, dailyFeature.id), provider.loadedFeatureIds)
            assertEquals(dailyTracks, controller.selectedFeatureTracks)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun navigateBackClosesSearchBeforeLeavingApp() = runTest {
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.openSearch()

            assertEquals(true, controller.canNavigateBack)
            assertEquals(true, controller.navigateBack())
            assertEquals(false, controller.canNavigateBack)
            assertEquals(false, controller.navigateBack())
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun navigateBackClosesQueueBeforeFullPlayer() = runTest {
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.openFullPlayer()
            controller.toggleQueue()

            assertEquals(true, controller.navigateBack())
            assertEquals(true, controller.canNavigateBack)
            assertEquals(true, controller.navigateBack())
            assertEquals(false, controller.canNavigateBack)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun restoresAndPersistsSettings() = runTest {
        val store = FakeSettingsStore(
            AppSettings(
                homeSection = HomeSection.Local,
                localMusicViewMode = LocalMusicViewMode.Album,
                providerLoginMode = ProviderLoginMode.Cookie,
                providerCookieInputs = mapOf("netease" to """{"MUSIC_U":"saved"}"""),
                audioCacheLimitMb = 256,
                imageCacheLimitMb = 64,
            ),
        )
        val cache = FakeResourceCacheRepository()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                settingsStore = store,
                resourceCacheRepository = cache,
                scope = controllerScope,
            )

            advanceUntilIdle()

            assertEquals(HomeSection.Local, controller.homeSection)
            assertEquals(LocalMusicViewMode.Album, controller.localMusicViewMode)
            assertEquals(ProviderLoginMode.Cookie, controller.providerLoginMode)
            assertEquals("""{"MUSIC_U":"saved"}""", controller.cookieInputFor("netease"))
            assertEquals(256, controller.audioCacheLimitMb)
            assertEquals(64, controller.imageCacheLimitMb)
            assertEquals(CacheLimit(256L * 1024L * 1024L, 64L * 1024L * 1024L), cache.lastLimit)

            controller.onProviderLoginModeChange(ProviderLoginMode.WebView)
            controller.onProviderCookiesChange("netease", """{"MUSIC_U":"draft"}""")
            controller.onAudioCacheLimitChange(1024)
            controller.onImageCacheLimitChange(256)
            advanceUntilIdle()

            assertEquals(ProviderLoginMode.WebView, store.saved.providerLoginMode)
            assertEquals("""{"MUSIC_U":"draft"}""", store.saved.providerCookieInputs["netease"])
            assertEquals(1024, store.saved.audioCacheLimitMb)
            assertEquals(256, store.saved.imageCacheLimitMb)
            assertEquals(CacheLimit(1024L * 1024L * 1024L, 256L * 1024L * 1024L), cache.lastLimit)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun clearResourceCacheRefreshesUsageAndMessage() = runTest {
        val cache = FakeResourceCacheRepository(
            CacheUsage(
                audioBytes = 10L * 1024L * 1024L,
                imageBytes = 2L * 1024L * 1024L,
            ),
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                resourceCacheRepository = cache,
                scope = controllerScope,
            )

            advanceUntilIdle()
            assertEquals(12L * 1024L * 1024L, controller.cacheUsage.totalBytes)

            controller.clearResourceCache()
            advanceUntilIdle()

            assertEquals(1, cache.clearCount)
            assertEquals(CacheUsage(), controller.cacheUsage)
            assertEquals("缓存已清空", controller.message)
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
        private val features: List<ProviderFeature> = emptyList(),
        private val featureSections: Map<String, ProviderContentSection> = emptyMap(),
    ) : ProviderMusicRepository {
        var resolveCount = 0
        val loadedFeatureIds = mutableListOf<String>()

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

        override suspend fun features(): List<ProviderFeature> = features

        override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection {
            loadedFeatureIds += feature.id
            return featureSections[feature.id] ?: ProviderContentSection(feature)
        }

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

    private class FakeSettingsStore(initial: AppSettings = AppSettings()) : AppSettingsStore {
        var saved = initial
            private set

        override suspend fun load(): AppSettings = saved

        override suspend fun save(settings: AppSettings) {
            saved = settings
        }
    }

    private class FakeResourceCacheRepository(
        initialUsage: CacheUsage = CacheUsage(),
    ) : ResourceCacheRepository {
        private val mutableUsage = MutableStateFlow(initialUsage)
        override val usage = mutableUsage
        var lastLimit: CacheLimit? = null
        var clearCount = 0

        override suspend fun refreshUsage() = Unit

        override suspend fun clearAll() {
            clearCount += 1
            mutableUsage.value = CacheUsage()
        }

        override suspend fun updateLimit(limit: CacheLimit) {
            lastLimit = limit
        }
    }
}
