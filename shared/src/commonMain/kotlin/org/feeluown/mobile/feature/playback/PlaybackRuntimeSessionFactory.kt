package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import org.feeluown.mobile.playback.runtime.DefaultPlaybackRuntime
import org.feeluown.mobile.playback.runtime.PlaybackRuntimeEngine
import org.feeluown.mobile.playback.runtime.PlaybackRuntimeEngineState
import org.feeluown.mobile.playback.runtime.PlaybackRuntimeOverlay
import org.feeluown.mobile.playback.runtime.PlaybackRuntimeQueueActions

/**
 * Shared composition adapter from the application playback owners to the narrow PlaybackSession API.
 * Platform hosts only choose the resume transaction; state mapping and queue bridging stay identical.
 *
 * This factory is public because Android's composition root lives in the separate :androidApp
 * Gradle module. The implementation details below remain internal to :shared.
 */
fun createSharedPlaybackRuntimeSession(
    playbackState: StateFlow<PlaybackState>,
    playbackEngine: PlaybackEngine,
    transportCoordinator: PlaybackTransportCoordinator,
    startFailureSource: PlaybackStartFailureSource,
    scope: CoroutineScope,
    resumePlayback: () -> Unit,
): PlaybackSession {
    val queueStateFlow = transportCoordinator.queueStateFlow
    val overlayFlow = if (queueStateFlow != null) {
        combine(playbackState, queueStateFlow) { state, queueState ->
            val canonicalQueue = playbackRuntimeCanonicalQueue(queueState)
            state.toPlaybackRuntimeOverlay(
                canGoNext = playbackRuntimeCanGoNext(state, queueState),
                canGoPrevious = playbackRuntimeCanGoPrevious(state, queueState),
                repeatMode = queueState.repeatMode,
                shuffleEnabled = queueState.shuffleEnabled,
                canChangePlaybackMode = !queueState.isFmQueue,
                canonicalQueueTracks = canonicalQueue.tracks,
                canonicalQueueIndex = canonicalQueue.index,
            )
        }
    } else {
        playbackState.map {
            it.toPlaybackRuntimeOverlay(
                repeatMode = transportCoordinator.repeatMode,
                shuffleEnabled = transportCoordinator.isShuffleEnabled,
                canChangePlaybackMode = !transportCoordinator.isFmQueueActive,
                canonicalQueueTracks = it.queue,
                canonicalQueueIndex = it.queueIndex,
            )
        }
    }
    val initialQueueState = queueStateFlow?.value
    val initialCanonicalQueue = initialQueueState?.let(::playbackRuntimeCanonicalQueue)
    val overlay = overlayFlow
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = playbackState.value.toPlaybackRuntimeOverlay(
                canGoNext = initialQueueState?.let { playbackRuntimeCanGoNext(playbackState.value, it) }
                    ?: fallbackCanGoNext(playbackState.value),
                canGoPrevious = initialQueueState?.let { playbackRuntimeCanGoPrevious(playbackState.value, it) }
                    ?: fallbackCanGoPrevious(playbackState.value),
                repeatMode = initialQueueState?.repeatMode ?: transportCoordinator.repeatMode,
                shuffleEnabled = initialQueueState?.shuffleEnabled ?: transportCoordinator.isShuffleEnabled,
                canChangePlaybackMode = !(initialQueueState?.isFmQueue ?: transportCoordinator.isFmQueueActive),
                canonicalQueueTracks = initialCanonicalQueue?.tracks ?: playbackState.value.queue,
                canonicalQueueIndex = initialCanonicalQueue?.index ?: playbackState.value.queueIndex,
            ),
        )

    return DefaultPlaybackRuntime(
        engine = PlaybackRuntimeEngineAdapter(
            playbackEngine = playbackEngine,
            startFailureSource = startFailureSource,
            scope = scope,
            resumePlayback = resumePlayback,
        ),
        overlay = overlay,
        queueActions = PlaybackCoordinatorQueueActions(transportCoordinator),
        scope = scope,
    )
}

