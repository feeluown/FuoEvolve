package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppFeaturePortAdaptersTest {
    @Test
    fun searchPortUsesFeatureOwnersAndClosesSearchAfterPlaybackSelection() {
        val first = track("netease:1")
        val second = track("netease:2")
        val search = FakeSearchController(SearchUiState(searchResults = listOf(first, second)))
        val queue = RecordingPlaybackQueuePort()
        val navigator = AppNavigator().apply { navigate(AppRoute.Search) }
        val provider = ProviderInfo("netease", "网易云音乐")
        val port = DefaultSearchAppPort(
            searchController = search,
            providerSessions = { ProviderSessionState(providers = listOf(provider)) },
            playbackQueue = queue,
            downloads = FakeDownloadActionPort(),
            playlists = FakePlaylistActionPort(),
            providerTrackActions = FakeProviderTrackActionPort(),
            navigator = navigator,
        )

        assertEquals(listOf(provider), port.providers)
        port.playResult(1)

        assertEquals(listOf(first, second), queue.playedTracks)
        assertEquals(1, queue.playedIndex)
        assertEquals(AppRoute.Home, navigator.currentEntry)
    }

    @Test
    fun searchPortUsesTypedProviderNavigationWithoutControllerState() {
        val navigator = AppNavigator()
        val port = DefaultSearchAppPort(
            searchController = FakeSearchController(),
            providerSessions = { ProviderSessionState() },
            playbackQueue = RecordingPlaybackQueuePort(),
            downloads = FakeDownloadActionPort(),
            playlists = FakePlaylistActionPort(),
            providerTrackActions = FakeProviderTrackActionPort(),
            navigator = navigator,
        )
        val playlist = ProviderPlaylist(
            id = "mine",
            title = "我的歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )

        port.openPlaylist(playlist)

        assertEquals(
            AppRoute.PlaylistDetail(playlist.toNavigationPlaylist()),
            navigator.currentEntry,
        )
    }

    @Test
    fun recognitionPortBuildsTypedNeteaseTrackRouteOnlyWhenProviderIsEnabled() {
        val song = RecognizedSong(
            neteaseSongId = "123",
            title = "Song",
            artists = listOf("Artist A", "Artist B"),
            album = "Album",
            coverUrl = "https://example.test/cover.jpg",
        )
        val disabledNavigator = AppNavigator()
        val disabled = DefaultRecognitionAppPort(
            isProviderEnabled = { false },
            navigator = disabledNavigator,
        )

        assertFalse(disabled.canOpenNeteaseDetail(song))
        disabled.openNeteaseDetail(song)
        assertEquals(AppRoute.Home, disabledNavigator.currentEntry)

        val enabledNavigator = AppNavigator()
        val enabled = DefaultRecognitionAppPort(
            isProviderEnabled = { it == "netease" },
            navigator = enabledNavigator,
        )

        assertTrue(enabled.canOpenNeteaseDetail(song))
        enabled.openNeteaseDetail(song)

        val route = enabledNavigator.currentEntry as AppRoute.TrackDetail
        assertEquals("netease:123", route.track.id)
        assertEquals("Artist A / Artist B", route.track.artists)
        assertEquals("netease", route.track.source)
    }

    private fun track(id: String): MusicTrack = MusicTrack(
        id = id,
        title = id,
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )

    private class FakeSearchController(
        initialState: SearchUiState = SearchUiState(),
    ) : SearchFeatureController {
        private val state = MutableStateFlow(initialState)
        override val uiState: StateFlow<SearchUiState> = state

        override fun dispatch(action: SearchAction) = Unit
        override fun applyPreferences(searchScope: SearchScope, selectedSearchProviderId: String?) = Unit
        override fun normalizeProviderSelection(providerIds: Set<String>) = Unit
        override fun searchRecognizedSong(song: RecognizedSong) = Unit
        override fun searchText(text: String, providerId: String?) = Unit
    }

    private class RecordingPlaybackQueuePort : PlaybackQueueUiPort {
        var playedTracks: List<MusicTrack>? = null
        var playedIndex: Int? = null

        override val currentQueueTrack: MusicTrack? = null
        override val queue: List<MusicTrack> = emptyList()
        override val displayUpNextCount: Int = 0
        override val isShuffleEnabled: Boolean = false
        override val repeatMode: RepeatMode = RepeatMode.QUEUE
        override val isFmQueueActive: Boolean = false
        override val trackChangeDirection: TrackChangeDirection = TrackChangeDirection.Next

        override fun playTracks(tracks: List<MusicTrack>, index: Int) {
            playedTracks = tracks
            playedIndex = index
        }

        override fun toggleShuffle() = Unit
        override fun toggleRepeat() = Unit
        override fun clearQueue() = Unit
        override fun playQueueIndex(index: Int) = Unit
        override fun removeFromQueue(track: MusicTrack) = Unit
        override fun playPlaybackPart(index: Int) = Unit
        override fun addToUpNext(track: MusicTrack) = Unit
    }

    private class FakeDownloadActionPort : DownloadActionPort {
        override val downloadStates: Map<String, DownloadState> = emptyMap()
        override fun download(track: MusicTrack) = Unit
        override fun deleteDownload(track: MusicTrack) = Unit
    }

    private class FakePlaylistActionPort : PlaylistActionPort {
        override fun canAddTrackToPlaylist(track: MusicTrack): Boolean = true
        override fun openPlaylistTargetPicker(track: MusicTrack) = Unit
        override fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean = false
        override fun removeTrackFromSelectedPlaylist(track: MusicTrack) = Unit
    }

    private class FakeProviderTrackActionPort : ProviderTrackActionPort {
        override fun openTrackArtist(track: MusicTrack) = Unit
        override fun openTrackAlbum(track: MusicTrack) = Unit
        override fun openOriginalTrackDetail(track: MusicTrack) = Unit
        override fun canSetSongDisliked(track: MusicTrack): Boolean = false
        override fun setSongDisliked(track: MusicTrack) = Unit
    }
}
