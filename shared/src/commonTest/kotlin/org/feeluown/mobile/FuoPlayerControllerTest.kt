package org.feeluown.mobile

import kotlinx.coroutines.CompletableDeferred
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
    fun smartReplacementPayloadUpdatesCurrentPlaybackTrack() = runTest {
        val origin = providerTrack("provider:1", "First")
        val provider = FakeProviderRepository(
            listOf(origin),
            resolveHandler = { track, _ ->
                PlaybackPayload(
                    url = "https://example.com/replacement.mp3",
                    title = "Replacement",
                    artists = "Other Artist",
                    album = "Other Album",
                    source = "qqmusic",
                    providerName = "QQ 音乐",
                    isSmartReplacement = true,
                    originalTitle = track.title,
                    originalProviderName = track.providerName,
                )
            },
        )
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
            controller.onQueryChange("first")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()
            controller.playFromSearch(0)
            advanceUntilIdle()

            assertEquals("Replacement", engine.lastTrack?.title)
            assertEquals("QQ 音乐", engine.lastTrack?.providerName)
            assertEquals(true, engine.lastTrack?.isSmartReplacement)
            assertEquals("First", engine.lastTrack?.originalTitle)
            assertEquals("网易云音乐", engine.lastTrack?.originalProviderName)
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
    fun staleResolveResultDoesNotOverrideLatestNextSelection() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
            providerTrack("provider:3", "Third"),
        )
        val payloads = tracks.associate { track ->
            track.id to CompletableDeferred<PlaybackPayload>()
        }
        val provider = FakeProviderRepository(
            tracks = tracks,
            resolveHandler = { track, _ -> payloads.getValue(track.id).await() },
        )
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
            controller.next()
            advanceUntilIdle()

            payloads.getValue("provider:2").complete(payloadFor(tracks[1]))
            payloads.getValue("provider:1").complete(payloadFor(tracks[0]))
            advanceUntilIdle()

            assertEquals(null, engine.lastTrack)
            assertEquals("provider:3", controller.playbackState.currentTrack?.id)
            assertEquals(2, controller.playbackState.queueIndex)

            payloads.getValue("provider:3").complete(payloadFor(tracks[2]))
            advanceUntilIdle()

            assertEquals("provider:3", engine.lastTrack?.id)
            assertEquals(3, provider.resolveCount)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun skipUnavailablePolicyPlaysNextTrackWhenResolveMediaIsMissing() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val provider = FakeProviderRepository(
            tracks = tracks,
            resolveFailures = setOf("provider:1"),
        )
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                settingsStore = FakeSettingsStore(
                    AppSettings(unavailablePlaybackPolicy = UnavailablePlaybackPolicy.Skip),
                ),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onQueryChange("song")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()
            controller.playFromSearch(0)
            advanceUntilIdle()

            assertEquals("provider:2", engine.lastTrack?.id)
            assertEquals(PlayerStatus.Playing, controller.playbackState.status)
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
        val radioFeature = ProviderFeature(
            id = "netease_radio",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "私人 FM",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val dailyTracks = listOf(providerTrack("provider:1", "First"))
        val radioTracks = listOf(providerTrack("provider:2", "Radio"))
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(dailyFeature, playlistFeature, radioFeature),
            featureSections = mapOf(
                dailyFeature.id to ProviderContentSection(dailyFeature, tracks = dailyTracks),
                playlistFeature.id to ProviderContentSection(playlistFeature),
                radioFeature.id to ProviderContentSection(radioFeature, tracks = radioTracks),
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
            assertEquals(emptyList(), controller.recommendSections.first { it.feature.id == radioFeature.id }.tracks)

            controller.openFeature(dailyFeature)
            advanceUntilIdle()

            assertEquals(listOf(playlistFeature.id, dailyFeature.id), provider.loadedFeatureIds)
            assertEquals(dailyTracks, controller.selectedFeatureTracks)

            controller.openFeature(radioFeature)
            advanceUntilIdle()

            assertEquals(listOf(playlistFeature.id, dailyFeature.id, radioFeature.id), provider.loadedFeatureIds)
            assertEquals(radioTracks, controller.selectedFeatureTracks)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun loginRequiredDeferredHomeFeaturesFollowProviderAuthState() = runTest {
        val dailyFeature = ProviderFeature(
            id = "netease_daily_songs",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "每日推荐歌曲",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val radioFeature = ProviderFeature(
            id = "netease_radio",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "私人 FM",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(dailyFeature, radioFeature),
            initialIsLoggedIn = false,
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

            assertEquals(
                listOf(true, true),
                controller.recommendSections.map { it.isLoginRequired },
            )

            controller.loginProviderWithCookies("netease", """{"MUSIC_U":"saved"}""")
            advanceUntilIdle()

            assertEquals(
                listOf(false, false),
                controller.recommendSections.map { it.isLoginRequired },
            )

            controller.logoutProvider("netease")
            advanceUntilIdle()

            assertEquals(
                listOf(true, true),
                controller.recommendSections.map { it.isLoginRequired },
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun privateFmAppendsMoreTracksBeforeQueueEnds() = runTest {
        val radioFeature = ProviderFeature(
            id = "netease_radio",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "私人 FM",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val initialTracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
            providerTrack("provider:3", "Third"),
        )
        val nextTracks = listOf(
            providerTrack("provider:4", "Fourth"),
            providerTrack("provider:5", "Fifth"),
            providerTrack("provider:6", "Sixth"),
        )
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(radioFeature),
            featureSections = mapOf(radioFeature.id to ProviderContentSection(radioFeature, tracks = initialTracks)),
            additionalFeatureTracks = mapOf(radioFeature.id to nextTracks),
        )
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
            controller.openFeature(radioFeature)
            advanceUntilIdle()
            controller.playAllFromSelectedFeature()
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()

            assertEquals(
                listOf("provider:1", "provider:2", "provider:3", "provider:4", "provider:5", "provider:6"),
                controller.playbackState.queue.map { it.id },
            )
            assertEquals(listOf(radioFeature.id), provider.loadedMoreFeatureIds)

            controller.next()
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()

            assertEquals("provider:4", engine.lastTrack?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun playAllFromDeferredPrivateFmLoadsFeatureBeforePlaying() = runTest {
        val radioFeature = ProviderFeature(
            id = "netease_radio",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "私人 FM",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val radioTracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
            providerTrack("provider:3", "Third"),
        )
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(radioFeature),
            featureSections = mapOf(radioFeature.id to ProviderContentSection(radioFeature, tracks = radioTracks)),
        )
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
            assertEquals(emptyList(), controller.recommendSections.first { it.feature.id == radioFeature.id }.tracks)

            controller.playAllFromFeature(radioFeature.id)
            advanceUntilIdle()

            assertEquals("provider:1", engine.lastTrack?.id)
            assertEquals(radioTracks, controller.recommendSections.first { it.feature.id == radioFeature.id }.tracks)
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
    fun switchingToMineLoadsUserPlaylistSectionsByDefault() = runTest {
        val playlistFeature = ProviderFeature(
            id = "netease_user_playlists",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "我的歌单",
            category = ProviderFeatureCategory.MinePlaylists,
            contentType = ProviderContentType.Playlists,
            requiresLogin = true,
        )
        val favoritePlaylistFeature = ProviderFeature(
            id = "netease_favorite_playlists",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "收藏歌单",
            category = ProviderFeatureCategory.MineFavoritePlaylists,
            contentType = ProviderContentType.Playlists,
            requiresLogin = true,
        )
        val playlists = listOf(
            ProviderPlaylist(
                id = "playlist:netease:1",
                title = "我创建的歌单",
                providerId = "netease",
                providerName = "网易云音乐",
            ),
        )
        val favoritePlaylists = listOf(
            ProviderPlaylist(
                id = "playlist:netease:2",
                title = "我收藏的歌单",
                providerId = "netease",
                providerName = "网易云音乐",
            ),
        )
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(playlistFeature, favoritePlaylistFeature),
            featureSections = mapOf(
                playlistFeature.id to ProviderContentSection(playlistFeature, playlists = playlists),
                favoritePlaylistFeature.id to ProviderContentSection(
                    favoritePlaylistFeature,
                    playlists = favoritePlaylists,
                ),
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
            controller.onHomeSectionChange(HomeSection.Mine)
            advanceUntilIdle()

            assertEquals(MineSection.Playlists, controller.mineSection)
            assertEquals(listOf(playlistFeature.id, favoritePlaylistFeature.id), provider.loadedFeatureIds)
            assertEquals(playlists, controller.minePlaylistSections.first().playlists)
            assertEquals(favoritePlaylists, controller.mineFavoritePlaylistSections.first().playlists)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun switchingToMineLoadsFavoriteSections() = runTest {
        val favoriteFeature = ProviderFeature(
            id = "netease_favorite_songs",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "收藏歌曲",
            category = ProviderFeatureCategory.Mine,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val favoriteTracks = listOf(providerTrack("provider:1", "Favorite"))
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(favoriteFeature),
            featureSections = mapOf(favoriteFeature.id to ProviderContentSection(favoriteFeature, tracks = favoriteTracks)),
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
            controller.onHomeSectionChange(HomeSection.Mine)
            controller.onMineSectionChange(MineSection.Favorites)
            advanceUntilIdle()

            assertEquals(listOf(favoriteFeature.id), provider.loadedFeatureIds)
            assertEquals(favoriteTracks, controller.mineSections.first().tracks)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun mineFavoriteSectionsFollowProviderAuthState() = runTest {
        val favoriteFeature = ProviderFeature(
            id = "netease_favorite_songs",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "收藏歌曲",
            category = ProviderFeatureCategory.Mine,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(favoriteFeature),
            initialIsLoggedIn = false,
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
            controller.onHomeSectionChange(HomeSection.Mine)
            controller.onMineSectionChange(MineSection.Favorites)
            advanceUntilIdle()

            assertEquals(true, controller.mineSections.first().isLoginRequired)

            controller.loginProviderWithCookies("netease", """{"MUSIC_U":"saved"}""")
            advanceUntilIdle()

            assertEquals(false, controller.mineSections.first().isLoginRequired)

            controller.logoutProvider("netease")
            advanceUntilIdle()

            assertEquals(true, controller.mineSections.first().isLoginRequired)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun mediaItemDetailLoadsTracksAndCanPlayAll() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val mediaItem = ProviderMediaItem(
            id = "artist:netease:1",
            title = "Artist",
            providerId = "netease",
            providerName = "网易云音乐",
            type = ProviderMediaItemType.Artist,
        )
        val provider = FakeProviderRepository(emptyList(), mediaItemTracks = tracks)
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
            controller.openMediaItem(mediaItem)
            advanceUntilIdle()
            controller.playAllFromSelectedMediaItem()
            advanceUntilIdle()

            assertEquals(listOf(mediaItem.id), provider.loadedMediaItemIds)
            assertEquals(tracks, controller.selectedMediaItemTracks)
            assertEquals("provider:1", engine.lastTrack?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun restoresAndPersistsSettings() = runTest {
        val store = FakeSettingsStore(
            AppSettings(
                homeSection = HomeSection.Mine,
                mineSection = MineSection.LocalMusic,
                localMusicViewMode = LocalMusicViewMode.Album,
                excludedLocalMusicDirectoryIds = setOf("Podcasts/"),
                localMusicMinDurationSeconds = 30,
                providerLoginMode = ProviderLoginMode.Cookie,
                providerCookieInputs = mapOf("netease" to """{"MUSIC_U":"saved"}"""),
                providerHeaderInputs = mapOf(
                    "ytmusic" to ProviderHeaderInput(
                        authorization = "SAPISIDHASH saved",
                        cookie = "SID=saved",
                    ),
                ),
                enabledProviderIds = setOf("netease", "ytmusic"),
                providerOrderIds = listOf("ytmusic", "netease", "qqmusic", "bilibili"),
                audioCacheLimitMb = 256,
                imageCacheLimitMb = 64,
                wifiAudioQualityPolicy = AudioQualityPolicy.Highest,
                cellularAudioQualityPolicy = AudioQualityPolicy.Low,
                unavailablePlaybackPolicy = UnavailablePlaybackPolicy.Skip,
            ),
        )
        val provider = FakeProviderRepository(emptyList())
        val local = FakeLocalMusicRepository(
            directories = listOf(LocalMusicDirectory("Podcasts/", "Podcasts", 2)),
        )
        val cache = FakeResourceCacheRepository()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                settingsStore = store,
                resourceCacheRepository = cache,
                scope = controllerScope,
            )

            advanceUntilIdle()

            assertEquals(HomeSection.Mine, controller.homeSection)
            assertEquals(MineSection.LocalMusic, controller.mineSection)
            assertEquals(LocalMusicViewMode.Album, controller.localMusicViewMode)
            assertEquals(setOf("Podcasts/"), controller.excludedLocalMusicDirectoryIds)
            assertEquals(30, controller.localMusicMinDurationSeconds)
            assertEquals(LocalMusicScanSettings(setOf("Podcasts/"), 30), local.lastSettings)
            assertEquals(ProviderLoginMode.Cookie, controller.providerLoginMode)
            assertEquals("""{"MUSIC_U":"saved"}""", controller.cookieInputFor("netease"))
            assertEquals(setOf("netease", "ytmusic"), controller.enabledProviderIds)
            assertEquals(listOf("ytmusic", "netease"), controller.providers.map { it.providerId })
            assertEquals(
                ProviderHeaderInput("SAPISIDHASH saved", "SID=saved"),
                controller.providerHeaderInputFor("ytmusic"),
            )
            assertEquals(256, controller.audioCacheLimitMb)
            assertEquals(64, controller.imageCacheLimitMb)
            assertEquals(CacheLimit(256L * 1024L * 1024L, 64L * 1024L * 1024L), cache.lastLimit)
            assertEquals(AudioQualityPolicy.Highest, controller.wifiAudioQualityPolicy)
            assertEquals(AudioQualityPolicy.Low, controller.cellularAudioQualityPolicy)
            assertEquals(UnavailablePlaybackPolicy.Skip, controller.unavailablePlaybackPolicy)
            assertEquals(AudioQualityPolicy.Highest, provider.lastWifiAudioQualityPolicy)
            assertEquals(AudioQualityPolicy.Low, provider.lastCellularAudioQualityPolicy)

            controller.onProviderLoginModeChange(ProviderLoginMode.WebView)
            controller.onProviderCookiesChange("netease", """{"MUSIC_U":"draft"}""")
            controller.onProviderHeaderAuthorizationChange("ytmusic", "SAPISIDHASH draft")
            controller.onProviderHeaderCookieChange("ytmusic", "SID=draft")
            controller.onMineSectionChange(MineSection.Favorites)
            controller.onLocalMusicDirectoryEnabledChange("Podcasts/", enabled = true)
            controller.onLocalMusicMinDurationChange(60)
            controller.onAudioCacheLimitChange(1024)
            controller.onImageCacheLimitChange(256)
            controller.onWifiAudioQualityPolicyChange(AudioQualityPolicy.High)
            controller.onCellularAudioQualityPolicyChange(AudioQualityPolicy.Standard)
            controller.onUnavailablePlaybackPolicyChange(UnavailablePlaybackPolicy.SmartReplace)
            advanceUntilIdle()

            assertEquals(ProviderLoginMode.WebView, store.saved.providerLoginMode)
            assertEquals("""{"MUSIC_U":"draft"}""", store.saved.providerCookieInputs["netease"])
            assertEquals(
                ProviderHeaderInput("SAPISIDHASH draft", "SID=draft"),
                store.saved.providerHeaderInputs["ytmusic"],
            )
            assertEquals(setOf("netease", "ytmusic"), store.saved.enabledProviderIds)
            assertEquals(listOf("ytmusic", "netease", "qqmusic", "bilibili"), store.saved.providerOrderIds)
            assertEquals(MineSection.Favorites, store.saved.mineSection)
            assertEquals(emptySet(), store.saved.excludedLocalMusicDirectoryIds)
            assertEquals(60, store.saved.localMusicMinDurationSeconds)
            assertEquals(LocalMusicScanSettings(emptySet(), 60), local.lastSettings)
            assertEquals(1024, store.saved.audioCacheLimitMb)
            assertEquals(256, store.saved.imageCacheLimitMb)
            assertEquals(CacheLimit(1024L * 1024L * 1024L, 256L * 1024L * 1024L), cache.lastLimit)
            assertEquals(AudioQualityPolicy.High, store.saved.wifiAudioQualityPolicy)
            assertEquals(AudioQualityPolicy.Standard, store.saved.cellularAudioQualityPolicy)
            assertEquals(UnavailablePlaybackPolicy.SmartReplace, store.saved.unavailablePlaybackPolicy)
            assertEquals(AudioQualityPolicy.High, provider.lastWifiAudioQualityPolicy)
            assertEquals(AudioQualityPolicy.Standard, provider.lastCellularAudioQualityPolicy)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun providerSwitchesDefaultToNeteaseAndPersistChanges() = runTest {
        val store = FakeSettingsStore()
        val provider = FakeProviderRepository(emptyList())
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                settingsStore = store,
                scope = controllerScope,
            )

            advanceUntilIdle()

            assertEquals(setOf("netease"), controller.enabledProviderIds)
            assertEquals(listOf("netease"), controller.providers.map { it.providerId })
            assertEquals(listOf("netease", "qqmusic", "bilibili", "ytmusic"), controller.availableProviders.map { it.providerId })
            assertEquals(setOf("netease"), provider.lastEnabledProviderIds)

            controller.onProviderEnabledChange("ytmusic", enabled = true)
            advanceUntilIdle()

            assertEquals(setOf("netease", "ytmusic"), controller.enabledProviderIds)
            assertEquals(listOf("netease", "ytmusic"), controller.providers.map { it.providerId })
            assertEquals(setOf("netease", "ytmusic"), store.saved.enabledProviderIds)
            assertEquals(setOf("netease", "ytmusic"), provider.lastEnabledProviderIds)

            controller.moveProvider("ytmusic", -3)
            advanceUntilIdle()

            assertEquals(listOf("ytmusic", "netease"), controller.providers.map { it.providerId })
            assertEquals(listOf("ytmusic", "netease", "qqmusic", "bilibili"), store.saved.providerOrderIds)

            controller.onSearchProviderChange("ytmusic")
            controller.onProviderEnabledChange("ytmusic", enabled = false)
            advanceUntilIdle()

            assertEquals(setOf("netease"), controller.enabledProviderIds)
            assertEquals(listOf("netease"), controller.providers.map { it.providerId })
            assertEquals(SearchScope.All, controller.searchScope)
            assertEquals(null, controller.selectedSearchProviderId)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun providerSectionsFollowConfiguredOrder() = runTest {
        val neteaseFeature = ProviderFeature(
            id = "netease_recommend",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "网易推荐",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Playlists,
            requiresLogin = false,
        )
        val ytmusicFeature = ProviderFeature(
            id = "ytmusic_recommend",
            providerId = "ytmusic",
            providerName = "YouTube Music",
            title = "YT 推荐",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Playlists,
            requiresLogin = false,
        )
        val store = FakeSettingsStore(
            AppSettings(
                enabledProviderIds = setOf("netease", "ytmusic"),
                providerOrderIds = listOf("ytmusic", "netease", "qqmusic", "bilibili"),
            ),
        )
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(neteaseFeature, ytmusicFeature),
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                settingsStore = store,
                scope = controllerScope,
            )

            advanceUntilIdle()

            assertEquals(
                listOf("ytmusic_recommend", "netease_recommend"),
                controller.recommendSections.map { it.feature.id },
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun logoutProviderClearsAuthAndCookieDraft() = runTest {
        val store = FakeSettingsStore(
            AppSettings(providerCookieInputs = mapOf("netease" to """{"MUSIC_U":"draft"}""")),
        )
        val provider = FakeProviderRepository(emptyList())
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                settingsStore = store,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.loginProviderWithCookies("netease", """{"MUSIC_U":"saved"}""")
            advanceUntilIdle()
            assertEquals(true, controller.authStateFor(ProviderInfo("netease", "网易云音乐")).isLoggedIn)

            controller.onProviderCookiesChange("netease", """{"MUSIC_U":"draft"}""")
            controller.logoutProvider("netease")
            advanceUntilIdle()

            assertEquals(1, provider.logoutCount)
            assertEquals(false, controller.authStateFor(ProviderInfo("netease", "网易云音乐")).isLoggedIn)
            assertEquals("", controller.cookieInputFor("netease"))
            assertEquals(null, store.saved.providerCookieInputs["netease"])
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

    private fun payloadFor(track: MusicTrack): PlaybackPayload = PlaybackPayload(
        url = "https://example.com/${track.id}.mp3",
        title = track.title,
        artists = track.artists,
        album = track.album,
        source = track.source,
    )

    private class FakeProviderRepository(
        private val tracks: List<MusicTrack>,
        private val playlistTracks: List<MusicTrack> = emptyList(),
        private val mediaItemTracks: List<MusicTrack> = emptyList(),
        private val features: List<ProviderFeature> = emptyList(),
        private val featureSections: Map<String, ProviderContentSection> = emptyMap(),
        private val additionalFeatureTracks: Map<String, List<MusicTrack>> = emptyMap(),
        private val resolveFailures: Set<String> = emptySet(),
        private val resolveHandler: (suspend (MusicTrack, UnavailablePlaybackPolicy) -> PlaybackPayload)? = null,
        private val availableProviderInfos: List<ProviderInfo> = listOf(
            ProviderInfo(providerId = "netease", providerName = "网易云音乐"),
            ProviderInfo(providerId = "qqmusic", providerName = "QQ 音乐"),
            ProviderInfo(providerId = "bilibili", providerName = "哔哩哔哩"),
            ProviderInfo(
                providerId = "ytmusic",
                providerName = "YouTube Music",
                supportedLoginModes = setOf(ProviderLoginMode.Headers),
            ),
        ),
        initialIsLoggedIn: Boolean = true,
    ) : ProviderMusicRepository {
        private var isLoggedIn = initialIsLoggedIn
        private var enabledProviderIds = DEFAULT_ENABLED_PROVIDER_IDS
        var resolveCount = 0
        var logoutCount = 0
        var lastEnabledProviderIds: Set<String>? = null
        var lastWifiAudioQualityPolicy: AudioQualityPolicy? = null
        var lastCellularAudioQualityPolicy: AudioQualityPolicy? = null
        val loadedFeatureIds = mutableListOf<String>()
        val loadedMoreFeatureIds = mutableListOf<String>()
        val loadedMediaItemIds = mutableListOf<String>()

        override suspend fun initialize() = Unit

        override suspend fun availableProviders(): List<ProviderInfo> = availableProviderInfos

        override suspend fun updateEnabledProviders(providerIds: Set<String>) {
            enabledProviderIds = providerIds
            lastEnabledProviderIds = providerIds
        }

        override suspend fun providers(): List<ProviderInfo> =
            availableProviderInfos.filter { it.providerId in enabledProviderIds }

        override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> = tracks

        override suspend fun resolve(
            track: MusicTrack,
            unavailablePolicy: UnavailablePlaybackPolicy,
        ): PlaybackPayload {
            resolveCount += 1
            if (track.id in resolveFailures) {
                throw RuntimeException("media not found: ${track.id}")
            }
            return resolveHandler?.invoke(track, unavailablePolicy) ?: PlaybackPayload(
                url = "https://example.com/${track.id}.mp3",
                title = track.title,
                artists = track.artists,
                album = track.album,
                source = track.source,
            )
        }

        override suspend fun authState(providerId: String): ProviderAuthState =
            ProviderAuthState(
                providerId = providerId,
                providerName = providerId,
                isLoggedIn = isLoggedIn,
                userName = "tester".takeIf { isLoggedIn },
            )

        override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState {
            isLoggedIn = true
            return authState(providerId)
        }

        override suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState {
            isLoggedIn = true
            return authState(providerId)
        }

        override suspend fun logout(providerId: String): ProviderAuthState {
            logoutCount += 1
            isLoggedIn = false
            return authState(providerId)
        }

        override suspend fun updateAudioQualityPolicies(
            wifiPolicy: AudioQualityPolicy,
            cellularPolicy: AudioQualityPolicy,
        ) {
            lastWifiAudioQualityPolicy = wifiPolicy
            lastCellularAudioQualityPolicy = cellularPolicy
        }

        override suspend fun features(): List<ProviderFeature> = features

        override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection {
            loadedFeatureIds += feature.id
            return featureSections[feature.id] ?: ProviderContentSection(feature)
        }

        override suspend fun loadMoreFeatureTracks(feature: ProviderFeature): List<MusicTrack> {
            loadedMoreFeatureIds += feature.id
            return additionalFeatureTracks[feature.id] ?: loadFeature(feature).tracks
        }

        override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = playlistTracks

        override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> {
            loadedMediaItemIds += item.id
            return mediaItemTracks
        }
    }

    private class FakeLocalMusicRepository(
        private val directories: List<LocalMusicDirectory> = emptyList(),
    ) : LocalMusicRepository {
        var lastSettings = LocalMusicScanSettings()

        override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
            lastSettings = settings
        }

        override suspend fun directories(): List<LocalMusicDirectory> = directories

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
                audioQuality = payload.audioQuality,
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
