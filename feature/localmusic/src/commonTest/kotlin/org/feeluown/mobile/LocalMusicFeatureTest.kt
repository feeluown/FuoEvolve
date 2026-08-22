package org.feeluown.mobile

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalMusicFeatureTest {
    @Test
    fun permissionAndRefreshAreFeatureOwned() = runTest {
        val repository = FakeRepository()
        val owner = createLocalMusicFeatureOwner(
            repository = repository,
            providerRepository = FakeProvider,
            operations = Operations,
            providers = { emptyList() },
            selectedSearchProviderId = { null },
            isLocalMusicSectionActive = { false },
            scope = this,
            openCollectionRoute = {},
            closeCollectionRoute = {},
            persistSettings = { _, _, _ -> },
        )

        owner.onPermissionChange(true)
        owner.refresh(forceRefresh = true, showLoading = true)
        advanceUntilIdle()

        assertEquals(listOf(Track("latest", "Latest")), owner.uiState.value.tracks)
        assertFalse(owner.uiState.value.isLoading)
        assertEquals(1, repository.refreshCount)
    }

    private data class Track(val id: String, val title: String)
    private data class Provider(val id: String)
    private data class Directory(val id: String)
    private data class Selection(val mode: Mode, val key: String)
    private enum class Mode { All, Artist, Album }

    private class FakeRepository : LocalMusicRepositoryPort<Track, Directory> {
        override val mediaChangeEvents = emptyFlow<Unit>()
        var refreshCount = 0
        override suspend fun updateScanSettings(excludedDirectoryIds: Set<String>, minDurationSeconds: Int) = Unit
        override suspend fun isDatabaseReady() = false
        override suspend fun isDatabaseStale() = false
        override suspend fun refreshDatabase(): List<Track> {
            refreshCount += 1
            return listOf(Track("latest", "Latest"))
        }
        override suspend fun tracks() = listOf(Track("cached", "Cached"))
        override suspend fun directories() = listOf(Directory("Music/"))
        override suspend fun updateMetadata(track: Track, title: String, artists: String, album: String) = Unit
        override suspend fun saveLyrics(track: Track, lyrics: String) = Unit
    }

    private object FakeProvider : LocalMusicProviderPort<Track> {
        override suspend fun search(keyword: String, providerId: String) = emptyList<Track>()
        override suspend fun lyrics(track: Track): String? = null
    }

    private object Operations : LocalMusicFeatureOperations<Track, Provider, Directory, Mode, Selection> {
        override fun trackId(track: Track) = track.id
        override fun trackTitle(track: Track) = track.title
        override fun trackArtists(track: Track) = ""
        override fun trackAlbum(track: Track) = ""
        override fun isProviderTrack(track: Track) = false
        override fun providerTrackId(track: Track): String? = null
        override fun withProviderTrackId(track: Track, providerTrackId: String) = track
        override fun withMetadata(track: Track, title: String, artists: String, album: String) = track.copy(title = title)
        override fun withLyrics(track: Track, lyrics: String) = track
        override fun providerId(provider: Provider) = provider.id
        override fun directoryId(directory: Directory) = directory.id
        override fun defaultViewMode() = Mode.All
        override fun isAllViewMode(viewMode: Mode) = viewMode == Mode.All
        override fun collection(viewMode: Mode, key: String) = Selection(viewMode, key)
    }
}
