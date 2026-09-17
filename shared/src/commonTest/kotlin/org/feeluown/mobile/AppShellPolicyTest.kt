package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShellPolicyTest {
    @Test
    fun homeNeedsCurrentTrackForMiniPlayer() {
        assertTrue(AppRoute.Home.showsMiniPlayer(hasCurrentTrack = true, hasQueueTrack = false))
        assertFalse(AppRoute.Home.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true))
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

        assertTrue(route.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true))
        assertFalse(route.showsMiniPlayer(hasCurrentTrack = true, hasQueueTrack = false))
    }

    @Test
    fun playlistMigrationDetailUsesQueueTrack() {
        val route = AppRoute.PlaylistMigrationDetail(
            taskId = "migration-123",
            target = PlaylistMigrationOpenTarget.Review,
        )

        assertTrue(route.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true))
        assertFalse(route.showsMiniPlayer(hasCurrentTrack = true, hasQueueTrack = false))
    }

    @Test
    fun playbackHistoryUsesQueueTrack() {
        assertTrue(AppRoute.PlaybackHistory.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true))
        assertFalse(AppRoute.PlaybackHistory.showsMiniPlayer(hasCurrentTrack = true, hasQueueTrack = false))
    }

    @Test
    fun videoDetailNeverShowsMiniPlayer() {
        val route = AppRoute.VideoDetail(
            NavigationVideo(
                id = "bilibili:BV1",
                title = "Video",
                artists = "Uploader",
                providerId = "bilibili:BV1",
                providerName = "哔哩哔哩",
            )
        )

        assertFalse(route.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = true))
        assertFalse(route.showsMiniPlayer(hasCurrentTrack = false, hasQueueTrack = false))
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
            assertFalse(route.showsMiniPlayer(hasCurrentTrack = true, hasQueueTrack = true))
        }
    }
}
