package org.feeluown.mobile.playback.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

/** Dynamic state emitted by the platform audio engine. */
data class PlaybackRuntimeEngineState(
    val status: PlaybackSessionStatus = PlaybackSessionStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val errorMessage: String? = null,
)

/** Queue/presentation state supplied by the current playback coordinator during migration. */
data class PlaybackRuntimeOverlay(
    val currentTrack: TrackRef? = null,
    val lyrics: String? = null,
    val lyricsAlignmentOffsetMs: Long = 0L,
    val queueTrackIds: List<String> = emptyList(),
    val queueIndex: Int = -1,
)

/** Minimal engine surface required by the app-scoped playback runtime. */
interface PlaybackRuntimeEngine {
    val state: StateFlow<PlaybackRuntimeEngineState>

    fun pause()
    fun resume()
}

/**
 * Temporary queue bridge while queue selection/resource-resolution policy still lives in the
 * legacy playback coordinator. The runtime owns session state and transport policy; these three
 * callbacks are the remaining queue-transition seam to remove in the next migration slice.
 */
interface PlaybackRuntimeQueueActions {
    fun startCurrent()
    fun previous()
    fun next()
}

/**
 * Default app-scoped [PlaybackSession] implementation.
 *
 * This is the authoritative owner of the state consumed by platform playback integrations.
 * Engine timing/status and coordinator queue presentation are merged here rather than in Android
 * service code, and play/pause/toggle decisions are made here rather than delegated to the global
 * controller facade.
 */
class DefaultPlaybackRuntime(
    private val engine: PlaybackRuntimeEngine,
    private val overlay: StateFlow<PlaybackRuntimeOverlay>,
    private val queueActions: PlaybackRuntimeQueueActions,
    scope: CoroutineScope,
) : PlaybackSession {
    override val state: StateFlow<PlaybackSessionState> = combine(
        engine.state,
        overlay,
        ::composeState,
    ).stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = composeState(engine.state.value, overlay.value),
    )

    override fun toggle() {
        when (state.value.status) {
            PlaybackSessionStatus.Playing -> engine.pause()
            PlaybackSessionStatus.Paused -> {
                if (state.value.currentTrack != null) engine.resume()
            }
            PlaybackSessionStatus.Idle,
            PlaybackSessionStatus.Loading,
            PlaybackSessionStatus.Error,
            PlaybackSessionStatus.Ended -> queueActions.startCurrent()
        }
    }

    override fun play() {
        when (state.value.status) {
            PlaybackSessionStatus.Playing -> Unit
            PlaybackSessionStatus.Paused -> {
                if (state.value.currentTrack != null) engine.resume()
            }
            PlaybackSessionStatus.Idle,
            PlaybackSessionStatus.Loading,
            PlaybackSessionStatus.Error,
            PlaybackSessionStatus.Ended -> queueActions.startCurrent()
        }
    }

    override fun pause() {
        if (state.value.status == PlaybackSessionStatus.Playing) {
            engine.pause()
        }
    }

    override fun previous() = queueActions.previous()

    override fun next() = queueActions.next()
}

private fun composeState(
    engine: PlaybackRuntimeEngineState,
    overlay: PlaybackRuntimeOverlay,
): PlaybackSessionState = PlaybackSessionState(
    status = engine.status,
    currentTrack = overlay.currentTrack,
    positionMs = engine.positionMs,
    lyricsPositionMs = (engine.positionMs - overlay.lyricsAlignmentOffsetMs).coerceAtLeast(0L),
    lyricsAlignmentOffsetMs = overlay.lyricsAlignmentOffsetMs,
    durationMs = engine.durationMs,
    bufferedMs = engine.bufferedMs,
    lyrics = overlay.lyrics,
    queueTrackIds = overlay.queueTrackIds,
    queueIndex = overlay.queueIndex,
    errorMessage = engine.errorMessage,
)
