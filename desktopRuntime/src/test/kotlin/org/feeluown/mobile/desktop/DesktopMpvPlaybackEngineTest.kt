package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType

class DesktopMpvPlaybackEngineTest {
    @Test
    fun reloadingAfterPauseClearsNativePauseBeforePlaybackRestart() {
        lateinit var listener: (DesktopMpvBackendEvent) -> Unit
        val backend = RecordingBackend()
        val engine = DesktopMpvPlaybackEngine { callback ->
            listener = callback
            backend
        }
        val track = track("netease:paused-reload")
        val payload = payload(track)

        engine.play(track, payload)
        listener(DesktopMpvBackendEvent.StartFile(1L))
        listener(DesktopMpvBackendEvent.FileLoaded(payload.url, 1L))
        listener(DesktopMpvBackendEvent.Property("pause", if (backend.nativePaused) "yes" else "no"))
        listener(DesktopMpvBackendEvent.PlaybackRestart)
        assertEquals(PlayerStatus.Playing, engine.state.value.status)

        engine.pause()
        assertTrue(backend.nativePaused)

        engine.prepareLoading(track)
        engine.play(track, payload)
        assertFalse(backend.nativePaused)

        listener(DesktopMpvBackendEvent.StartFile(2L))
        listener(DesktopMpvBackendEvent.FileLoaded(payload.url, 2L))
        listener(DesktopMpvBackendEvent.Property("pause", if (backend.nativePaused) "yes" else "no"))
        listener(DesktopMpvBackendEvent.PlaybackRestart)

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
        engine.close()
    }

    private fun track(id: String) = MusicTrack(
        id = id,
        title = "Track",
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        durationMs = 180_000L,
        providerId = id,
        providerName = "网易云音乐",
    )

    private fun payload(track: MusicTrack) = PlaybackPayload(
        url = "https://example.test/${track.id}.mp3",
        title = track.title,
        artists = track.artists,
        album = track.album,
        source = track.source,
        durationMs = track.durationMs,
    )
}

private class RecordingBackend : DesktopMpvBackend {
    var nativePaused = false

    override fun load(url: String, headers: Map<String, String>) = Unit

    override fun setPaused(paused: Boolean) {
        nativePaused = paused
    }

    override fun stop() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun close() = Unit
}
