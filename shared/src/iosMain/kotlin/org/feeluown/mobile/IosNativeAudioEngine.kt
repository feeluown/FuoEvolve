package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAudioSession
import platform.AVFoundation.AVAudioSessionCategoryPlayback
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.Foundation.NSURL
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime

class IosNativeAudioEngine(
    private val scope: CoroutineScope,
) : PlaybackEngine {
    private val player = AVPlayer()
    private val mutableState = MutableStateFlow(PlaybackState())
    private var currentPayload: PlaybackPayload? = null

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        configureAudioSession()
        scope.launch {
            while (true) {
                updatePosition()
                delay(1_000)
            }
        }
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        val url = payload.url.toNsUrl() ?: run {
            mutableState.value = mutableState.value.copy(
                status = PlayerStatus.Error,
                errorMessage = "无效播放地址",
            )
            return
        }
        currentPayload = payload
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = track,
            positionMs = 0,
            durationMs = payload.durationMs ?: 0,
            lyrics = payload.lyrics,
            audioQuality = payload.audioQuality,
            errorMessage = null,
        )
        val item = AVPlayerItem(uRL = url)
        player.replaceCurrentItemWithPlayerItem(item)
        updateNowPlaying(payload, 0)
        player.play()
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun pause() {
        player.pause()
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
    }

    override fun resume() {
        player.play()
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        mutableState.value = PlaybackState(status = PlayerStatus.Idle)
        MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo = null
    }

    override fun seekTo(positionMs: Long) {
        player.seekToTime(positionMs.toCMTime())
        mutableState.value = mutableState.value.copy(positionMs = positionMs)
        currentPayload?.let { updateNowPlaying(it, positionMs) }
    }

    private fun configureAudioSession() {
        runCatching {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
            AVAudioSession.sharedInstance().setActive(true, error = null)
        }
    }

    private fun updatePosition() {
        val payload = currentPayload ?: return
        val positionMs = player.currentTime().toMillis()
        val durationMs = player.currentItem?.duration?.toMillis()
            ?.takeIf { it > 0 }
            ?: payload.durationMs
            ?: mutableState.value.durationMs
        val status = when {
            durationMs > 0 && positionMs >= durationMs - 500 && mutableState.value.status == PlayerStatus.Playing -> PlayerStatus.Ended
            else -> mutableState.value.status
        }
        mutableState.value = mutableState.value.copy(
            status = status,
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0),
            lyrics = mutableState.value.lyrics ?: payload.lyrics,
        )
        updateNowPlaying(payload, positionMs)
    }

    private fun updateNowPlaying(payload: PlaybackPayload, positionMs: Long) {
        MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo = mapOf(
            MPMediaItemPropertyTitle to payload.title,
            MPMediaItemPropertyArtist to payload.artists,
            MPMediaItemPropertyAlbumTitle to payload.album,
            MPMediaItemPropertyPlaybackDuration to ((payload.durationMs ?: 0).toDouble() / 1000.0),
            MPNowPlayingInfoPropertyElapsedPlaybackTime to (positionMs.toDouble() / 1000.0),
        )
    }
}
