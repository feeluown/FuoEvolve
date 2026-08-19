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
