package org.feeluown.mobile.playback.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.feeluown.mobile.RepeatMode
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

/** Dynamic state emitted by the platform audio engine. */
data class PlaybackRuntimeEngineState(
    val status: PlaybackSessionStatus = PlaybackSessionStatus.Idle,
    val currentTrack: TrackRef? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val volume: Double = 1.0,
    val errorMessage: String? = null,
)

/** Queue/presentation state supplied by the current playback coordinator during migration. */
data class PlaybackRuntimeOverlay(
    val currentTrack: TrackRef? = null,
    val lyrics: String? = null,
    val lyricsAlignmentOffsetMs: Long = 0L,
    val queueTrackIds: List<String> = emptyList(),
    val queueIndex: Int = -1,
    val canonicalQueueTracks: List<TrackRef> = emptyList(),
    val canonicalQueueIndex: Int = -1,
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.QUEUE,
    val shuffleEnabled: Boolean = false,
    val canChangePlaybackMode: Boolean = true,
)

/** Minimal engine surface required by the app-scoped playback runtime. */
interface PlaybackRuntimeEngine {
    val state: StateFlow<PlaybackRuntimeEngineState>

    fun pause()
    fun resume()
    fun stop() = pause()
    fun seekTo(positionMs: Long) = Unit
    fun setVolume(volume: Double) = Unit
}

/**
 * Temporary queue bridge while queue selection/resource-resolution policy still lives in the
 * legacy playback coordinator. The runtime owns session state and transport policy; these callbacks
 * are the remaining queue-transition seam to remove in the next migration slice.
 */
interface PlaybackRuntimeQueueActions {
    fun startCurrent()
    fun previous()
    fun next()
    fun setRepeatMode(mode: RepeatMode) = Unit
    fun setShuffleEnabled(enabled: Boolean) = Unit
}

/**
 * Default app-scoped [PlaybackSession] implementation.
 *
 * This is the authoritative owner of the state consumed by platform playback integrations.
 * Engine timing/status and coordinator queue presentation are merged here rather than in Android
 * service code, and play/pause/toggle decisions are made here rather than delegated to the global
 * controller facade.
 *
 * The platform engine is authoritative for the media item that is actually playing. During queue
 * reconciliation the presentation overlay can briefly lag behind an engine transition; a stale
 * overlay must never replace the engine track or leak lyrics belonging to the previous track.
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
        val current = state.value
        if (
            current.currentTrack != null &&
            (
                current.status == PlaybackSessionStatus.Loading ||
                    current.status == PlaybackSessionStatus.Playing ||
                    current.status == PlaybackSessionStatus.Paused
            )
        ) {
            // Idempotent pause forwarding is intentional. Platform integrations may issue Pause
            // immediately after a queue transition while the combined session still exposes the
            // previous Paused snapshot; the engine already knows that the replacement track is
            // Loading and must retain the pause request through that asynchronous start.
            engine.pause()
        }
    }

    override fun stop() = engine.stop()

    override fun previous() = queueActions.previous()

    override fun next() = queueActions.next()

    override fun seekTo(positionMs: Long) {
        if (state.value.currentTrack == null) return
        val duration = state.value.durationMs
        engine.seekTo(
            if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L),
        )
    }

    override fun setVolume(volume: Double) {
        if (!volume.isFinite()) return
        engine.setVolume(volume.coerceIn(0.0, 1.0))
    }

    override fun setRepeatMode(mode: RepeatMode) {
        if (!state.value.canChangePlaybackMode || state.value.repeatMode == mode) return
        queueActions.setRepeatMode(mode)
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        if (!state.value.canChangePlaybackMode || state.value.shuffleEnabled == enabled) return
        queueActions.setShuffleEnabled(enabled)
    }
}

private fun composeState(
    engine: PlaybackRuntimeEngineState,
    overlay: PlaybackRuntimeOverlay,
): PlaybackSessionState {
    val engineTrack = engine.currentTrack
    val overlayMatchesEngine = engineTrack == null || overlay.currentTrack?.id == engineTrack.id
    val currentTrack = engineTrack ?: overlay.currentTrack
    val lyricsAlignmentOffsetMs = if (overlayMatchesEngine) overlay.lyricsAlignmentOffsetMs else 0L
    val queueTrackIds = if (overlayMatchesEngine) {
        overlay.queueTrackIds
    } else {
        currentTrack?.let { listOf(it.id) }.orEmpty()
    }
    val queueIndex = if (overlayMatchesEngine) overlay.queueIndex else currentTrack?.let { 0 } ?: -1
    val canonicalQueueTracks = if (overlayMatchesEngine) {
        overlay.canonicalQueueTracks
    } else {
        currentTrack?.let(::listOf).orEmpty()
    }
    val canonicalQueueIndex = if (overlayMatchesEngine) {
        overlay.canonicalQueueIndex
    } else {
        currentTrack?.let { 0 } ?: -1
    }

    return PlaybackSessionState(
        status = engine.status,
        currentTrack = currentTrack,
        positionMs = engine.positionMs,
        lyricsPositionMs = (engine.positionMs - lyricsAlignmentOffsetMs).coerceAtLeast(0L),
        lyricsAlignmentOffsetMs = lyricsAlignmentOffsetMs,
        durationMs = engine.durationMs,
        bufferedMs = engine.bufferedMs,
        volume = engine.volume.coerceIn(0.0, 1.0),
        lyrics = overlay.lyrics.takeIf { overlayMatchesEngine },
        queueTrackIds = queueTrackIds,
        queueIndex = queueIndex,
        canonicalQueueTracks = canonicalQueueTracks,
        canonicalQueueIndex = canonicalQueueIndex,
        canGoNext = overlay.canGoNext && overlayMatchesEngine,
        canGoPrevious = overlay.canGoPrevious && overlayMatchesEngine,
        repeatMode = overlay.repeatMode,
        shuffleEnabled = overlay.shuffleEnabled,
        canChangePlaybackMode = overlay.canChangePlaybackMode,
        errorMessage = engine.errorMessage,
    )
}
