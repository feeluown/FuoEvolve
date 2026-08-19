package org.feeluown.mobile.playback.api

import kotlinx.coroutines.flow.StateFlow
import org.feeluown.mobile.core.model.TrackRef

enum class PlaybackSessionStatus {
    Idle,
    Loading,
    Playing,
    Paused,
    Error,
    Ended,
}

data class PlaybackSessionState(
    val status: PlaybackSessionStatus = PlaybackSessionStatus.Idle,
    val currentTrack: TrackRef? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val lyrics: String? = null,
    val queueTrackIds: List<String> = emptyList(),
    val queueIndex: Int = -1,
    val errorMessage: String? = null,
)

/**
 * Narrow app-scoped playback contract for platform integrations and cross-feature consumers.
 *
 * The legacy player controller may back this contract during migration, but consumers must only
 * depend on this state/transport surface so ownership can move into a dedicated runtime without
 * another platform-wide rewrite.
 */
interface PlaybackSession {
    val state: StateFlow<PlaybackSessionState>

    fun toggle()
    fun play()
    fun pause()
    fun previous()
    fun next()
}