private class PlaybackRuntimeEngineAdapter(
    private val playbackEngine: PlaybackEngine,
    startFailureSource: PlaybackStartFailureSource,
    scope: CoroutineScope,
    private val resumePlayback: () -> Unit,
) : PlaybackRuntimeEngine {
    override val state: StateFlow<PlaybackRuntimeEngineState> = combine(
        playbackEngine.state,
        startFailureSource.startFailure,
    ) { engineState, startFailure ->
        mergePlaybackStartFailure(engineState, startFailure)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = mergePlaybackStartFailure(
            playbackEngine.state.value,
            startFailureSource.startFailure.value,
        ),
    )

    override fun pause() = playbackEngine.pause()

    override fun resume() = resumePlayback()

    override fun stop() = playbackEngine.stop()

    override fun seekTo(positionMs: Long) = playbackEngine.seekTo(positionMs)

    override fun setVolume(volume: Double) = playbackEngine.setVolume(volume)
}

private class PlaybackCoordinatorQueueActions(
    private val coordinator: PlaybackTransportCoordinator,
) : PlaybackRuntimeQueueActions {
    override fun startCurrent() = coordinator.startCurrent()

    override fun previous() = coordinator.previous()

    override fun next() = coordinator.next()

    override fun setRepeatMode(mode: RepeatMode) {
        if (coordinator.isFmQueueActive || coordinator.repeatMode == mode) return
        repeat(RepeatMode.entries.size) {
            coordinator.toggleRepeat()
            if (coordinator.repeatMode == mode) return
        }
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        if (coordinator.isFmQueueActive || coordinator.isShuffleEnabled == enabled) return
        coordinator.toggleShuffle()
    }
}

/** Pre-engine resource failures are published by PlaybackStartCoordinator. */
internal fun mergePlaybackStartFailure(
    engineState: PlaybackState,
    startFailure: PlaybackStartFailure?,
): PlaybackRuntimeEngineState {
    val activeFailure = startFailure?.takeIf { failure ->
        engineState.status == PlayerStatus.Loading && engineState.currentTrack?.id == failure.trackId
    }
    return PlaybackRuntimeEngineState(
        status = if (activeFailure != null) PlaybackSessionStatus.Error else engineState.status.toPlaybackSessionStatus(),
        currentTrack = engineState.currentTrack?.logicalPlaybackTrack()?.toTrackRef(),
        positionMs = engineState.positionMs,
        durationMs = engineState.durationMs,
        bufferedMs = engineState.bufferedMs,
        volume = engineState.volume,
        errorMessage = activeFailure?.message ?: engineState.errorMessage,
    )
}

internal fun playbackRuntimeCanGoNext(
    playbackState: PlaybackState,
    queueState: PlaybackQueueState,
): Boolean {
    val queueTrack = queueState.currentTrack()
    if (queueState.repeatMode == RepeatMode.SINGLE) {
        return queueTrack != null || playbackState.currentTrack != null
    }

    val parts = playbackState.playbackParts
    val partIndex = playbackState.currentPartIndex
    if (queueTrack != null && partIndex in parts.indices && partIndex < parts.lastIndex) return true
    if (queueState.upNextQueue.isNotEmpty()) return true
    if (queueState.mainQueue.isEmpty()) return false
    if (queueState.queueFeature != null && queueState.mainQueueIndex >= queueState.mainQueue.lastIndex) return true

    val nextMainIndex = queueState.mainQueueIndex + 1
    return nextMainIndex < queueState.mainQueue.size || queueState.repeatMode == RepeatMode.QUEUE
}

internal fun playbackRuntimeCanGoPrevious(
    playbackState: PlaybackState,
    queueState: PlaybackQueueState,
): Boolean {
    val queueTrack = queueState.currentTrack()
    if (queueState.repeatMode == RepeatMode.SINGLE) {
        return queueTrack != null || playbackState.currentTrack != null
    }

    val parts = playbackState.playbackParts
    val partIndex = playbackState.currentPartIndex
    if (queueTrack != null && partIndex in parts.indices && partIndex > 0) return true
    if (queueState.currentIsUpNext) return queueState.mainQueue.isNotEmpty()
    if (queueState.mainQueue.isEmpty()) return false

    val previousMainIndex = queueState.mainQueueIndex - 1
    return previousMainIndex >= 0 || queueState.repeatMode == RepeatMode.QUEUE
}

