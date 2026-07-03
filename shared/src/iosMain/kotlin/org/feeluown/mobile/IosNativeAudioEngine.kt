package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IosNativeAudioEngine(
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        mutableState.value = PlaybackState(
            status = PlayerStatus.Playing,
            currentTrack = track,
            durationMs = payload.durationMs ?: track.durationMs ?: 0,
            lyrics = payload.lyrics,
            audioQuality = payload.audioQuality,
        )
    }

    override fun pause() {
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
    }

    override fun resume() {
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        mutableState.value = PlaybackState(status = PlayerStatus.Idle)
    }

    override fun seekTo(positionMs: Long) {
        mutableState.value = mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0))
    }
}
