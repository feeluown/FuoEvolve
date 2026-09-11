package org.feeluown.mobile.nucleus

import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaControlService
import dev.nucleusframework.media.control.MediaMetadata
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

internal class NucleusSystemMediaSession(
    private val playbackSession: PlaybackSession,
    private val onRaise: () -> Unit,
    private val onQuit: () -> Unit,
    private val onOpenUri: (String) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        if (!MediaControlService.isAvailable()) {
            AppLogger.w("SystemMediaSession", "Nucleus media-control backend is unavailable")
        } else {
            MediaControlService.configure(
                dbusName = "org.mpris.MediaPlayer2.FuoEvolve",
                displayName = "FuoEvolve",
            )
            MediaControlService.attach(::handleEvent)
            publish(playbackSession.state.value, previous = null)
            scope.launch {
                var previous = playbackSession.state.value
                playbackSession.state.collect { current ->
                    publish(current, previous)
                    previous = current
                }
            }
            AppLogger.i("SystemMediaSession", "Nucleus media-control integration enabled")
        }
    }

    private fun handleEvent(event: MediaControlEvent) {
        when (event) {
            MediaControlEvent.Play -> playbackSession.play()
            MediaControlEvent.Pause -> playbackSession.pause()
            MediaControlEvent.Toggle -> playbackSession.toggle()
            MediaControlEvent.Next -> skipTrack(next = true)
            MediaControlEvent.Previous -> skipTrack(next = false)
            MediaControlEvent.Stop -> playbackSession.stop()
            is MediaControlEvent.SeekBy -> seekTo(playbackSession.state.value.positionMs + event.offsetMs)
            is MediaControlEvent.SetPosition -> seekTo(event.positionMs)
            is MediaControlEvent.SetVolume -> {
                if (event.volume.isFinite()) playbackSession.setVolume(event.volume.coerceIn(0.0, 1.0))
            }
            is MediaControlEvent.OpenUri -> onOpenUri(event.uri)
            MediaControlEvent.Raise -> onRaise()
            MediaControlEvent.Quit -> onQuit()
        }
    }

    private fun skipTrack(next: Boolean) {
        val before = playbackSession.state.value
        if (next) {
            if (!before.canGoNext) return
            playbackSession.next()
        } else {
            if (!before.canGoPrevious) return
            playbackSession.previous()
        }
        preserveNonPlayingStatusAfterSkip(before.status)
    }

    private fun preserveNonPlayingStatusAfterSkip(status: PlaybackSessionStatus) {
        when (status) {
            PlaybackSessionStatus.Paused -> playbackSession.pause()
            PlaybackSessionStatus.Idle,
            PlaybackSessionStatus.Loading,
            PlaybackSessionStatus.Error,
            PlaybackSessionStatus.Ended -> playbackSession.stop()
            PlaybackSessionStatus.Playing -> Unit
        }
    }

    private fun seekTo(positionMs: Long) {
        val state = playbackSession.state.value
        val upperBound = state.durationMs.takeIf { it > 0L }
        val bounded = if (upperBound != null) {
            positionMs.coerceIn(0L, upperBound)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        playbackSession.seekTo(bounded)
    }

    private fun publish(current: PlaybackSessionState, previous: PlaybackSessionState?) {
        if (
            previous == null ||
            previous.currentTrack != current.currentTrack ||
            previous.durationMs != current.durationMs
        ) {
            val track = current.currentTrack
            MediaControlService.setMetadata(
                MediaMetadata(
                    title = track?.title,
                    artist = track?.artists?.takeIf(String::isNotBlank),
                    album = track?.album?.takeIf(String::isNotBlank),
                    coverUrl = track?.coverUrl?.takeIf(String::isNotBlank),
                    duration = current.durationMs.takeIf { it > 0L }
                        ?: track?.durationMs?.takeIf { it > 0L },
                ),
            )
        }

        MediaControlService.setPlaybackState(
            MediaPlaybackState(
                status = current.status.toNucleusStatus(),
                positionMs = current.positionMs.coerceAtLeast(0L),
            ),
        )

        if (previous == null || previous.volume != current.volume) {
            MediaControlService.setVolume(current.volume)
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { MediaControlService.detach() }
        runCatching {
            MediaControlService.setPlaybackState(
                MediaPlaybackState(MediaPlaybackStatus.STOPPED, positionMs = null),
            )
        }
    }
}

private fun PlaybackSessionStatus.toNucleusStatus(): MediaPlaybackStatus = when (this) {
    PlaybackSessionStatus.Playing -> MediaPlaybackStatus.PLAYING
    PlaybackSessionStatus.Paused -> MediaPlaybackStatus.PAUSED
    PlaybackSessionStatus.Idle,
    PlaybackSessionStatus.Loading,
    PlaybackSessionStatus.Error,
    PlaybackSessionStatus.Ended -> MediaPlaybackStatus.STOPPED
}
