package org.feeluown.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FuoPlayerControllerTest {
    @Test
    fun providerTrackSharePayloadUsesFuoUriAndProviderUrl() {
        val track = providerTrack("netease:1811961337", "Igallta").copy(
            artists = "Se-U-Ra",
            album = "Igallta",
            providerUrl = "https://y.music.163.com/m/song?id=1811961337",
        )

        val payload = track.toSharePayload()

        assertEquals("fuo://netease/songs/1811961337", payload?.fuoUri)
        assertEquals("https://feeluown.github.io/FuoEvolve/r/netease/songs/1811961337", payload?.appLinkUrl)
        assertEquals("https://y.music.163.com/m/song?id=1811961337", payload?.content(ShareMode.ProviderLink))
        assertEquals(
            "https://feeluown.github.io/FuoEvolve/r/netease/songs/1811961337",
            payload?.content(ShareMode.FuoLink),
        )
        assertEquals(
            "分享一首歌：\n" +
                "《Igallta》 - Se-U-Ra（专辑：Igallta）\n" +
                "打开收听：https://feeluown.github.io/FuoEvolve/r/netease/songs/1811961337\n" +
                "也可以在网易云音乐收听：https://y.music.163.com/m/song?id=1811961337",
            payload?.content(ShareMode.FullText),
        )
    }

    @Test
    fun sharePayloadWithoutProviderUrlDisablesProviderLinkOnly() {
        val payload = ProviderPlaylist(
            id = "playlist:netease:123",
            title = "每日推荐",
            providerId = "netease",
            providerName = "网易云音乐",
        ).toSharePayload()

        assertEquals("fuo://netease/playlists/123", payload?.fuoUri)
        assertEquals("https://feeluown.github.io/FuoEvolve/r/netease/playlists/123", payload?.content(ShareMode.FuoLink))
        assertNull(payload?.content(ShareMode.ProviderLink))
        assertEquals(
            "分享一个歌单：\n" +
                "《每日推荐》\n" +
                "打开查看：https://feeluown.github.io/FuoEvolve/r/netease/playlists/123",
            payload?.content(ShareMode.FullText),
        )
    }

    @Test
    fun parseSharedResourceSupportsCanonicalShortSongAndAppLinkUris() {
        val canonical = parseSharedResource("复制到 FuoEvolve：fuo://netease/albums/456")
        val shortSong = parseSharedResource("fuo://netease/1811961337")
        val appLink = parseSharedResource("https://feeluown.github.io/FuoEvolve/r/qqmusic/songs/abc")

        assertEquals(ShareResourceType.Album, canonical?.type)
        assertEquals("netease", canonical?.providerId)
        assertEquals("456", canonical?.identifier)
        assertEquals(ShareResourceType.Song, shortSong?.type)
        assertEquals("1811961337", shortSong?.identifier)
        assertEquals(ShareResourceType.Song, appLink?.type)
        assertEquals("qqmusic", appLink?.providerId)
        assertEquals("abc", appLink?.identifier)
    }

    @Test
    fun openSharedSongEnablesProviderAndLoadsTrack() = runTest {
        val providerTrack = providerTrack("qqmusic:abc", "Shared Song").copy(
            source = "qqmusic",
            providerName = "QQ 音乐",
        )
        val provider = FakeProviderRepository(listOf(providerTrack))
        val settings = FakeSettingsStore(AppSettings(enabledProviderIds = setOf("netease")))
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                settingsStore = settings,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.openSharedResource("fuo://qqmusic/songs/abc")
            advanceUntilIdle()

            assertEquals("qqmusic:abc", provider.lastTrackDetailId)
            assertEquals("Shared Song", controller.selectedTrack?.title)
            assertEquals(setOf("netease", "qqmusic"), provider.lastEnabledProviderIds)
            assertEquals(setOf("netease", "qqmusic"), settings.saved.enabledProviderIds)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun invalidSharedResourceDoesNotNavigate() = runTest {
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
            controller.openSharedResource("hello")
            advanceUntilIdle()

            assertNull(controller.selectedTrack)
            assertNull(controller.selectedPlaylist)
            assertNull(controller.selectedMediaItem)
            assertEquals("无法识别分享链接", controller.message)
        } finally {
            controllerScope.cancel()
        }
    }

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
    fun providerSearchAllStoresTypedResults() = runTest {
        val song = providerTrack("netease:1", "Song")
        val playlist = ProviderPlaylist("playlist:netease:10", "Playlist", "netease", "网易云音乐")
        val artist = ProviderMediaItem("artist:netease:20", "Artist", "netease", "网易云音乐", ProviderMediaItemType.Artist)
        val album = ProviderMediaItem("album:netease:30", "Album", "netease", "网易云音乐", ProviderMediaItemType.Album)
        val video = ProviderVideo("video:netease:40", "MV", providerId = "netease", providerName = "网易云音乐")
        val provider = FakeProviderRepository(
            tracks = listOf(song),
            searchResults = ProviderSearchResults(
                tracks = listOf(song),
                playlists = listOf(playlist),
                artists = listOf(artist),
                albums = listOf(album),
                videos = listOf(video),
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
            controller.onQueryChange("song")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()

            assertEquals(listOf(song), controller.searchResults)
            assertEquals(listOf(playlist), controller.providerSearchResults.playlists)
            assertEquals(listOf(artist), controller.providerSearchResults.artists)
            assertEquals(listOf(album), controller.providerSearchResults.albums)
            assertEquals(listOf(video), controller.providerSearchResults.videos)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun openTrackDetailLoadsRelatedContentAndVideo() = runTest {
        val song = providerTrack("netease:1", "Song")
        val similar = providerTrack("netease:2", "Similar")
        val comment = ProviderComment("comment1", "Listener", "Nice", likedCount = 8)
        val video = ProviderVideo("video:netease:40", "MV", providerId = "netease", providerName = "网易云音乐")
        val provider = FakeProviderRepository(
            tracks = listOf(song, similar),
            similarTracks = listOf(similar),
            comments = listOf(comment),
            trackVideo = video,
            videoPayload = VideoPlaybackPayload(video = video, url = "https://example.com/mv.mp4"),
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
            controller.openTrackDetail(song)
            advanceUntilIdle()
            controller.openSelectedTrackVideo()
            advanceUntilIdle()

            assertEquals(listOf(similar), controller.selectedTrackSimilar)
            assertEquals(listOf(comment), controller.selectedTrackComments)
            assertEquals(video, controller.selectedTrackVideo)
            assertEquals("https://example.com/mv.mp4", controller.selectedVideoPayload?.url)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun smartReplacementPayloadUpdatesCurrentPlaybackTrack() = runTest {
        val origin = providerTrack("provider:1", "First")
        val provider = FakeProviderRepository(
            listOf(origin),
            resolveHandler = { track, _, _, _, _, _ ->
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
    fun smartReplacementUsesConfiguredProviderIds() = runTest {
        val provider = FakeProviderRepository(listOf(providerTrack("provider:1", "First")))
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                settingsStore = FakeSettingsStore(
                    AppSettings(
                        enabledProviderIds = setOf("netease", "qqmusic", "bilibili"),
                        smartReplacementProviderIds = setOf("qqmusic", "bilibili"),
                    ),
                ),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onQueryChange("first")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()
            controller.playFromSearch(0)
            advanceUntilIdle()

            assertEquals(setOf("qqmusic", "bilibili"), provider.lastSmartReplacementProviderIds)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun smartReplacementUsesConfiguredMinScore() = runTest {
        val provider = FakeProviderRepository(listOf(providerTrack("provider:1", "First")))
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                settingsStore = FakeSettingsStore(
                    AppSettings(smartReplacementMinScore = 0.75),
                ),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onQueryChange("first")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()
            controller.playFromSearch(0)
            advanceUntilIdle()

            assertEquals(0.75, provider.lastSmartReplacementMinScore)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun smartReplacementCanUseReplacementSourceOptions() = runTest {
        val provider = FakeProviderRepository(listOf(providerTrack("provider:1", "First")))
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                settingsStore = FakeSettingsStore(
                    AppSettings(
                        smartReplacementUseReplacementMetadata = true,
                        smartReplacementUseReplacementLyrics = true,
                    ),
                ),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onQueryChange("first")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()
            controller.playFromSearch(0)
            advanceUntilIdle()

            assertEquals(false, provider.lastSmartReplacementUseOriginalMetadata)
            assertEquals(false, provider.lastSmartReplacementUseOriginalLyrics)
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
    fun multiPartTrackPlaysPartsInOrderBeforeMainQueueNextWhenShuffleIsEnabled() = runTest {
        val parent = providerTrack("bilibili:BV1xx", "Multi Part").copy(
            source = "bilibili",
            providerName = "哔哩哔哩",
        )
        val other = providerTrack("provider:2", "Second")
        val parts = listOf(
            PlaybackPart("bilibili:paged_BV1xx__1", "P1", 60_000),
            PlaybackPart("bilibili:paged_BV1xx__2", "P2", 70_000),
        )
        val provider = FakeProviderRepository(
            tracks = listOf(parent, other),
            resolveHandler = { track, _, _, _, _, _ ->
                val partIndex = parts.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
                PlaybackPayload(
                    url = "https://example.com/${parts[partIndex].id}.m4s",
                    title = parts[partIndex].title,
                    artists = parent.artists,
                    album = parent.album,
                    source = parent.source,
                    providerName = parent.providerName,
                    durationMs = parts[partIndex].durationMs,
                    parts = parts,
                    currentPartIndex = partIndex,
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
            controller.toggleShuffle()
            controller.playLocalTrack(parent, listOf(parent, other))
            advanceUntilIdle()

            assertEquals("bilibili:BV1xx", engine.lastTrack?.id)
            assertEquals(0, controller.playbackState.currentPartIndex)
            assertEquals(parts, controller.playbackState.playbackParts)

            engine.emitEnded(engine.lastTrack ?: parent)
            advanceUntilIdle()

            assertEquals("bilibili:BV1xx", engine.lastTrack?.id)
            assertEquals("https://example.com/bilibili:paged_BV1xx__2.m4s", engine.lastPayload?.url)
            assertEquals(1, controller.playbackState.currentPartIndex)
            assertEquals("bilibili:BV1xx", controller.playbackState.queue.first().id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun singleRepeatCyclesInsideMultiPartTrack() = runTest {
        val parent = providerTrack("bilibili:BV1xx", "Multi Part").copy(
            source = "bilibili",
            providerName = "哔哩哔哩",
        )
        val other = providerTrack("provider:2", "Second")
        val parts = listOf(
            PlaybackPart("bilibili:paged_BV1xx__1", "P1", 60_000),
            PlaybackPart("bilibili:paged_BV1xx__2", "P2", 70_000),
        )
        val provider = FakeProviderRepository(
            tracks = listOf(parent, other),
            resolveHandler = { track, _, _, _, _, _ ->
                val partIndex = parts.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
                PlaybackPayload(
                    url = "https://example.com/${parts[partIndex].id}.m4s",
                    title = parts[partIndex].title,
                    artists = parent.artists,
                    album = parent.album,
                    source = parent.source,
                    providerName = parent.providerName,
                    durationMs = parts[partIndex].durationMs,
                    parts = parts,
                    currentPartIndex = partIndex,
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
            controller.playLocalTrack(parent, listOf(parent, other))
            advanceUntilIdle()
            controller.toggleRepeat()

            engine.emitEnded(engine.lastTrack ?: parent)
            advanceUntilIdle()

            assertEquals(RepeatMode.SINGLE, controller.repeatMode)
            assertEquals("https://example.com/bilibili:paged_BV1xx__2.m4s", engine.lastPayload?.url)
            assertEquals(1, controller.playbackState.currentPartIndex)

            engine.emitEnded(engine.lastTrack ?: parent)
            advanceUntilIdle()

            assertEquals("https://example.com/bilibili:paged_BV1xx__1.m4s", engine.lastPayload?.url)
            assertEquals(0, controller.playbackState.currentPartIndex)

            controller.previous()
            advanceUntilIdle()

            assertEquals("https://example.com/bilibili:paged_BV1xx__2.m4s", engine.lastPayload?.url)
            assertEquals(1, controller.playbackState.currentPartIndex)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun staleEndedWhileNextTrackIsLoadingDoesNotSkipAgain() = runTest {
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
            resolveHandler = { track, _, _, _, _, _ -> payloads.getValue(track.id).await() },
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
            payloads.getValue("provider:1").complete(payloadFor(tracks[0]))
            advanceUntilIdle()

            engine.emitEnded(tracks[0])
            advanceUntilIdle()
            engine.emitEnded(tracks[1])
            advanceUntilIdle()

            assertEquals("provider:2", controller.playbackState.currentTrack?.id)
            assertEquals(0, controller.playbackState.queueIndex)
            assertEquals("provider:1", engine.lastTrack?.id)

            payloads.getValue("provider:2").complete(payloadFor(tracks[1]))
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
            resolveHandler = { track, _, _, _, _, _ -> payloads.getValue(track.id).await() },
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
            assertEquals(0, controller.playbackState.queueIndex)

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
    fun playbackEngineErrorSkipsToNextProviderTrack() = runTest {
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

            engine.emitError(tracks[0], "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
            advanceUntilIdle()

            assertEquals("provider:2", engine.lastTrack?.id)
            assertEquals("provider:2", controller.playbackState.currentTrack?.id)
            assertEquals(PlayerStatus.Playing, controller.playbackState.status)
            assertEquals(2, provider.resolveCount)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun smartReplacementMarksUnavailableAndPlaysNextTrackWhenResolveMediaIsMissing() = runTest {
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
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onQueryChange("song")
            controller.onSearchScopeChange(SearchScope.Provider)
            advanceUntilIdle()
            controller.playFromSearch(0)
            advanceUntilIdle()

            assertEquals("provider:2", engine.lastTrack?.id)
            assertEquals(listOf("provider:2"), controller.playbackState.queue.map { it.id })
            assertEquals(false, controller.playbackState.queue[0].isUnavailable)
            assertEquals(0, controller.playbackState.queueIndex)
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
    fun playAllFromSelectedPlaylistLoadsRemainingPagesBeforePlayback() = runTest {
        val tracks = (1..(PROVIDER_PAGE_SIZE + 5)).map { index ->
            providerTrack("provider:$index", "Track $index")
        }
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

            assertEquals(PROVIDER_PAGE_SIZE, controller.selectedPlaylistTracks.size)
            assertEquals(true, controller.selectedPlaylistTracksHasMore)

            controller.playAllFromSelectedPlaylist()
            advanceUntilIdle()

            assertEquals(tracks.size, controller.selectedPlaylistTracks.size)
            assertEquals(false, controller.selectedPlaylistTracksHasMore)
            assertEquals(tracks.map { it.id }, controller.playbackState.queue.map { it.id })
            assertEquals("provider:1", engine.lastTrack?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun providerPlaylistTargetsLoadOnlyWhenCapabilityAndLoginAllow() = runTest {
        val track = providerTrack("netease:1", "First")
        val target = ProviderPlaylist(
            id = "playlist:netease:mine",
            title = "我的歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )
        val provider = FakeProviderRepository(
            tracks = listOf(track),
            playlistTargets = listOf(target),
            capabilities = listOf(
                ProviderCapabilities(
                    providerId = "netease",
                    providerName = "网易云音乐",
                    canAddSongToPlaylist = true,
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
            controller.openPlaylistTargetPicker(track)
            advanceUntilIdle()

            assertEquals(track, controller.playlistTargetTrack)
            assertEquals(listOf(target), controller.playlistOperationTargets)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun addTrackToProviderPlaylistCallsRepositoryAndClosesPicker() = runTest {
        val track = providerTrack("netease:1", "First")
        val target = ProviderPlaylist(
            id = "playlist:netease:mine",
            title = "我的歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )
        val provider = FakeProviderRepository(
            tracks = listOf(track),
            playlistTargets = listOf(target),
            capabilities = listOf(
                ProviderCapabilities(
                    providerId = "netease",
                    providerName = "网易云音乐",
                    canAddSongToPlaylist = true,
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
            controller.openPlaylistTargetPicker(track)
            advanceUntilIdle()
            controller.addTrackToProviderPlaylist(target)
            advanceUntilIdle()

            assertEquals(target to track, provider.lastAddedToPlaylist)
            assertNull(controller.playlistTargetTrack)
            assertEquals("已添加到：我的歌单", controller.message)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun removeTrackFromSelectedPlaylistUpdatesVisibleTracks() = runTest {
        val tracks = listOf(
            providerTrack("netease:1", "First"),
            providerTrack("netease:2", "Second"),
        )
        val playlist = ProviderPlaylist(
            id = "playlist:netease:mine",
            title = "我的歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            playlistTracks = tracks,
            capabilities = listOf(
                ProviderCapabilities(
                    providerId = "netease",
                    providerName = "网易云音乐",
                    canRemoveSongFromPlaylist = true,
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
            controller.openPlaylist(playlist, ProviderFeatureCategory.MinePlaylists)
            advanceUntilIdle()
            controller.removeTrackFromSelectedPlaylist(tracks.first())
            advanceUntilIdle()

            assertEquals(playlist to tracks.first(), provider.lastRemovedFromPlaylist)
            assertEquals(listOf("netease:2"), controller.selectedPlaylistTracks.map { it.id })
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun favoritePlaylistDoesNotAllowRemovingVisibleTracks() = runTest {
        val tracks = listOf(providerTrack("netease:1", "First"))
        val playlist = ProviderPlaylist(
            id = "playlist:netease:favorite",
            title = "收藏歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            playlistTracks = tracks,
            capabilities = listOf(
                ProviderCapabilities(
                    providerId = "netease",
                    providerName = "网易云音乐",
                    canRemoveSongFromPlaylist = true,
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
            controller.openPlaylist(playlist, ProviderFeatureCategory.MineFavoritePlaylists)
            advanceUntilIdle()

            assertFalse(controller.canRemoveTrackFromSelectedPlaylist(tracks.first()))

            controller.removeTrackFromSelectedPlaylist(tracks.first())
            advanceUntilIdle()

            assertEquals(null, provider.lastRemovedFromPlaylist)
            assertEquals(listOf("netease:1"), controller.selectedPlaylistTracks.map { it.id })
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
                listOf("provider:2", "provider:3", "provider:4", "provider:5", "provider:6"),
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
    fun playAllFromSelectedPrivateFmDoesNotLoadAllPagesBeforePlayback() = runTest {
        val radioFeature = ProviderFeature(
            id = "netease_radio",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "私人 FM",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val radioTracks = (1..(PROVIDER_PAGE_SIZE + 5)).map { index ->
            providerTrack("provider:$index", "Track $index")
        }
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
            controller.openFeature(radioFeature)
            advanceUntilIdle()

            assertEquals(PROVIDER_PAGE_SIZE, controller.selectedFeatureTracks.size)
            assertEquals(true, controller.selectedFeatureTracksHasMore)
            val loadedFeatureCalls = provider.loadedFeatureIds.size

            controller.playAllFromSelectedFeature()
            advanceUntilIdle()

            assertEquals(PROVIDER_PAGE_SIZE, controller.selectedFeatureTracks.size)
            assertEquals(loadedFeatureCalls, provider.loadedFeatureIds.size)
            assertEquals(PROVIDER_PAGE_SIZE, controller.playbackState.queue.size)
            assertEquals("provider:1", engine.lastTrack?.id)
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
    fun navigateBackClosesPlaylistBeforeFeature() = runTest {
        val feature = ProviderFeature(
            id = "netease_daily_songs",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "每日推荐歌曲",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = false,
        )
        val playlist = ProviderPlaylist(
            id = "playlist:netease:1",
            title = "歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList(), playlistTracks = listOf(providerTrack("provider:1", "First"))),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.openFeature(feature)
            advanceUntilIdle()
            controller.openPlaylist(playlist)
            advanceUntilIdle()

            assertEquals(playlist.id, controller.selectedPlaylist?.id)
            assertEquals(feature.id, controller.selectedFeature?.id)

            assertEquals(true, controller.navigateBack())
            assertEquals(null, controller.selectedPlaylist)
            assertEquals(feature.id, controller.selectedFeature?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun navigateBackClosesMediaItemBeforeSearch() = runTest {
        val mediaItem = ProviderMediaItem(
            id = "album:netease:1",
            title = "专辑",
            providerId = "netease",
            providerName = "网易云音乐",
            type = ProviderMediaItemType.Album,
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList(), mediaItemTracks = listOf(providerTrack("provider:1", "First"))),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.openSearch()
            controller.openMediaItem(mediaItem)
            advanceUntilIdle()

            assertEquals(mediaItem.id, controller.selectedMediaItem?.id)
            assertEquals(true, controller.isSearchOpen)

            assertEquals(true, controller.navigateBack())
            assertEquals(null, controller.selectedMediaItem)
            assertEquals(true, controller.isSearchOpen)
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
    fun switchingToMineLoadsArtistSections() = runTest {
        val favoriteSongsFeature = ProviderFeature(
            id = "netease_favorite_songs",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "收藏歌曲",
            category = ProviderFeatureCategory.Mine,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val favoriteArtistsFeature = ProviderFeature(
            id = "netease_favorite_artists",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "收藏歌手",
            category = ProviderFeatureCategory.Mine,
            contentType = ProviderContentType.Artists,
            requiresLogin = true,
        )
        val favoriteArtists = listOf(
            ProviderMediaItem(
                id = "artist:netease:1",
                title = "Favorite Artist",
                providerId = "netease",
                providerName = "网易云音乐",
                type = ProviderMediaItemType.Artist,
            ),
        )
        val favoriteTracks = listOf(providerTrack("provider:1", "Favorite"))
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            features = listOf(favoriteSongsFeature, favoriteArtistsFeature),
            featureSections = mapOf(
                favoriteSongsFeature.id to ProviderContentSection(
                    favoriteSongsFeature,
                    tracks = favoriteTracks,
                ),
                favoriteArtistsFeature.id to ProviderContentSection(favoriteArtistsFeature, mediaItems = favoriteArtists),
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
            provider.loadedFeatureIds.clear()
            controller.onMineSectionChange(MineSection.Artists)
            advanceUntilIdle()

            assertEquals(
                listOf(favoriteArtistsFeature.id),
                provider.loadedFeatureIds,
            )
            assertEquals(favoriteArtists, controller.mineSections[0].mediaItems)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun mineFavoriteSectionsFollowProviderAuthState() = runTest {
        val favoriteFeature = ProviderFeature(
            id = "netease_favorite_artists",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "收藏歌手",
            category = ProviderFeatureCategory.Mine,
            contentType = ProviderContentType.Artists,
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
            controller.onMineSectionChange(MineSection.Artists)
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
    fun openingSettingsCanSelectProvider() = runTest {
        val provider = FakeProviderRepository(
            tracks = emptyList(),
            availableProviderInfos = listOf(
                ProviderInfo("netease", "网易云音乐"),
                ProviderInfo("qqmusic", "QQ 音乐"),
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
            controller.onProviderEnabledChange("qqmusic", enabled = true)
            advanceUntilIdle()
            controller.openSettings("qqmusic")
            advanceUntilIdle()

            assertEquals(true, controller.isSettingsOpen)
            assertEquals("qqmusic", controller.selectedSettingsProviderId)
            assertEquals("qqmusic", controller.selectedSettingsProvider()?.providerId)
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
                playlistFilter = PlaylistFilter.FavoritePlaylists,
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
                smartReplacementUseReplacementMetadata = true,
                smartReplacementUseReplacementLyrics = true,
                lyricFontSize = LyricFontSize.Large,
                themeMode = ThemeMode.Dark,
                themeColorScheme = ThemeColorScheme.OceanBlue,
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
            controller.onLocalMusicPermissionChange(true)
            advanceUntilIdle()

            assertEquals(HomeSection.Mine, controller.homeSection)
            assertEquals(MineSection.LocalMusic, controller.mineSection)
            assertEquals(PlaylistFilter.FavoritePlaylists, controller.playlistFilter)
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
            assertEquals(true, controller.smartReplacementUseReplacementMetadata)
            assertEquals(true, controller.smartReplacementUseReplacementLyrics)
            assertEquals(LyricFontSize.Large, controller.lyricFontSize)
            assertEquals(ThemeMode.Dark, controller.themeMode)
            assertEquals(ThemeColorScheme.OceanBlue, controller.themeColorScheme)
            assertEquals(AudioQualityPolicy.Highest, provider.lastWifiAudioQualityPolicy)
            assertEquals(AudioQualityPolicy.Low, provider.lastCellularAudioQualityPolicy)

            controller.onProviderLoginModeChange(ProviderLoginMode.WebView)
            controller.onProviderCookiesChange("netease", """{"MUSIC_U":"draft"}""")
            controller.onProviderHeaderAuthorizationChange("ytmusic", "SAPISIDHASH draft")
            controller.onProviderHeaderCookieChange("ytmusic", "SID=draft")
            controller.onMineSectionChange(MineSection.Artists)
            controller.onPlaylistFilterChange(PlaylistFilter.UserPlaylists)
            controller.onLocalMusicDirectoryEnabledChange("Podcasts/", enabled = true)
            controller.onLocalMusicMinDurationChange(60)
            controller.onAudioCacheLimitChange(1024)
            controller.onImageCacheLimitChange(256)
            controller.onWifiAudioQualityPolicyChange(AudioQualityPolicy.High)
            controller.onCellularAudioQualityPolicyChange(AudioQualityPolicy.Standard)
            controller.onUnavailablePlaybackPolicyChange(UnavailablePlaybackPolicy.SmartReplace)
            controller.onSmartReplacementUseReplacementMetadataChange(false)
            controller.onSmartReplacementUseReplacementLyricsChange(false)
            controller.onLyricFontSizeChange(LyricFontSize.Medium)
            controller.onThemeModeChange(ThemeMode.Light)
            controller.onThemeColorSchemeChange(ThemeColorScheme.FuoGreen)
            advanceUntilIdle()

            assertEquals(ProviderLoginMode.WebView, store.saved.providerLoginMode)
            assertEquals("""{"MUSIC_U":"draft"}""", store.saved.providerCookieInputs["netease"])
            assertEquals(
                ProviderHeaderInput("SAPISIDHASH draft", "SID=draft"),
                store.saved.providerHeaderInputs["ytmusic"],
            )
            assertEquals(setOf("netease", "ytmusic"), store.saved.enabledProviderIds)
            assertEquals(listOf("ytmusic", "netease", "qqmusic", "bilibili"), store.saved.providerOrderIds)
            assertEquals(MineSection.Artists, store.saved.mineSection)
            assertEquals(PlaylistFilter.UserPlaylists, store.saved.playlistFilter)
            assertEquals(emptySet(), store.saved.excludedLocalMusicDirectoryIds)
            assertEquals(60, store.saved.localMusicMinDurationSeconds)
            assertEquals(LocalMusicScanSettings(emptySet(), 60), local.lastSettings)
            assertEquals(1024, store.saved.audioCacheLimitMb)
            assertEquals(256, store.saved.imageCacheLimitMb)
            assertEquals(CacheLimit(1024L * 1024L * 1024L, 256L * 1024L * 1024L), cache.lastLimit)
            assertEquals(AudioQualityPolicy.High, store.saved.wifiAudioQualityPolicy)
            assertEquals(AudioQualityPolicy.Standard, store.saved.cellularAudioQualityPolicy)
            assertEquals(UnavailablePlaybackPolicy.SmartReplace, store.saved.unavailablePlaybackPolicy)
            assertEquals(false, store.saved.smartReplacementUseReplacementMetadata)
            assertEquals(false, store.saved.smartReplacementUseReplacementLyrics)
            assertEquals(LyricFontSize.Medium, store.saved.lyricFontSize)
            assertEquals(ThemeMode.Light, store.saved.themeMode)
            assertEquals(ThemeColorScheme.FuoGreen, store.saved.themeColorScheme)
            assertEquals(AudioQualityPolicy.High, provider.lastWifiAudioQualityPolicy)
            assertEquals(AudioQualityPolicy.Standard, provider.lastCellularAudioQualityPolicy)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun staleLocalMusicRefreshDoesNotOverrideLatestRefresh() = runTest {
        val staleScan = CompletableDeferred<List<MusicTrack>>()
        val filteredScan = CompletableDeferred<List<MusicTrack>>()
        val local = DeferredScanLocalMusicRepository(
            scans = listOf(staleScan, filteredScan),
            directories = listOf(LocalMusicDirectory("Blocked/", "Blocked", 1)),
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onLocalMusicPermissionChange(true)
            controller.refreshLocalMusic()
            advanceUntilIdle()
            controller.refreshLocalMusic()
            advanceUntilIdle()

            filteredScan.complete(listOf(localTrack("local:content://music/allowed", "Allowed")))
            advanceUntilIdle()

            assertEquals(listOf("Allowed"), controller.localTracks.map { it.title })

            staleScan.complete(listOf(localTrack("local:content://music/blocked", "Blocked")))
            advanceUntilIdle()

            assertEquals(listOf("Allowed"), controller.localTracks.map { it.title })
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun localMusicTabWithoutPermissionDoesNotReadOrRefreshDatabase() = runTest {
        val local = FakeLocalMusicRepository(databaseReady = false)
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onHomeSectionChange(HomeSection.Mine)
            controller.onMineSectionChange(MineSection.LocalMusic)
            advanceUntilIdle()

            assertEquals(0, local.refreshDatabaseCount)
            assertEquals(0, local.tracksReadCount)
            assertEquals(emptyList(), controller.localTracks)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun firstLocalMusicEntryWithPermissionRefreshesDatabaseOnce() = runTest {
        val local = FakeLocalMusicRepository(
            tracks = listOf(localTrack("local:content://music/1", "Local")),
            databaseReady = false,
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onLocalMusicPermissionChange(true)
            controller.onHomeSectionChange(HomeSection.Mine)
            controller.onMineSectionChange(MineSection.LocalMusic)
            advanceUntilIdle()
            controller.ensureLocalMusic()
            advanceUntilIdle()

            assertEquals(1, local.refreshDatabaseCount)
            assertEquals(listOf("Local"), controller.localTracks.map { it.title })
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun readyLocalMusicDatabaseReadsTracksWithoutRefresh() = runTest {
        val local = FakeLocalMusicRepository(
            tracks = listOf(localTrack("local:content://music/1", "Cached")),
            databaseReady = true,
            databaseStale = false,
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onLocalMusicPermissionChange(true)
            controller.onMineSectionChange(MineSection.LocalMusic)
            advanceUntilIdle()

            assertEquals(0, local.refreshDatabaseCount)
            assertEquals(1, local.tracksReadCount)
            assertEquals(listOf("Cached"), controller.localTracks.map { it.title })
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun localMusicFilterChangesOnlyReadDatabase() = runTest {
        val local = FakeLocalMusicRepository(
            tracks = listOf(localTrack("local:content://music/1", "Cached")),
            databaseReady = true,
            databaseStale = false,
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onLocalMusicPermissionChange(true)
            controller.onMineSectionChange(MineSection.LocalMusic)
            advanceUntilIdle()
            controller.onLocalMusicMinDurationChange(60)
            advanceUntilIdle()

            assertEquals(0, local.refreshDatabaseCount)
            assertEquals(LocalMusicScanSettings(emptySet(), 60), local.lastSettings)
            assertEquals(2, local.tracksReadCount)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun mediaChangeRefreshesInitializedLocalMusicDatabase() = runTest {
        val local = FakeLocalMusicRepository(
            tracks = listOf(localTrack("local:content://music/1", "Cached")),
            databaseReady = true,
            databaseStale = false,
        )
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.onLocalMusicPermissionChange(true)
            local.emitMediaChange()
            advanceTimeBy(750)
            advanceUntilIdle()

            assertEquals(1, local.refreshDatabaseCount)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun themeModeResolvesSystemAndOverrides() {
        assertEquals(false, resolvedDarkTheme(ThemeMode.System, systemDark = false))
        assertEquals(true, resolvedDarkTheme(ThemeMode.System, systemDark = true))
        assertEquals(false, resolvedDarkTheme(ThemeMode.Light, systemDark = true))
        assertEquals(true, resolvedDarkTheme(ThemeMode.Dark, systemDark = false))
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
            val ytmusic = controller.availableProviders.first { it.providerId == "ytmusic" }
            assertEquals(
                setOf(ProviderLoginMode.WebView, ProviderLoginMode.Headers),
                ytmusic.supportedLoginModes,
            )
            assertEquals(
                ProviderLoginConfig(
                    loginUrl = "https://music.youtube.com",
                    cookieKeyGroups = listOf(listOf("__Secure-3PAPISID")),
                ),
                ytmusic.loginConfig,
            )
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

    @Test
    fun restoresPersistedQueueWithoutAutoPlay() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val upNext = providerTrack("provider:3", "Third")
        val queueStore = FakePlaybackQueueStore(
            PlaybackQueueSnapshot(
                mainQueue = tracks,
                upNextQueue = listOf(upNext),
                queueIndex = 1,
                shuffleEnabled = true,
                repeatMode = RepeatMode.OFF,
            ),
        )
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                playbackQueueStore = queueStore,
                scope = controllerScope,
            )

            advanceUntilIdle()

            assertEquals("provider:2", controller.playbackState.currentTrack?.id)
            assertEquals(listOf("provider:2", "provider:3"), controller.playbackState.queue.map { it.id })
            assertEquals(0, controller.playbackState.queueIndex)
            assertEquals(true, controller.isShuffleEnabled)
            assertEquals(RepeatMode.OFF, controller.repeatMode)
            assertEquals(null, engine.lastTrack)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun restoredQueueToggleStartsCurrentTrackFromBeginning() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val queueStore = FakePlaybackQueueStore(
            PlaybackQueueSnapshot(
                mainQueue = tracks,
                queueIndex = 0,
            ),
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
                playbackQueueStore = queueStore,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.toggle()
            advanceUntilIdle()

            assertEquals("provider:1", engine.lastTrack?.id)
            assertEquals(1, provider.resolveCount)
            assertEquals(0, engine.resumeCount)
            assertEquals(0, controller.playbackState.positionMs)
            assertEquals(0, controller.playbackState.queueIndex)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun disablingShuffleRestoresOriginalMainQueue() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
            providerTrack("provider:3", "Third"),
            providerTrack("provider:4", "Fourth"),
        )
        val queueStore = FakePlaybackQueueStore()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(tracks),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                playbackQueueStore = queueStore,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.playAllLocalTracks(tracks)
            advanceUntilIdle()
            controller.toggleShuffle()
            advanceUntilIdle()

            assertEquals(true, controller.isShuffleEnabled)
            assertEquals(tracks.map { it.id }, queueStore.saved.originalMainQueue.map { it.id })

            controller.toggleShuffle()
            advanceUntilIdle()

            assertEquals(false, controller.isShuffleEnabled)
            assertEquals(tracks.map { it.id }, controller.playbackState.queue.map { it.id })
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun selectedTrackStartsPlaybackWhenShuffleIsEnabled() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
            providerTrack("provider:3", "Third"),
        )
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(tracks),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.toggleShuffle()
            advanceUntilIdle()
            controller.playLocalTrack(tracks[2], tracks)
            advanceUntilIdle()

            assertEquals("provider:3", engine.lastTrack?.id)
            assertEquals("provider:3", controller.playbackState.queue.first().id)
            assertEquals(0, controller.playbackState.queueIndex)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun playAllUsesShuffledStartWhenShuffleIsEnabled() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
            providerTrack("provider:3", "Third"),
        )
        val queueStore = FakePlaybackQueueStore()
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(tracks),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                playbackQueueStore = queueStore,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.toggleShuffle()
            controller.playAllLocalTracks(tracks)
            advanceUntilIdle()

            assertEquals(true, controller.isShuffleEnabled)
            assertEquals(tracks.map { it.id }, queueStore.saved.originalMainQueue.map { it.id })
            assertEquals(controller.playbackState.queue.first().id, engine.lastTrack?.id)
            assertEquals(false, engine.lastTrack?.id == tracks.first().id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun queueDisplayStartsWithCurrentThenUpNextThenRemainingTracks() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
            providerTrack("provider:3", "Third"),
        )
        val upNext = providerTrack("provider:4", "Fourth")
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(tracks + upNext),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.playAllLocalTracks(tracks)
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()
            controller.addToUpNext(upNext)
            advanceUntilIdle()

            assertEquals(
                listOf("provider:2", "provider:4", "provider:3"),
                controller.playbackState.queue.map { it.id },
            )
            assertEquals(0, controller.playbackState.queueIndex)

            controller.playQueueIndex(2)
            advanceUntilIdle()

            assertEquals("provider:3", engine.lastTrack?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun upNextQueuePlaysBeforeMainQueueAndThenReturns() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val upNext = providerTrack("provider:3", "Third")
        val provider = FakeProviderRepository(tracks + upNext)
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
            controller.playAllLocalTracks(tracks)
            advanceUntilIdle()
            controller.addToUpNext(upNext)
            controller.next()
            advanceUntilIdle()

            assertEquals("provider:3", engine.lastTrack?.id)

            controller.next()
            advanceUntilIdle()

            assertEquals("provider:2", engine.lastTrack?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun repeatModeCyclesThroughQueueSingleOff() = runTest {
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

            assertEquals(RepeatMode.QUEUE, controller.repeatMode)
            controller.toggleRepeat()
            assertEquals(RepeatMode.SINGLE, controller.repeatMode)
            controller.toggleRepeat()
            assertEquals(RepeatMode.OFF, controller.repeatMode)
            controller.toggleRepeat()
            assertEquals(RepeatMode.QUEUE, controller.repeatMode)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun repeatQueueLoopsAtMainQueueEnd() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(tracks),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.playAllLocalTracks(tracks)
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()

            assertEquals(RepeatMode.QUEUE, controller.repeatMode)
            assertEquals("provider:1", engine.lastTrack?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun repeatSingleReplaysCurrentTrack() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(tracks),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.toggleRepeat()
            controller.playAllLocalTracks(tracks)
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()

            assertEquals(RepeatMode.SINGLE, controller.repeatMode)
            assertEquals("provider:1", engine.lastTrack?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun repeatDisabledStopsAtMainQueueEnd() = runTest {
        val tracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val engine = FakePlaybackEngine()
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(tracks),
                localRepository = FakeLocalMusicRepository(),
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = engine,
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.toggleRepeat()
            controller.toggleRepeat()
            controller.playAllLocalTracks(tracks)
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()
            controller.next()
            advanceUntilIdle()

            assertEquals(RepeatMode.OFF, controller.repeatMode)
            assertEquals("provider:2", engine.lastTrack?.id)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun privateFmTemporarilyDisablesShuffleAndRestoresAfterExit() = runTest {
        val radioFeature = ProviderFeature(
            id = "netease_radio",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "私人 FM",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val normalTracks = listOf(
            providerTrack("provider:1", "First"),
            providerTrack("provider:2", "Second"),
        )
        val radioTracks = listOf(
            providerTrack("provider:3", "Radio One"),
            providerTrack("provider:4", "Radio Two"),
        )
        val provider = FakeProviderRepository(
            tracks = normalTracks,
            features = listOf(radioFeature),
            featureSections = mapOf(radioFeature.id to ProviderContentSection(radioFeature, tracks = radioTracks)),
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
            controller.playAllLocalTracks(normalTracks)
            advanceUntilIdle()
            controller.toggleShuffle()
            advanceUntilIdle()
            assertEquals(true, controller.isShuffleEnabled)

            controller.openFeature(radioFeature)
            advanceUntilIdle()
            controller.playAllFromSelectedFeature()
            advanceUntilIdle()

            assertEquals(true, controller.isFmQueueActive)
            assertEquals(false, controller.isShuffleEnabled)
            assertEquals(radioTracks.map { it.id }, controller.playbackState.queue.map { it.id })

            controller.playAllLocalTracks(normalTracks)
            advanceUntilIdle()

            assertEquals(false, controller.isFmQueueActive)
            assertEquals(true, controller.isShuffleEnabled)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun saveLocalMetadataUpdatesRepositoryAndLocalTracks() = runTest {
        val localTrack = localTrack("local:content://music/1", "Old Title")
        val local = FakeLocalMusicRepository(tracks = listOf(localTrack))
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = FakeProviderRepository(emptyList()),
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.saveLocalMetadata(localTrack, "New Title", "New Artist", "New Album")
            advanceUntilIdle()

            assertEquals(LocalTrackMetadata("New Title", "New Artist", "New Album"), local.lastMetadata)
            assertEquals("New Title", controller.localTracks.first().title)
            assertEquals("New Artist", controller.localTracks.first().artists)
            assertEquals("New Album", controller.localTracks.first().album)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun downloadLocalLyricsSavesLyricsAndRefreshesLocalTrack() = runTest {
        val localTrack = localTrack("local:content://music/1", "Local Title")
        val providerTrack = providerTrack("provider:1", "Provider Title")
        val provider = FakeProviderRepository(
            tracks = listOf(providerTrack),
            resolveHandler = { track, _, _, _, _, _ ->
                payloadFor(track).copy(lyrics = "[00:01.00]Provider lyric")
            },
        )
        val local = FakeLocalMusicRepository(tracks = listOf(localTrack))
        val controllerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = FuoPlayerController(
                providerRepository = provider,
                localRepository = local,
                downloadRepository = FakeDownloadRepository(emptyMap()),
                playbackEngine = FakePlaybackEngine(),
                scope = controllerScope,
            )

            advanceUntilIdle()
            controller.downloadLocalLyrics(localTrack, providerTrack)
            advanceUntilIdle()

            assertEquals("[00:01.00]Provider lyric", local.lastSavedLyrics)
            assertEquals("[00:01.00]Provider lyric", controller.localTracks.first().lyrics)
            assertEquals(1, provider.resolveCount)
            assertEquals(setOf("netease"), provider.lastSmartReplacementProviderIds)
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

    private fun localTrack(id: String, title: String): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = "local",
        sourceType = TrackSourceType.LocalMediaStore,
        localUri = id.removePrefix("local:"),
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
        private val playlistTargets: List<ProviderPlaylist> = emptyList(),
        private val capabilities: List<ProviderCapabilities> = emptyList(),
        private val searchResults: ProviderSearchResults? = null,
        private val similarTracks: List<MusicTrack> = emptyList(),
        private val comments: List<ProviderComment> = emptyList(),
        private val trackVideo: ProviderVideo? = null,
        private val videoPayload: VideoPlaybackPayload? = null,
        private val resolveFailures: Set<String> = emptySet(),
        private val resolveHandler: (
            suspend (MusicTrack, UnavailablePlaybackPolicy, Set<String>, Double, Boolean, Boolean) -> PlaybackPayload
        )? = null,
        private val availableProviderInfos: List<ProviderInfo> = listOf(
            ProviderInfo(providerId = "netease", providerName = "网易云音乐"),
            ProviderInfo(providerId = "qqmusic", providerName = "QQ 音乐"),
            ProviderInfo(providerId = "bilibili", providerName = "哔哩哔哩"),
            ProviderInfo(
                providerId = "ytmusic",
                providerName = "YouTube Music",
                loginConfig = ProviderLoginConfig(
                    loginUrl = "https://music.youtube.com",
                    cookieKeyGroups = listOf(listOf("__Secure-3PAPISID")),
                ),
                supportedLoginModes = setOf(ProviderLoginMode.WebView, ProviderLoginMode.Headers),
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
        var lastSmartReplacementProviderIds: Set<String>? = null
        var lastSmartReplacementMinScore: Double? = null
        var lastSmartReplacementUseOriginalMetadata: Boolean? = null
        var lastSmartReplacementUseOriginalLyrics: Boolean? = null
        var lastTrackDetailId: String? = null
        var lastAddedToPlaylist: Pair<ProviderPlaylist, MusicTrack>? = null
        var lastRemovedFromPlaylist: Pair<ProviderPlaylist, MusicTrack>? = null
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

        override suspend fun searchAll(keyword: String, providerId: String?): ProviderSearchResults =
            searchResults ?: ProviderSearchResults(tracks = tracks)

        override suspend fun trackDetail(trackId: String): MusicTrack {
            lastTrackDetailId = trackId
            return tracks.firstOrNull { it.id == trackId } ?: throw RuntimeException("track not found: $trackId")
        }

        override suspend fun resolve(
            track: MusicTrack,
            unavailablePolicy: UnavailablePlaybackPolicy,
            smartReplacementProviderIds: Set<String>,
            smartReplacementMinScore: Double,
            smartReplacementUseOriginalMetadata: Boolean,
            smartReplacementUseOriginalLyrics: Boolean,
        ): PlaybackPayload {
            resolveCount += 1
            lastSmartReplacementProviderIds = smartReplacementProviderIds
            lastSmartReplacementMinScore = smartReplacementMinScore
            lastSmartReplacementUseOriginalMetadata = smartReplacementUseOriginalMetadata
            lastSmartReplacementUseOriginalLyrics = smartReplacementUseOriginalLyrics
            if (track.id in resolveFailures) {
                throw RuntimeException("media not found: ${track.id}")
            }
            return resolveHandler?.invoke(
                track,
                unavailablePolicy,
                smartReplacementProviderIds,
                smartReplacementMinScore,
                smartReplacementUseOriginalMetadata,
                smartReplacementUseOriginalLyrics,
            ) ?: PlaybackPayload(
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

        override suspend fun providerCapabilities(): List<ProviderCapabilities> = capabilities

        override suspend fun features(): List<ProviderFeature> = features

        override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection {
            loadedFeatureIds += feature.id
            return featureSections[feature.id] ?: ProviderContentSection(feature)
        }

        override suspend fun loadFeaturePage(
            feature: ProviderFeature,
            offset: Int,
            limit: Int,
        ): ProviderContentSection {
            loadedFeatureIds += feature.id
            val section = featureSections[feature.id] ?: return ProviderContentSection(feature)
            val tracks = section.tracks.drop(offset).take(limit)
            val playlists = section.playlists.drop(offset).take(limit)
            val mediaItems = section.mediaItems.drop(offset).take(limit)
            val sourceSize = when (feature.contentType) {
                ProviderContentType.Songs -> section.tracks.size
                ProviderContentType.Playlists -> section.playlists.size
                ProviderContentType.Artists,
                ProviderContentType.Albums -> section.mediaItems.size
            }
            val nextOffset = offset + maxOf(tracks.size, playlists.size, mediaItems.size)
            return section.copy(
                tracks = tracks,
                playlists = playlists,
                mediaItems = mediaItems,
                nextOffset = nextOffset,
                hasMore = nextOffset < sourceSize,
            )
        }

        override suspend fun loadMoreFeatureTracks(feature: ProviderFeature): List<MusicTrack> {
            loadedMoreFeatureIds += feature.id
            return additionalFeatureTracks[feature.id] ?: loadFeature(feature).tracks
        }

        override suspend fun playlistDetailPage(
            playlist: ProviderPlaylist,
            offset: Int,
            limit: Int,
        ): ProviderPlaylistDetail {
            val tracks = playlistTracks.drop(offset).take(limit)
            val nextOffset = offset + tracks.size
            return ProviderPlaylistDetail(
                playlist = playlist,
                tracks = tracks,
                tracksNextOffset = nextOffset,
                tracksHasMore = nextOffset < playlistTracks.size,
            )
        }

        override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = playlistTracks

        override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> = playlistTargets

        override suspend fun addTrackToPlaylist(
            playlist: ProviderPlaylist,
            track: MusicTrack,
        ): ProviderMutationResult {
            lastAddedToPlaylist = playlist to track
            return ProviderMutationResult(true, "已添加到：${playlist.title}")
        }

        override suspend fun removeTrackFromPlaylist(
            playlist: ProviderPlaylist,
            track: MusicTrack,
        ): ProviderMutationResult {
            lastRemovedFromPlaylist = playlist to track
            return ProviderMutationResult(true, "已从歌单移除：${track.title}")
        }

        override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> {
            loadedMediaItemIds += item.id
            return mediaItemTracks
        }

        override suspend fun mediaItemDetailPage(
            item: ProviderMediaItem,
            tracksOffset: Int,
            albumsOffset: Int,
            limit: Int,
        ): ProviderMediaItemDetail {
            loadedMediaItemIds += item.id
            val tracks = mediaItemTracks.drop(tracksOffset).take(limit)
            val tracksNextOffset = tracksOffset + tracks.size
            return ProviderMediaItemDetail(
                item = item,
                tracks = tracks,
                tracksNextOffset = tracksNextOffset,
                tracksHasMore = tracksNextOffset < mediaItemTracks.size,
            )
        }

        override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> = similarTracks

        override suspend fun hotComments(track: MusicTrack): List<ProviderComment> = comments

        override suspend fun trackVideo(track: MusicTrack): ProviderVideo? = trackVideo

        override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload =
            videoPayload ?: VideoPlaybackPayload(video = video, url = "https://example.com/${video.id}.mp4")
    }

    private class FakeLocalMusicRepository(
        private val directories: List<LocalMusicDirectory> = emptyList(),
        tracks: List<MusicTrack> = emptyList(),
        var databaseReady: Boolean = true,
        var databaseStale: Boolean = false,
    ) : LocalMusicRepository {
        private val mutableMediaChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val mediaChangeEvents = mutableMediaChangeEvents
        var lastSettings = LocalMusicScanSettings()
        var lastMetadata: LocalTrackMetadata? = null
        var lastSavedLyrics: String? = null
        var tracksReadCount = 0
        var refreshDatabaseCount = 0
        private var tracks = tracks

        fun emitMediaChange() {
            mutableMediaChangeEvents.tryEmit(Unit)
        }

        override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
            lastSettings = settings
        }

        override suspend fun isDatabaseReady(): Boolean = databaseReady

        override suspend fun isDatabaseStale(): Boolean = databaseStale

        override suspend fun directories(): List<LocalMusicDirectory> = directories

        override suspend fun tracks(): List<MusicTrack> {
            tracksReadCount += 1
            return tracks
        }

        override suspend fun refreshDatabase(): List<MusicTrack> {
            refreshDatabaseCount += 1
            databaseReady = true
            databaseStale = false
            return tracks
        }

        override suspend fun search(keyword: String): List<MusicTrack> = tracks.filter {
            it.title.contains(keyword, ignoreCase = true) ||
                it.artists.contains(keyword, ignoreCase = true) ||
                it.album.contains(keyword, ignoreCase = true)
        }

        override suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) {
            lastMetadata = metadata
            tracks = tracks.map {
                if (it.id == track.id) {
                    it.copy(
                        title = metadata.title,
                        artists = metadata.artists,
                        album = metadata.album,
                    )
                } else {
                    it
                }
            }
        }

        override suspend fun saveLyrics(track: MusicTrack, lyrics: String) {
            lastSavedLyrics = lyrics
            tracks = tracks.map {
                if (it.id == track.id) it.copy(lyrics = lyrics) else it
            }
        }
    }

    private class DeferredScanLocalMusicRepository(
        scans: List<CompletableDeferred<List<MusicTrack>>>,
        private val directories: List<LocalMusicDirectory>,
    ) : LocalMusicRepository {
        private val pendingScans = ArrayDeque(scans)
        var lastSettings = LocalMusicScanSettings()
        var databaseReady: Boolean = true
        var databaseStale: Boolean = false

        override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
            lastSettings = settings
        }

        override suspend fun isDatabaseReady(): Boolean = databaseReady

        override suspend fun isDatabaseStale(): Boolean = databaseStale

        override suspend fun directories(): List<LocalMusicDirectory> = directories

        override suspend fun tracks(): List<MusicTrack> = emptyList()

        override suspend fun refreshDatabase(): List<MusicTrack> {
            return pendingScans.removeFirst().await()
        }

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
        var resumeCount = 0

        override fun prepareLoading(track: MusicTrack) {
            mutableState.value = PlaybackState(
                status = PlayerStatus.Loading,
                currentTrack = track,
                durationMs = track.durationMs ?: 0,
                lyrics = track.lyrics,
            )
        }

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

        fun emitEnded(track: MusicTrack) {
            mutableState.value = mutableState.value.copy(
                status = PlayerStatus.Ended,
                currentTrack = track,
            )
        }

        fun emitError(track: MusicTrack, message: String) {
            mutableState.value = mutableState.value.copy(
                status = PlayerStatus.Error,
                currentTrack = track,
                errorMessage = message,
            )
        }

        override fun pause() {
            mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
        }

        override fun resume() {
            resumeCount += 1
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

    private class FakePlaybackQueueStore(initial: PlaybackQueueSnapshot = PlaybackQueueSnapshot()) : PlaybackQueueStore {
        var saved = initial
            private set

        override suspend fun load(): PlaybackQueueSnapshot = saved

        override suspend fun save(snapshot: PlaybackQueueSnapshot) {
            saved = snapshot
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
