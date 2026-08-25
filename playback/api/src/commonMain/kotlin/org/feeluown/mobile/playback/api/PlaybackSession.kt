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
    val lyricsPositionMs: Long = positionMs,
    val lyricsAlignmentOffsetMs: Long = 0L,
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
 * Implementations own the published session state and transport policy. Consumers depend only on
 * this surface so the underlying engine, queue coordinator, and platform adapters can evolve
 * independently.
 */
interface PlaybackSession {
    val state: StateFlow<PlaybackSessionState>

    fun toggle()
    fun play()
    fun pause()
    fun previous()
    fun next()
}
