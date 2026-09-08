package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType

class DesktopMpvPlaybackStatusSyncTest {
    @Test
    fun pausePropertyConfirmsPlayingWithoutPlaybackRestart() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("netease:status")

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 101L))

        assertEquals(PlayerStatus.Loading, engine.state.value.status)

        backend.emit(DesktopMpvBackendEvent.Property("pause", "no"))

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
    }

    @Test
    fun advancingTimelineConfirmsPlayingWithoutPlaybackRestart() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("qqmusic:status")

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 102L))
        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "0"))

        assertEquals(PlayerStatus.Loading, engine.state.value.status)

        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "0.25"))

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
        assertEquals(250L, engine.state.value.positionMs)
    }

    @Test
    fun pausePropertyCanConfirmPausedWhileLoading() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("local:status", TrackSourceType.LocalMediaStore)

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 103L))
        backend.emit(DesktopMpvBackendEvent.Property("pause", "yes"))

        assertEquals(PlayerStatus.Paused, engine.state.value.status)
    }

    private fun payload(track: MusicTrack) = PlaybackPayload(
        url = "https://example.test/status.mp3",
        title = track.title,
        artists = track.artists,
        album = track.album,
        source = track.source,
        durationMs = track.durationMs,
    )

    private fun track(
        id: String,
        sourceType: TrackSourceType = TrackSourceType.Provider,
    ) = MusicTrack(
        id = id,
        title = "Status Track",
        artists = "Artist",
        album = "Album",
        source = id.substringBefore(':'),
        sourceType = sourceType,
        durationMs = 100_000L,
        providerId = id,
        providerName = id.substringBefore(':'),
    )
}

private class StatusSyncFakeDesktopMpvBackend(
    private val listener: (DesktopMpvBackendEvent) -> Unit,
) : DesktopMpvBackend {
    override fun load(url: String, headers: Map<String, String>) = Unit

    override fun setPaused(paused: Boolean) = Unit

    override fun stop() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun close() = Unit

    fun emit(event: DesktopMpvBackendEvent) {
        listener(event)
    }
}
