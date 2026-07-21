package org.feeluown.mobile

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidNativeAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    private var mediaController: MediaController? = null
    private var controllerConnecting = false

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    override val resolvesResourcesInternally: Boolean = true

    init {
        connectController()
        scope.launch {
            FuoPlaybackService.audioDecoderInfo.collect { audioDecoderInfo ->
                mutableState.value = mutableState.value.copy(audioDecoderInfo = audioDecoderInfo)
            }
        }
        scope.launch {
            FuoPlaybackService.audioFormatInfo.collect { audioFormatInfo ->
                mutableState.value = mutableState.value.copy(audioFormatInfo = audioFormatInfo)
            }
        }
        scope.launch {
            FuoPlaybackService.spectrumLevels.collect { spectrumLevels ->
                mutableState.value = mutableState.value.copy(spectrumLevels = spectrumLevels)
            }
        }
        scope.launch {
            FuoPlaybackService.playbackState.collect { serviceState ->
                mutableState.value = serviceState.copy(
                    audioDecoderInfo = mutableState.value.audioDecoderInfo,
                    audioFormatInfo = mutableState.value.audioFormatInfo,
                    spectrumLevels = mutableState.value.spectrumLevels,
                )
            }
        }
        scope.launch {
            while (true) {
                updatePosition()
                delay(1_000)
            }
        }
    }

    override fun prepareLoading(track: MusicTrack) {
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = track,
            positionMs = 0,
            durationMs = track.durationMs ?: 0,
            bufferedMs = 0,
            lyrics = track.lyrics,
            audioQuality = null,
            audioFormatInfo = null,
            spectrumLevels = emptyList(),
            playbackParts = emptyList(),
            currentPartIndex = -1,
            errorMessage = null,
        )
        connectController()
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) = error("Android playback resolves resources in FuoPlaybackService")

    override fun play(plan: PlaybackPlan) {
        val first = plan.requests.firstOrNull() ?: return
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = first.track,
            positionMs = 0,
            durationMs = first.track.durationMs ?: 0,
            lyrics = first.track.lyrics,
            audioQuality = null,
            audioFormatInfo = null,
            spectrumLevels = emptyList(),
            playbackParts = emptyList(),
            currentPartIndex = -1,
            playbackGeneration = plan.generation,
            errorMessage = null,
        )
        runCatching { FuoPlaybackService.play(context, plan.toJson()) }
            .onFailure { throwable ->
                Log.e(TAG, "start playback service failed trackId=${first.track.id}", throwable)
                mutableState.value = mutableState.value.copy(
                    status = PlayerStatus.Error,
                    errorMessage = throwable.message ?: "无法启动播放器服务",
                )
                return
            }
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
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Idle, positionMs = 0, spectrumLevels = emptyList())
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
                        mediaController = controller
                    }
                    .onFailure { throwable ->
                        Log.e(TAG, "connect media controller failed", throwable)
                        mutableState.value = mutableState.value.copy(
                            status = PlayerStatus.Error,
                            errorMessage = throwable.message ?: "无法连接播放器服务",
                        )
                    }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun updatePosition() {
        val controller = mediaController ?: return
        if (controller.playbackState == Player.STATE_IDLE) return
        mutableState.value = mutableState.value.copy(
            currentTrack = mutableState.value.currentTrack ?: controller.currentMediaItem?.toMusicTrack(),
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.takeIf { it > 0 } ?: mutableState.value.durationMs,
            bufferedMs = controller.bufferedPosition.coerceAtLeast(0),
            lyrics = mutableState.value.lyrics ?: controller.currentMediaItem?.mediaMetadata
                ?.extras
                ?.getString("lyrics")
                ?.takeIf { it.isNotBlank() },
        )
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
            originalCoverUrl = mediaMetadata.extras?.getString("original_cover_url")?.takeIf { it.isNotBlank() },
            replacementTitle = mediaMetadata.extras?.getString("replacement_title")?.takeIf { it.isNotBlank() },
            replacementArtists = mediaMetadata.extras?.getString("replacement_artists")?.takeIf { it.isNotBlank() },
            replacementSource = mediaMetadata.extras?.getString("replacement_source")?.takeIf { it.isNotBlank() },
            replacementProviderName = mediaMetadata.extras?.getString("replacement_provider_name")?.takeIf { it.isNotBlank() },
            replacementCoverUrl = mediaMetadata.extras?.getString("replacement_cover_url")?.takeIf { it.isNotBlank() },
            replacementStrategy = mediaMetadata.extras?.getString("replacement_strategy")?.takeIf { it.isNotBlank() },
            replacementScore = mediaMetadata.extras?.getDouble("replacement_score")?.takeIf { it > 0.0 },
        )
    }

    private companion object {
        private const val TAG = "FuoAudioEngine"
    }
}