internal data class PlaybackRuntimeCanonicalQueue(
    val tracks: List<MusicTrack>,
    val index: Int,
)

internal fun playbackRuntimeCanonicalQueue(queueState: PlaybackQueueState): PlaybackRuntimeCanonicalQueue {
    val currentUpNextTrack = queueState.currentUpNextTrack
    if (queueState.currentIsUpNext && currentUpNextTrack != null) {
        val insertionIndex = (queueState.mainQueueIndex + 1).coerceIn(0, queueState.mainQueue.size)
        return PlaybackRuntimeCanonicalQueue(
            tracks = buildList {
                addAll(queueState.mainQueue.take(insertionIndex))
                add(currentUpNextTrack)
                addAll(queueState.upNextQueue)
                addAll(queueState.mainQueue.drop(insertionIndex))
            },
            index = insertionIndex,
        )
    }

    if (queueState.mainQueueIndex in queueState.mainQueue.indices) {
        val insertionIndex = queueState.mainQueueIndex + 1
        return PlaybackRuntimeCanonicalQueue(
            tracks = buildList {
                addAll(queueState.mainQueue.take(insertionIndex))
                addAll(queueState.upNextQueue)
                addAll(queueState.mainQueue.drop(insertionIndex))
            },
            index = queueState.mainQueueIndex,
        )
    }

    return PlaybackRuntimeCanonicalQueue(
        tracks = queueState.upNextQueue + queueState.mainQueue,
        index = -1,
    )
}

private fun PlaybackState.toPlaybackRuntimeOverlay(
    canGoNext: Boolean = fallbackCanGoNext(this),
    canGoPrevious: Boolean = fallbackCanGoPrevious(this),
    repeatMode: RepeatMode = RepeatMode.QUEUE,
    shuffleEnabled: Boolean = false,
    canChangePlaybackMode: Boolean = true,
    canonicalQueueTracks: List<MusicTrack> = queue,
    canonicalQueueIndex: Int = queueIndex,
): PlaybackRuntimeOverlay =
    PlaybackRuntimeOverlay(
        currentTrack = currentTrack?.toTrackRef(),
        lyrics = lyrics,
        lyricsAlignmentOffsetMs = lyricsAlignmentOffsetMs,
        queueTrackIds = queue.map(MusicTrack::id),
        queueIndex = queueIndex,
        canonicalQueueTracks = canonicalQueueTracks.map(MusicTrack::toTrackRef),
        canonicalQueueIndex = canonicalQueueIndex,
        canGoNext = canGoNext,
        canGoPrevious = canGoPrevious,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        canChangePlaybackMode = canChangePlaybackMode,
    )

private fun fallbackCanGoNext(state: PlaybackState): Boolean =
    state.queueIndex >= 0 && state.queueIndex < state.queue.lastIndex

private fun fallbackCanGoPrevious(state: PlaybackState): Boolean = state.queueIndex > 0

private fun PlayerStatus.toPlaybackSessionStatus(): PlaybackSessionStatus = when (this) {
    PlayerStatus.Idle -> PlaybackSessionStatus.Idle
    PlayerStatus.Loading -> PlaybackSessionStatus.Loading
    PlayerStatus.Playing -> PlaybackSessionStatus.Playing
    PlayerStatus.Paused -> PlaybackSessionStatus.Paused
    PlayerStatus.Error -> PlaybackSessionStatus.Error
    PlayerStatus.Ended -> PlaybackSessionStatus.Ended
}

private fun MusicTrack.toTrackRef(): TrackRef = TrackRef(
    id = id,
    title = title,
    artists = artists,
    album = album,
    source = source,
    coverUrl = coverUrl,
    durationMs = durationMs,
)
