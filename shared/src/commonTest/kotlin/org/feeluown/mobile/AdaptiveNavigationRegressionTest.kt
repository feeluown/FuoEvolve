package org.feeluown.mobile

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveNavigationRegressionTest {
    @Test
    fun representativeWindowShapesKeepExpectedAdaptiveCapabilities() {
        listOf(
            WindowCase(
                width = 360.dp,
                height = 800.dp,
                listDetail = false,
                wide = false,
                persistentNavigation = false,
                fullPlayerTwoPane = false,
            ),
            WindowCase(
                width = 800.dp,
                height = 360.dp,
                listDetail = false,
                wide = false,
                persistentNavigation = false,
                fullPlayerTwoPane = false,
            ),
            WindowCase(
                width = 800.dp,
                height = 1280.dp,
                listDetail = true,
                wide = false,
                persistentNavigation = false,
                fullPlayerTwoPane = false,
            ),
            WindowCase(
                width = 840.dp,
                height = 600.dp,
                listDetail = true,
                wide = true,
                persistentNavigation = false,
                fullPlayerTwoPane = true,
            ),
            WindowCase(
                width = 900.dp,
                height = 520.dp,
                listDetail = true,
                wide = true,
                persistentNavigation = true,
                fullPlayerTwoPane = false,
            ),
            WindowCase(
                width = 1280.dp,
                height = 800.dp,
                listDetail = true,
                wide = true,
                persistentNavigation = true,
                fullPlayerTwoPane = true,
            ),
        ).forEach { case ->
            val layout = appLayoutInfoFor(case.width, case.height)
            assertEquals(case.listDetail, layout.useListDetailNavigation, "list-detail at ${case.width} x ${case.height}")
            assertEquals(case.wide, layout.useWideLayout, "wide layout at ${case.width} x ${case.height}")
            assertEquals(
                case.persistentNavigation,
                layout.usePersistentNavigation,
                "persistent navigation at ${case.width} x ${case.height}",
            )
            assertEquals(
                case.fullPlayerTwoPane,
                layout.useFullPlayerTwoPane,
                "full-player two-pane at ${case.width} x ${case.height}",
            )
        }
    }

    @Test
    fun adaptiveBreakpointsStayPinnedToTheirExactBoundaries() {
        assertFalse(appLayoutInfoFor(599.dp, 800.dp).useListDetailNavigation)
        assertTrue(appLayoutInfoFor(600.dp, 800.dp).useListDetailNavigation)

        assertFalse(appLayoutInfoFor(839.dp, 800.dp).useWideLayout)
        assertTrue(appLayoutInfoFor(840.dp, 800.dp).useWideLayout)

        assertFalse(appLayoutInfoFor(899.dp, 800.dp).usePersistentNavigation)
        assertTrue(appLayoutInfoFor(900.dp, 800.dp).usePersistentNavigation)

        val compactHeight = appLayoutInfoFor(900.dp, 479.dp)
        assertFalse(compactHeight.useListDetailNavigation)
        assertFalse(compactHeight.useWideLayout)
        assertFalse(compactHeight.usePersistentNavigation)

        val usableHeight = appLayoutInfoFor(900.dp, 480.dp)
        assertTrue(usableHeight.useListDetailNavigation)
        assertTrue(usableHeight.useWideLayout)
        assertTrue(usableHeight.usePersistentNavigation)

        assertFalse(appLayoutInfoFor(840.dp, 599.dp).useFullPlayerTwoPane)
        assertTrue(appLayoutInfoFor(840.dp, 600.dp).useFullPlayerTwoPane)
    }

    @Test
    fun listDetailPairRequiresBothUsableWindowSpaceAndAListPredecessor() {
        val tabletPortrait = appLayoutInfoFor(800.dp, 1280.dp)
        val phoneLandscape = appLayoutInfoFor(800.dp, 360.dp)
        val track = trackDetail()
        val playlist = playlistDetail()
        val feature = featureDetail()

        assertTrue(formsAdaptivePair(tabletPortrait, listOf(AppRoute.Search, track)))
        assertTrue(formsAdaptivePair(tabletPortrait, listOf(feature, playlist)))
        assertTrue(formsAdaptivePair(tabletPortrait, listOf(feature, playlist, track)))

        assertFalse(formsAdaptivePair(tabletPortrait, listOf(AppRoute.Home, track)))
        assertFalse(formsAdaptivePair(tabletPortrait, listOf(AppRoute.Search)))
        assertFalse(formsAdaptivePair(tabletPortrait, listOf(AppRoute.Search, videoDetail())))
        assertFalse(formsAdaptivePair(phoneLandscape, listOf(AppRoute.Search, track)))
    }

    @Test
    fun adaptiveRoleMatrixKeepsFullscreenMediaAndHomeOutsideListDetail() {
        assertFalse(AppRoute.Home.supportsAdaptiveListPane())
        assertFalse(AppRoute.Home.supportsAdaptiveDetailPane())

        assertTrue(AppRoute.Search.supportsAdaptiveListPane())
        assertFalse(AppRoute.Search.supportsAdaptiveDetailPane())

        val playlist = playlistDetail()
        assertTrue(playlist.supportsAdaptiveListPane())
        assertTrue(playlist.supportsAdaptiveDetailPane())

        val track = trackDetail()
        assertFalse(track.supportsAdaptiveListPane())
        assertTrue(track.supportsAdaptiveDetailPane())

        assertFalse(AppRoute.Video.supportsAdaptiveListPane())
        assertFalse(AppRoute.Video.supportsAdaptiveDetailPane())
        assertFalse(videoDetail().supportsAdaptiveListPane())
        assertFalse(videoDetail().supportsAdaptiveDetailPane())
    }

    @Test
    fun persistentRailRemainsShellOwnedAcrossHomeBackTransitions() {
        val persistent = appLayoutInfoFor(900.dp, 800.dp)
        assertTrue(
            shouldShowShellNavigationRail(
                layoutInfo = persistent,
                isFullPlayerOpen = false,
                isVideoFullscreen = false,
            )
        )

        // Home only hosts its own rail below the persistent-navigation breakpoint.
        val homeWouldHostEmbeddedRail = persistent.useWideLayout && !persistent.usePersistentNavigation
        assertFalse(homeWouldHostEmbeddedRail)

        val prePersistent = appLayoutInfoFor(840.dp, 800.dp)
        assertFalse(
            shouldShowShellNavigationRail(
                layoutInfo = prePersistent,
                isFullPlayerOpen = false,
                isVideoFullscreen = false,
            )
        )
        assertTrue(prePersistent.useWideLayout && !prePersistent.usePersistentNavigation)
    }

    @Test
    fun fullscreenSurfacesSuppressPersistentShellNavigation() {
        val persistent = appLayoutInfoFor(1280.dp, 800.dp)

        assertTrue(
            shouldShowShellNavigationRail(
                layoutInfo = persistent,
                isFullPlayerOpen = false,
                isVideoFullscreen = false,
            )
        )
        assertFalse(
            shouldShowShellNavigationRail(
                layoutInfo = persistent,
                isFullPlayerOpen = true,
                isVideoFullscreen = false,
            )
        )
        assertFalse(
            shouldShowShellNavigationRail(
                layoutInfo = persistent,
                isFullPlayerOpen = false,
                isVideoFullscreen = true,
            )
        )
    }

    private fun formsAdaptivePair(layout: AppLayoutInfo, backStack: List<AppRoute>): Boolean {
        val activeRoute = backStack.lastOrNull()
        return layout.useListDetailNavigation &&
            activeRoute?.supportsAdaptiveDetailPane() == true &&
            backStack.dropLast(1).any { it.supportsAdaptiveListPane() }
    }

    private fun featureDetail(): AppRoute.FeatureDetail = AppRoute.FeatureDetail(
        NavigationFeature(
            id = "feature",
            providerId = "provider",
            providerName = "Provider",
            title = "Feature",
            category = "Recommendation",
            contentType = "Songs",
            requiresLogin = false,
        )
    )

    private fun playlistDetail(): AppRoute.PlaylistDetail = AppRoute.PlaylistDetail(
        NavigationPlaylist(
            id = "playlist",
            title = "Playlist",
            providerId = "provider",
            providerName = "Provider",
        )
    )

    private fun trackDetail(): AppRoute.TrackDetail = AppRoute.TrackDetail(
        NavigationTrack(
            id = "track",
            title = "Track",
            artists = "Artist",
            album = "Album",
            source = "provider",
            sourceType = TrackSourceType.Provider.name,
        )
    )

    private fun videoDetail(): AppRoute.VideoDetail = AppRoute.VideoDetail(
        NavigationVideo(
            id = "video",
            title = "Video",
            artists = "Uploader",
            providerId = "provider",
            providerName = "Provider",
        )
    )

    private data class WindowCase(
        val width: Dp,
        val height: Dp,
        val listDetail: Boolean,
        val wide: Boolean,
        val persistentNavigation: Boolean,
        val fullPlayerTwoPane: Boolean,
    )
}
