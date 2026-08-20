package org.feeluown.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMusicOwnershipTest {
    @Test
    fun compatibilityStatePublishesImmutableFeatureProjection() {
        val state = LocalMusicControllerState()
        var projection = LocalMusicUiState()
        var publishCount = 0
        state.observeChanges {
            projection = state.toUiState(projection)
            publishCount += 1
        }

        val track = MusicTrack(
            id = "local:1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "local",
            sourceType = TrackSourceType.LocalMediaStore,
            localDirectoryId = "Music/",
        )
        state.tracks = listOf(track)
        state.viewMode = LocalMusicViewMode.Artist
        state.excludedDirectoryIds = setOf("Podcasts/")
        state.selectedCollection = LocalMusicCollectionSelection(LocalMusicViewMode.Artist, "Artist")
        state.metadataEditorTrack = track

        assertEquals(listOf(track), projection.tracks)
        assertEquals(LocalMusicViewMode.Artist, projection.viewMode)
        assertEquals(setOf("Podcasts/"), projection.excludedDirectoryIds)
        assertEquals(LocalMusicCollectionSelection(LocalMusicViewMode.Artist, "Artist"), projection.selectedCollection)
        assertEquals(track, projection.metadataEditorTrack)
        assertEquals(6, publishCount)

        state.metadataEditorTrack = null
        assertNull(projection.metadataEditorTrack)
        assertEquals(7, publishCount)
    }

    @Test
    fun localCollectionsRespectExcludedDirectories() {
        val tracks = listOf(
            localTrack("one", "One", "Music/"),
            localTrack("two", "Two", "Podcasts/"),
        )
        val directories = listOf(
            LocalMusicDirectory(id = "Music/", name = "Music", trackCount = 1),
            LocalMusicDirectory(id = "Podcasts/", name = "Podcasts", trackCount = 1),
        )

        val collections = buildLocalMusicCollections(
            mode = LocalMusicViewMode.All,
            tracks = tracks,
            directories = directories,
            excludedDirectoryIds = setOf("Podcasts"),
        )

        assertEquals(listOf("Music/"), collections.map { it.key })
        assertEquals(listOf("one"), collections.single().tracks.map { it.id })
    }

    @Test
    fun silentRefreshClearsLoadingWhenItSupersedesVisibleRefresh() = runTest {
        val repository = RacingLocalMusicRepository()
        var compatibilityLoading = false
        val controller = LocalMusicController(
            repository = repository,
            providerRepository = FakeProviderRepository,
            navigator = AppNavigator(),
            scope = this,
            providers = { emptyList() },
            selectedSearchProviderId = { null },
            isLocalMusicSectionActive = { false },
            persistSettings = {},
            setLoading = { compatibilityLoading = it },
            setMessage = {},
            onError = { throw it },
            onTrackUpdated = { _, _ -> },
        )

        controller.refresh(forceRefresh = true, showLoading = true)
        repository.firstRefreshStarted.await()

        assertTrue(controller.uiState.value.isLoading)
        assertTrue(compatibilityLoading)

        controller.refresh(forceRefresh = true, showLoading = false)
        repository.secondRefreshStarted.await()
        runCurrent()

        assertFalse(controller.uiState.value.isLoading)
        assertFalse(compatibilityLoading)
        assertEquals(listOf("latest"), controller.uiState.value.tracks.map { it.id })

        repository.allowFirstRefreshToFinish.complete(Unit)
        advanceUntilIdle()

        assertFalse(controller.uiState.value.isLoading)
        assertFalse(compatibilityLoading)
        assertEquals(listOf("latest"), controller.uiState.value.tracks.map { it.id })
    }

    private fun localTrack(id: String, title: String, directoryId: String) = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = "local",
        sourceType = TrackSourceType.LocalMediaStore,
        localDirectoryId = directoryId,
    )

    private class RacingLocalMusicRepository : LocalMusicRepository {
        val firstRefreshStarted = CompletableDeferred<Unit>()
        val allowFirstRefreshToFinish = CompletableDeferred<Unit>()
        val secondRefreshStarted = CompletableDeferred<Unit>()
        private var refreshCalls = 0

        private val directory = LocalMusicDirectory(
            id = "Music/",
            name = "Music",
            trackCount = 1,
        )
        private val staleTrack = MusicTrack(
            id = "stale",
            title = "Stale",
            artists = "Artist",
            album = "Album",
            source = "local",
            sourceType = TrackSourceType.LocalMediaStore,
            localDirectoryId = directory.id,
        )
        private val latestTrack = staleTrack.copy(id = "latest", title = "Latest")

        override suspend fun updateScanSettings(settings: LocalMusicScanSettings) = Unit
        override suspend fun isDatabaseReady(): Boolean = false
        override suspend fun directories(): List<LocalMusicDirectory> = listOf(directory)
        override suspend fun tracks(): List<MusicTrack> = listOf(latestTrack)

        override suspend fun refreshDatabase(): List<MusicTrack> {
            refreshCalls += 1
            return if (refreshCalls == 1) {
                firstRefreshStarted.complete(Unit)
                allowFirstRefreshToFinish.await()
                listOf(staleTrack)
            } else {
                secondRefreshStarted.complete(Unit)
                listOf(latestTrack)
            }
        }

        override suspend fun search(keyword: String): List<MusicTrack> = emptyList()
    }

    private object FakeProviderRepository : ProviderMusicRepository {
        override suspend fun initialize() = Unit
        override suspend fun providers(): List<ProviderInfo> = emptyList()
        override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> = emptyList()

        override suspend fun resolve(
            track: MusicTrack,
            unavailablePolicy: UnavailablePlaybackPolicy,
            smartReplacementProviderIds: Set<String>,
            smartReplacementMinScore: Double,
            smartReplacementUseOriginalMetadata: Boolean,
            smartReplacementUseOriginalLyrics: Boolean,
        ): PlaybackPayload = PlaybackPayload(
            url = "",
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = track.source,
        )

        override suspend fun authState(providerId: String): ProviderAuthState =
            ProviderAuthState(providerId = providerId, providerName = providerId, isLoggedIn = false)

        override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
            authState(providerId)

        override suspend fun logout(providerId: String): ProviderAuthState = authState(providerId)

        override suspend fun updateAudioQualityPolicies(
            wifiPolicy: AudioQualityPolicy,
            cellularPolicy: AudioQualityPolicy,
        ) = Unit

        override suspend fun features(): List<ProviderFeature> = emptyList()
        override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection = ProviderContentSection(feature)
        override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = emptyList()
        override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> = emptyList()
    }
}
