package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShellPolicyTest {
    @Test
    fun homeNeedsCurrentTrackForMiniPlayer() {
        assertTrue(AppRoute.Home.showsMiniPlayer(hasCurrentTrack = true, hasQueueTrack = false, isVideoFullscreen = false))
        assertFalse(AppRoute.Home.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true, isVideoFullscreen = false))
    }

    @Test
    fun detailRoutesUseQueueTrack() {
        val route = AppRoute.TrackDetail(
            NavigationTrack(
                id = "netease:1",
                title = "Song",
                artists = "Artist",
                album = "Album",
                source = "netease",
                sourceType = TrackSourceType.Provider.name,
            )
        )

        assertTrue(route.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true, isVideoFullscreen = false))
        assertFalse(route.showsMiniPlayer(hasCurrentTrack = true, hasQueueTrack = false, isVideoFullscreen = false))
    }

    @Test
    fun fullscreenVideoSuppressesMiniPlayer() {
        val route = AppRoute.VideoDetail(
            NavigationVideo(
                id = "bilibili:BV1",
                title = "Video",
                providerId = "bilibili:BV1",
                providerName = "哔哩哔哩",
            )
        )

        assertTrue(route.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true, isVideoFullscreen = false))
        assertFalse(route.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true, isVideoFullscreen = true))
    }

    @Test
    fun utilityRoutesNeverShowMiniPlayer() {
        listOf(
            AppRoute.Search,
            AppRoute.AudioRecognition,
            AppRoute.Settings,
            AppRoute.DebugLogs,
            AppRoute.DownloadManager,
        ).forEach { route ->
            assertFalse(route.showsMiniPlayer(hasCurrentTrack = true, hasQueueTrack = true, isVideoFullscreen = false))
        }
    }
}
