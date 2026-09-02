package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackEngine
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackResumeSnapshot
import org.feeluown.mobile.PlaybackResumeStore
import org.feeluown.mobile.PlaybackStartReason
import org.feeluown.mobile.PlaybackState
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.ResolvedPlaybackSourceAwareEngine
import org.feeluown.mobile.TrackSourceType

class PersistentDesktopPlaybackEngineTest {
    @Test
    fun restoredStateIsVisibleUntilUserSelectsAnotherTrack() {
        val restoredTrack = track("a")
        val selectedTrack = track("b")
        val store = FakeResumeStore(
            PlaybackResumeSnapshot(
                currentTrack = restoredTrack,
                positionMs = 42_000L,
                durationMs = 180_000L,
            ),
        )
        val delegate = FakePlaybackEngine()
        val engine = PersistentDesktopPlaybackEngine(delegate, store)

        assertEquals(PlayerStatus.Paused, engine.state.value.status)
        assertEquals(restoredTrack.id, engine.state.value.currentTrack?.id)
        assertEquals(42_000L, engine.state.value.positionMs)

        engine.prepareLoading(selectedTrack, PlaybackStartReason.USER_SELECTION)

        assertTrue(store.cleared)
        assertEquals(selectedTrack.id, delegate.preparedTrack?.id)
        engine.close()
    }

    @Test
    fun resumeReResolvesTrackThenSeeksToPersistedPosition() {
        val restoredTrack = track("a")
        val store = FakeResumeStore(
            PlaybackResumeSnapshot(
                currentTrack = restoredTrack,
                positionMs = 42_000L,
                durationMs = 180_000L,
            ),
        )
        val delegate = FakePlaybackEngine()
        val engine = PersistentDesktopPlaybackEngine(delegate, store)

        engine.prepareLoading(restoredTrack, PlaybackStartReason.RESUME)
        engine.playResolved(
            logicalTrack = restoredTrack,
            resolveTrack = restoredTrack,
            payload = payload(restoredTrack),
        )

        repeat(100) {
            if (delegate.lastSeekPositionMs == 42_000L) return@repeat
            Thread.sleep(5)
        }
        assertEquals(42_000L, delegate.lastSeekPositionMs)
        engine.close()
    }

    private fun track(id: String) = MusicTrack(
        id = "netease:$id",
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        durationMs = 180_000L,
        providerId = "netease:$id",
        providerName = "网易云音乐",
    )

    private fun payload(track: MusicTrack) = PlaybackPayload(
        url = "https://example.invalid/${track.id}.mp3",
        title = track.title,
        artists = track.artists,
        album = track.album,
        source = track.source,
        durationMs = track.durationMs,
    )
}

private class FakeResumeStore(
    private var snapshot: PlaybackResumeSnapshot?,
) : PlaybackResumeStore {
    var cleared = false
    override fun load(): PlaybackResumeSnapshot? = snapshot
    override fun saveSession(state: PlaybackState) {
        val track = state.currentTrack ?: return
        snapshot = PlaybackResumeSnapshot(track, state.positionMs, state.durationMs, state.playbackParts, state.currentPartIndex)
    }
    override fun savePosition(positionMs: Long, durationMs: Long) {
        snapshot = snapshot?.copy(positionMs = positionMs, durationMs = durationMs)
    }
    override fun flush() = Unit
    override fun clear() {
        cleared = true
        snapshot = null
    }
}

private class FakePlaybackEngine : PlaybackEngine, ResolvedPlaybackSourceAwareEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    var preparedTrack: MusicTrack? = null
    var lastSeekPositionMs: Long? = null

    override fun prepareLoading(track: MusicTrack) {
        preparedTrack = track
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track,
            durationMs = track.durationMs ?: 0L,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        mutableState.value = PlaybackState(
            status = PlayerStatus.Playing,
            currentTrack = track,
            durationMs = payload.durationMs ?: track.durationMs ?: 0L,
        )
    }

    override fun playResolved(
        logicalTrack: MusicTrack,
        resolveTrack: MusicTrack,
        payload: PlaybackPayload,
    ) {
        play(logicalTrack, payload)
    }

    override fun pause() {
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
    }

    override fun resume() {
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        mutableState.value = PlaybackState()
    }

    override fun seekTo(positionMs: Long) {
        lastSeekPositionMs = positionMs
        mutableState.value = mutableState.value.copy(positionMs = positionMs)
    }
}
