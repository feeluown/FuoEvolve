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
    settingsRepository: AppSettingsRepository,
) : PlaybackEngine, ResolvedPlaybackSourceAwareEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    private var rawAudioQuality: String? = null

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        scope.launch {
            settingsRepository.state.collect { settingsState ->
                if (settingsState.isLoaded) {
                    output.setPauseOnOtherAppPlayback(settingsState.settings.pauseOnOtherAppPlayback)
                }
            }
        }
        scope.launch {
            while (true) {
                updateFromOutput()
                delay(50)
            }
        }
    }

    override fun prepareLoading(track: MusicTrack) {
        rawAudioQuality = null
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track.logicalPlaybackTrack(),
            resolvedSource = null,
            durationMs = track.durationMs ?: 0,
            lyrics = track.lyrics,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        playResolved(
            logicalTrack = track.logicalPlaybackTrack(),
            resolveTrack = track,
            payload = payload,
        )
    }

    override fun playResolved(
        logicalTrack: MusicTrack,
        resolveTrack: MusicTrack,
        payload: PlaybackPayload,
    ) {
        val normalizedLogicalTrack = logicalTrack.logicalPlaybackTrack()
        output.play(payload.url, payload.headers, payload.title, payload.artists, payload.album)
        rawAudioQuality = payload.audioQuality
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = normalizedLogicalTrack,
            resolvedSource = payload.toResolvedPlaybackSource(normalizedLogicalTrack, resolveTrack),
            durationMs = payload.durationMs ?: normalizedLogicalTrack.durationMs ?: 0,
            lyrics = payload.lyrics,
            audioQuality = normalizedAudioQualityLabel(rawAudioQuality, null),
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
        rawAudioQuality = null
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
        val audioFormatInfo = output.audioFormatInfo()
        mutableState.value = mutableState.value.copy(
            status = status,
            positionMs = output.positionMs().coerceAtLeast(0),
            durationMs = output.durationMs().takeIf { it > 0 } ?: mutableState.value.durationMs,
            bufferedMs = output.bufferedMs().coerceAtLeast(0),
            audioQuality = normalizedAudioQualityLabel(rawAudioQuality, audioFormatInfo),
            audioFormatInfo = audioFormatInfo,
            errorMessage = output.errorMessage(),
        )
    }
}
