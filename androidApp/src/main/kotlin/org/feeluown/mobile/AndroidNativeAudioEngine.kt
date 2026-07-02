package org.feeluown.mobile

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class AndroidNativeAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    private var mediaController: MediaController? = null
    private var currentPayload: PlaybackPayload? = null
    private var controllerConnecting = false

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        connectController()
        scope.launch {
            while (true) {
                updatePosition()
                delay(1_000)
            }
        }
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
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
        FuoPlaybackService.play(context, payload.toJson(track))
        connectController()
    }

    override fun pause() {
        mediaController?.pause()
        FuoPlaybackService.pause(context)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
    }

    override fun resume() {
        mediaController?.play()
        FuoPlaybackService.resume(context)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        mediaController?.stop()
        FuoPlaybackService.stop(context)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Idle, positionMs = 0)
    }

    override fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        mutableState.value = mutableState.value.copy(positionMs = positionMs)
    }

    private fun connectController() {
        if (mediaController != null || controllerConnecting) return
        controllerConnecting = true
        val token = SessionToken(context, ComponentName(context, FuoPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controllerConnecting = false
                runCatching { future.get() }
                    .onSuccess { controller ->
                        mediaController = controller.also { connectedController ->
                            connectedController.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    updateFromController(connectedController, playbackState)
                                }

                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    updateFromController(connectedController, connectedController.playbackState)
                                }

                                override fun onPlayerError(error: PlaybackException) {
                                    mutableState.value = mutableState.value.copy(
                                        status = PlayerStatus.Error,
                                        errorMessage = error.message ?: error.errorCodeName,
                                    )
                                }
                            })
                            updateFromController(connectedController, connectedController.playbackState)
                        }
                    }
                    .onFailure { throwable ->
                        mutableState.value = mutableState.value.copy(
                            status = PlayerStatus.Error,
                            errorMessage = throwable.message ?: "无法连接播放器服务",
                        )
                    }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun updateFromController(controller: MediaController, playbackState: Int) {
        val status = when {
            playbackState == Player.STATE_ENDED -> PlayerStatus.Ended
            playbackState == Player.STATE_BUFFERING -> PlayerStatus.Loading
            controller.isPlaying -> PlayerStatus.Playing
            playbackState == Player.STATE_READY -> PlayerStatus.Paused
            else -> mutableState.value.status
        }
        mutableState.value = mutableState.value.copy(
            status = status,
            currentTrack = mutableState.value.currentTrack ?: controller.currentMediaItem?.toMusicTrack(),
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.takeIf { it > 0 } ?: currentPayload?.durationMs ?: 0,
            bufferedMs = controller.bufferedPosition.coerceAtLeast(0),
            lyrics = mutableState.value.lyrics ?: controller.currentMediaItem?.mediaMetadata
                ?.extras
                ?.getString("lyrics")
                ?.takeIf { it.isNotBlank() },
            audioQuality = mutableState.value.audioQuality ?: controller.currentMediaItem?.mediaMetadata
                ?.extras
                ?.getString("audio_quality")
                ?.takeIf { it.isNotBlank() },
        )
    }

    private fun updatePosition() {
        val controller = mediaController ?: return
        if (controller.playbackState == Player.STATE_IDLE) return
        mutableState.value = mutableState.value.copy(
            currentTrack = mutableState.value.currentTrack ?: controller.currentMediaItem?.toMusicTrack(),
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.takeIf { it > 0 } ?: currentPayload?.durationMs ?: 0,
            bufferedMs = controller.bufferedPosition.coerceAtLeast(0),
            lyrics = mutableState.value.lyrics ?: controller.currentMediaItem?.mediaMetadata
                ?.extras
                ?.getString("lyrics")
                ?.takeIf { it.isNotBlank() },
        )
    }

    private fun PlaybackPayload.toJson(track: MusicTrack): String {
        val headersJson = JSONObject()
        headers.forEach { (key, value) -> headersJson.put(key, value) }
        return JSONObject()
            .put("track_id", track.id)
            .put("source_type", track.sourceType.name)
            .put("local_uri", track.localUri ?: "")
            .put("provider_id", track.providerId ?: "")
            .put("provider_name", track.providerName ?: "")
            .put("smart_replacement", track.isSmartReplacement)
            .put("original_title", track.originalTitle ?: "")
            .put("original_provider_name", track.originalProviderName ?: "")
            .put("url", url)
            .put("title", title)
            .put("artists", artists)
            .put("album", album)
            .put("source", source)
            .put("headers", headersJson)
            .put("cover_url", coverUrl ?: "")
            .put("duration_ms", durationMs ?: 0)
            .put("lyrics", lyrics ?: "")
            .put("audio_quality", audioQuality ?: "")
            .toString()
    }

    private fun MediaItem.toMusicTrack(): MusicTrack? {
        val metadata = mediaMetadata
        val title = metadata.title?.toString().orEmpty()
        if (title.isBlank() && mediaId.isBlank()) return null
        val sourceType = runCatching { TrackSourceType.valueOf(mediaMetadata.extras?.getString("source_type").orEmpty()) }
            .getOrDefault(TrackSourceType.Provider)
        return MusicTrack(
            id = mediaId.ifBlank { "session:${metadata.title}:${metadata.artist}" },
            title = title,
            artists = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            source = mediaMetadata.extras?.getString("source").orEmpty(),
            sourceType = sourceType,
            coverUrl = metadata.artworkUri?.toString(),
            localUri = mediaMetadata.extras?.getString("local_uri")?.takeIf { it.isNotBlank() },
            lyrics = mediaMetadata.extras?.getString("lyrics")?.takeIf { it.isNotBlank() },
            providerId = mediaMetadata.extras?.getString("provider_id")?.takeIf { it.isNotBlank() },
            providerName = mediaMetadata.extras?.getString("provider_name")?.takeIf { it.isNotBlank() },
            isSmartReplacement = mediaMetadata.extras?.getBoolean("smart_replacement") ?: false,
            originalTitle = mediaMetadata.extras?.getString("original_title")?.takeIf { it.isNotBlank() },
            originalProviderName = mediaMetadata.extras?.getString("original_provider_name")?.takeIf { it.isNotBlank() },
        )
    }
}
