package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

class NucleusTrayPlaybackControllerTest {
    @Test
    fun currentTrackLabelIncludesArtistWhenAvailable() {
        assertEquals(
            "当前曲目：Song · Artist",
            trayPlaybackTrackLabel(
                PlaybackSessionState(
                    currentTrack = TrackRef(
                        id = "1",
                        title = "Song",
                        artists = "Artist",
                        album = "Album",
                        source = "test",
                    ),
                ),
            ),
        )
        assertEquals("当前曲目：暂无播放", trayPlaybackTrackLabel(PlaybackSessionState()))
    }

    @Test
    fun playPauseLabelAndAvailabilityFollowPlaybackState() {
        val track = TrackRef(
            id = "1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "test",
        )
        assertEquals(
            "暂停",
            trayPlaybackToggleLabel(
                PlaybackSessionState(status = PlaybackSessionStatus.Playing, currentTrack = track),
            ),
        )
        assertEquals(
            "播放",
            trayPlaybackToggleLabel(
                PlaybackSessionState(status = PlaybackSessionStatus.Paused, currentTrack = track),
            ),
        )
        assertTrue(
            trayPlaybackCanToggle(
                PlaybackSessionState(status = PlaybackSessionStatus.Paused, currentTrack = track),
            ),
        )
        assertFalse(
            trayPlaybackCanToggle(
                PlaybackSessionState(status = PlaybackSessionStatus.Loading, currentTrack = track),
            ),
        )
        assertFalse(trayPlaybackCanToggle(PlaybackSessionState()))
    }

    @Test
    fun windowsJumpListUsesRequestedPlaybackActionOrder() {
        val tasks = windowsPlaybackJumpListTasks()
        assertEquals(listOf("播放/暂停", "上一首", "下一首"), tasks.map { it.title })
        assertEquals(
            listOf(
                DESKTOP_MEDIA_PLAY_PAUSE_ARGUMENT,
                DESKTOP_MEDIA_PREVIOUS_ARGUMENT,
                DESKTOP_MEDIA_NEXT_ARGUMENT,
            ),
            tasks.map { it.arguments },
        )
    }
}
