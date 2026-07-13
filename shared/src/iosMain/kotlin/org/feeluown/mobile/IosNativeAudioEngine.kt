package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class IosNativeAudioEngine(
    scope: CoroutineScope,
    private val output: IosAudioOutput,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        scope.launch {
            while (true) {
                updateFromOutput()
                delay(500)
            }
        }
    }

    override fun prepareLoading(track: MusicTrack) {
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track,
            durationMs = track.durationMs ?: 0,
            lyrics = track.lyrics,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        output.play(payload.url, payload.headers, payload.title, payload.artists, payload.album)
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track,
            durationMs = payload.durationMs ?: track.durationMs ?: 0,
            lyrics = payload.lyrics,
            audioQuality = payload.audioQuality,
            playbackParts = payload.parts,
            currentPartIndex = payload.currentPartIndex,
        )
    }

    override fun pause() {
        output.pause()
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
    }

    override fun resume() {
        output.resume()
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        output.stop()
        mutableState.value = PlaybackState(status = PlayerStatus.Idle)
    }

    override fun seekTo(positionMs: Long) {
        output.seekTo(positionMs)
        mutableState.value = mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0))
    }

    private fun updateFromOutput() {
        if (mutableState.value.currentTrack == null) return
        val status = when (output.playbackStatus()) {
            "Loading" -> PlayerStatus.Loading
            "Playing" -> PlayerStatus.Playing
            "Paused" -> PlayerStatus.Paused
            "Ended" -> PlayerStatus.Ended
            "Error" -> PlayerStatus.Error
            else -> PlayerStatus.Idle
        }
        mutableState.value = mutableState.value.copy(
            status = status,
            positionMs = output.positionMs().coerceAtLeast(0),
            durationMs = output.durationMs().takeIf { it > 0 } ?: mutableState.value.durationMs,
            bufferedMs = output.bufferedMs().coerceAtLeast(0),
            audioFormatInfo = output.audioFormatInfo(),
            errorMessage = output.errorMessage(),
        )
    }
}
