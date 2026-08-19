package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigatorTest {
    @Test
    fun navigationOwnsAnOrderedBackStack() {
        val navigator = AppNavigator()

        navigator.navigate(AppRoute.Search)
        navigator.navigate(AppRoute.Track)

        assertEquals(listOf(AppRoute.Home, AppRoute.Search, AppRoute.Track), navigator.backStack.value)
        assertTrue(navigator.pop(AppRoute.Track))
        assertEquals(AppRoute.Search, navigator.currentRoute)
        assertTrue(navigator.pop())
        assertEquals(listOf(AppRoute.Home), navigator.backStack.value)
        assertFalse(navigator.pop())
    }

    @Test
    fun typedRoutesKeepIndependentResourceIdentity() {
        val navigator = AppNavigator()
        val first = NavigationTrack(
            id = "netease:1",
            title = "First",
            artists = "Artist",
            album = "Album",
            source = "netease",
            sourceType = TrackSourceType.Provider.name,
        )
        val second = first.copy(id = "netease:2", title = "Second")

        navigator.navigate(AppRoute.TrackDetail(first))
        navigator.navigate(AppRoute.TrackDetail(second))

        assertEquals(AppRoute.Track, navigator.currentRoute)
        assertEquals(AppRoute.TrackDetail(second), navigator.currentEntry)
        assertEquals(
            listOf(
                AppRoute.Home,
                AppRoute.TrackDetail(first),
                AppRoute.TrackDetail(second),
            ),
            navigator.backStack.value,
        )

        assertTrue(navigator.pop())
        assertEquals(AppRoute.TrackDetail(first), navigator.currentEntry)
        assertEquals(AppRoute.Track, navigator.currentRoute)
    }

    @Test
    fun typedTrackRouteRoundTripPreservesCompleteTrackMetadata() {
        val original = MusicTrack(
            id = "ytmusic:replacement",
            title = "Displayed title",
            artists = "Artist A / Artist B",
            album = "Displayed album",
            source = "ytmusic",
            sourceType = TrackSourceType.Provider,
            coverUrl = "https://example.com/displayed.jpg",
            durationMs = 210_000,
            localUri = "content://unused/provider-track",
            localDirectoryId = "unused/provider-dir/",
            lyrics = "lyrics",
            providerId = "ytmusic:replacement",
            providerName = "YouTube Music",
            isSmartReplacement = true,
            originalId = "netease:original",
            originalTitle = "Original title",
            originalArtists = "Original artist",
            originalAlbum = "Original album",
            originalSource = "netease",
            originalProviderName = "网易云音乐",
            originalCoverUrl = "https://example.com/original.jpg",
            replacementId = "ytmusic:replacement",
            replacementTitle = "Replacement title",
            replacementArtists = "Artist A / Artist B",
            replacementAlbum = "Replacement album",
            replacementSource = "ytmusic",
            replacementProviderName = "YouTube Music",
            replacementCoverUrl = "https://example.com/replacement.jpg",
            replacementStrategy = "smart",
            replacementScore = 0.93,
            isUnavailable = false,
            artistItemId = "artist:a",
            albumItemId = "album:1",
            artistItems = listOf(
                ProviderMediaItem(
                    id = "artist:a",
                    title = "Artist A",
                    providerId = "artist:a",
                    providerName = "YouTube Music",
                    type = ProviderMediaItemType.Artist,
                    coverUrl = "https://example.com/a.jpg",
                    providerUrl = "https://music.youtube.com/channel/a",
                ),
                ProviderMediaItem(
                    id = "artist:b",
                    title = "Artist B",
                    providerId = "artist:b",
                    providerName = "YouTube Music",
                    type = ProviderMediaItemType.Artist,
                    coverUrl = "https://example.com/b.jpg",
                    providerUrl = "https://music.youtube.com/channel/b",
                ),
            ),
            providerUrl = "https://music.youtube.com/watch?v=replacement",
            providerTags = listOf("official", "music-video"),
        )

        val navigator = AppNavigator()
        navigator.navigate(AppRoute.TrackDetail(original.toNavigationTrack()))
        navigator.navigate(
            AppRoute.TrackDetail(
                original.copy(id = "ytmusic:other", title = "Other").toNavigationTrack(),
            )
        )

        assertTrue(navigator.pop())
        val restoredRoute = navigator.currentEntry as AppRoute.TrackDetail

        assertEquals(original, restoredRoute.track.toMusicTrack())
    }

    @Test
    fun compatibilityPopMatchesTypedDetailRoute() {
        val navigator = AppNavigator()
        val track = NavigationTrack(
            id = "qqmusic:1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider.name,
        )
        navigator.navigate(AppRoute.Search)
        navigator.navigate(AppRoute.TrackDetail(track))

        assertTrue(navigator.contains(AppRoute.Track))
        assertTrue(navigator.pop(AppRoute.Track))
        assertEquals(AppRoute.Search, navigator.currentRoute)
    }

    @Test
    fun poppingAParentAlsoRemovesItsChildRoutes() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Settings)
        navigator.navigate(AppRoute.DebugLogs)

        navigator.pop(AppRoute.Settings)

        assertEquals(listOf(AppRoute.Home), navigator.backStack.value)
    }

    @Test
    fun localMusicCollectionUsesAnIndependentRoute() {
        val navigator = AppNavigator()

        navigator.navigate(AppRoute.LocalMusicCollection)

        assertEquals(listOf(AppRoute.Home, AppRoute.LocalMusicCollection), navigator.backStack.value)
        assertTrue(navigator.pop(AppRoute.LocalMusicCollection))
        assertEquals(listOf(AppRoute.Home), navigator.backStack.value)
    }
}
