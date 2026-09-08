package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType

class DesktopMpvPlaybackStatusSyncTest {
    @Test
    fun pausePropertyDoesNotConfirmPlaybackWhileLoading() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("netease:status")

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 101L))

        assertEquals(PlayerStatus.Loading, engine.state.value.status)

        backend.emit(DesktopMpvBackendEvent.Property("pause", "no"))

        assertEquals(PlayerStatus.Loading, engine.state.value.status)
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
    fun fileLoadedKeepsTimelineSyncWorkingWhenStartFileDataIsMissing() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("netease:file-loaded-fallback")

        engine.play(track, payload(track))

        // A property event before the new file is known to be loaded can still belong to the
        // previous item and must not leak into the new logical playback transaction.
        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "15.0"))
        assertEquals(PlayerStatus.Loading, engine.state.value.status)
        assertEquals(0L, engine.state.value.positionMs)

        // Even FILE_LOADED itself can be stale because loadfile/stop processing is asynchronous.
        backend.emit(DesktopMpvBackendEvent.FileLoaded(path = "https://example.test/stale.mp3"))
        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "16.0"))
        assertEquals(PlayerStatus.Loading, engine.state.value.status)
        assertEquals(0L, engine.state.value.positionMs)

        // Some libmpv/ABI combinations can fail to expose START_FILE data while still delivering
        // FILE_LOADED and property-change events. A correlated FILE_LOADED must unlock sync.
        backend.emit(DesktopMpvBackendEvent.FileLoaded(path = STATUS_URL))
        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "0"))
        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "0.25"))

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
        assertEquals(250L, engine.state.value.positionMs)
    }

    @Test
    fun staleEndFileIsIgnoredAfterFileLoadedFallbackRestoresPlaylistEntryId() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val firstTrack = track("netease:first")
        val replacementTrack = track("qqmusic:file-loaded-end-file")

        engine.play(firstTrack, payload(firstTrack))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 101L))
        backend.emit(DesktopMpvBackendEvent.FileLoaded(path = STATUS_URL, playlistEntryId = 101L))
        backend.emit(DesktopMpvBackendEvent.PlaybackRestart)
        assertEquals(PlayerStatus.Playing, engine.state.value.status)

        // The replacement reaches FILE_LOADED without usable START_FILE data. The FILE_LOADED
        // correlation must carry its playlist entry id so the delayed END_FILE from the first item
        // cannot terminate the replacement playback transaction.
        engine.play(replacementTrack, payload(replacementTrack))
        backend.emit(DesktopMpvBackendEvent.FileLoaded(path = STATUS_URL, playlistEntryId = 202L))
        backend.emit(DesktopMpvBackendEvent.PlaybackRestart)
        assertEquals(PlayerStatus.Playing, engine.state.value.status)

        backend.emit(
            DesktopMpvBackendEvent.EndFile(
                playlistEntryId = 101L,
                reason = END_FILE_REASON_STOP,
            ),
        )
        assertEquals(PlayerStatus.Playing, engine.state.value.status)

        backend.emit(
            DesktopMpvBackendEvent.EndFile(
                playlistEntryId = 202L,
                reason = END_FILE_REASON_EOF,
            ),
        )
        assertEquals(PlayerStatus.Ended, engine.state.value.status)
    }

    @Test
    fun fileLoadedAllowsPlaybackRestartWhenStartFileDataIsMissing() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("qqmusic:file-loaded-restart")

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.FileLoaded(path = STATUS_URL))
        backend.emit(DesktopMpvBackendEvent.PlaybackRestart)

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
    }

    @Test
    fun playbackRestartBeforeFileLoadedIsAppliedAfterActivation() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("youtube:restart-before-file-loaded")

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.PlaybackRestart)

        // PLAYBACK_RESTART can be observed before FILE_LOADED is processed. Do not publish Playing
        // until the requested file is correlated, but also do not lose that confirmation forever.
        assertEquals(PlayerStatus.Loading, engine.state.value.status)

        backend.emit(DesktopMpvBackendEvent.FileLoaded(path = STATUS_URL, playlistEntryId = 201L))

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "0.25"))
        assertEquals(250L, engine.state.value.positionMs)
    }

    @Test
    fun firstPositiveTimelineSampleDoesNotConfirmPlayback() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("youtube:status")

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 103L))
        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "10.0"))

        assertEquals(PlayerStatus.Loading, engine.state.value.status)
        assertEquals(10_000L, engine.state.value.positionMs)

        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "10.25"))

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
        assertEquals(10_250L, engine.state.value.positionMs)
    }

    @Test
    fun playbackRestartConfirmsPlayingImmediately() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("bilibili:status")

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 104L))
        backend.emit(DesktopMpvBackendEvent.PlaybackRestart)

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
    }

    @Test
    fun pausePropertyCanConfirmPausedWhileLoading() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("local:status", TrackSourceType.LocalMediaStore)

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 105L))
        backend.emit(DesktopMpvBackendEvent.Property("pause", "yes"))

        assertEquals(PlayerStatus.Paused, engine.state.value.status)
    }

    @Test
    fun resumingUnconfirmedPausedLoadReturnsToLoading() {
        lateinit var backend: StatusSyncFakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            StatusSyncFakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track("netease:resume")

        engine.play(track, payload(track))
        backend.emit(DesktopMpvBackendEvent.StartFile(playlistEntryId = 106L))
        engine.pause()
        assertEquals(PlayerStatus.Paused, engine.state.value.status)

        engine.resume()

        assertEquals(PlayerStatus.Loading, engine.state.value.status)
    }

    private fun payload(track: MusicTrack) = PlaybackPayload(
        url = STATUS_URL,
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

    private companion object {
        const val STATUS_URL = "https://example.test/status.mp3"
        const val END_FILE_REASON_EOF = 0
        const val END_FILE_REASON_STOP = 2
    }
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
