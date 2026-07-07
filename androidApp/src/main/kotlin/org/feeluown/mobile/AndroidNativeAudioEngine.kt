package org.feeluown.mobile

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private var playRequestSerial: Long = 0
    private var loadingWatchdog: Job? = null
    private var playbackRetryJob: Job? = null
    private var playbackRetryAttempt = 0

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

    override fun prepareLoading(track: MusicTrack) {
        playRequestSerial += 1
        cancelLoadingWatchdog()
        cancelPlaybackRetry()
        playbackRetryAttempt = 0
        currentPayload = null
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = track,
            positionMs = 0,
            durationMs = track.durationMs ?: 0,
            bufferedMs = 0,
            lyrics = track.lyrics,
            audioQuality = null,
            playbackParts = emptyList(),
            currentPartIndex = -1,
            errorMessage = null,
        )
        connectController()
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        val requestSerial = ++playRequestSerial
        cancelLoadingWatchdog()
        cancelPlaybackRetry()
        playbackRetryAttempt = 0
        currentPayload = payload
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = track,
            positionMs = 0,
            durationMs = payload.durationMs ?: 0,
            lyrics = payload.lyrics,
            audioQuality = payload.audioQuality,
            playbackParts = payload.parts,
            currentPartIndex = payload.currentPartIndex,
            errorMessage = null,
        )
        runCatching { FuoPlaybackService.play(context, payload.toJson(track)) }
            .onFailure { throwable ->
                Log.e(TAG, "start playback service failed trackId=${track.id}", throwable)
                mutableState.value = mutableState.value.copy(
                    status = PlayerStatus.Error,
                    errorMessage = throwable.message ?: "无法启动播放器服务",
                )
                return
            }
        connectController()
        startLoadingWatchdog(requestSerial, track.id)
    }

    override fun pause() {
        cancelLoadingWatchdog()
        cancelPlaybackRetry()
        mediaController?.pause()
        FuoPlaybackService.pause(context)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
    }

    override fun resume() {
        cancelLoadingWatchdog()
        mediaController?.play()
        FuoPlaybackService.resume(context)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        cancelLoadingWatchdog()
        cancelPlaybackRetry()
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
                                    cancelLoadingWatchdog()
                                    val track = mutableState.value.currentTrack
                                    val payload = currentPayload
                                    val message = playbackErrorMessage(error)
                                    Log.e(
                                        TAG,
                                        "playback error trackId=${track?.id.orEmpty()} " +
                                            "source=${track?.source.orEmpty()} url=${payload?.url?.summarizePlaybackUrl().orEmpty()} " +
                                            "code=${error.errorCodeName} retryAttempt=$playbackRetryAttempt",
                                        error,
                                    )
                                    if (track != null && payload != null && shouldRetryPlaybackError(payload, error)) {
                                        retryPlayback(track, payload, message)
                                    } else {
                                        mutableState.value = mutableState.value.copy(
                                            status = PlayerStatus.Error,
                                            errorMessage = message,
                                        )
                                    }
                                }
                            })
                            updateFromController(connectedController, connectedController.playbackState)
                        }
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

    private fun updateFromController(controller: MediaController, playbackState: Int) {
        val status = when {
            playbackState == Player.STATE_ENDED -> PlayerStatus.Ended
            playbackState == Player.STATE_BUFFERING -> PlayerStatus.Loading
            controller.isPlaying -> PlayerStatus.Playing
            playbackState == Player.STATE_READY -> PlayerStatus.Paused
            else -> mutableState.value.status
        }
        if (status != PlayerStatus.Loading) {
            cancelLoadingWatchdog()
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

    private fun startLoadingWatchdog(requestSerial: Long, trackId: String) {
        loadingWatchdog = scope.launch {
            delay(PLAYBACK_START_TIMEOUT_MS)
            if (requestSerial != playRequestSerial || mutableState.value.status != PlayerStatus.Loading) {
                return@launch
            }
            val controller = mediaController
            Log.w(
                TAG,
                "playback loading timeout trackId=$trackId state=${controller?.playbackState} " +
                    "isPlaying=${controller?.isPlaying} url=${currentPayload?.url?.summarizePlaybackUrl().orEmpty()}",
            )
            runCatching {
                controller?.stop()
                FuoPlaybackService.stop(context)
            }
            mutableState.value = mutableState.value.copy(
                status = PlayerStatus.Error,
                errorMessage = "音频加载超时，请检查网络后重试",
            )
        }
    }

    private fun cancelLoadingWatchdog() {
        loadingWatchdog?.cancel()
        loadingWatchdog = null
    }

    private fun cancelPlaybackRetry() {
        playbackRetryJob?.cancel()
        playbackRetryJob = null
    }

    private fun retryPlayback(track: MusicTrack, payload: PlaybackPayload, errorMessage: String) {
        playbackRetryAttempt += 1
        val requestSerial = playRequestSerial
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            errorMessage = "播放中断，正在重试：$errorMessage",
        )
        playbackRetryJob = scope.launch {
            delay(PLAYBACK_RETRY_DELAY_MS)
            if (requestSerial != playRequestSerial) return@launch
            Log.w(
                TAG,
                "retry playback trackId=${track.id} attempt=$playbackRetryAttempt url=${payload.url.summarizePlaybackUrl()}",
            )
            runCatching { FuoPlaybackService.play(context, payload.toJson(track)) }
                .onSuccess { startLoadingWatchdog(requestSerial, track.id) }
                .onFailure { throwable ->
                    Log.e(TAG, "retry playback service failed trackId=${track.id}", throwable)
                    mutableState.value = mutableState.value.copy(
                        status = PlayerStatus.Error,
                        errorMessage = throwable.message ?: "重试播放失败",
                    )
                }
        }
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
            .put("original_cover_url", track.originalCoverUrl ?: "")
            .put("replacement_title", track.replacementTitle ?: "")
            .put("replacement_artists", track.replacementArtists ?: "")
            .put("replacement_source", track.replacementSource ?: "")
            .put("replacement_provider_name", track.replacementProviderName ?: "")
            .put("replacement_cover_url", track.replacementCoverUrl ?: "")
            .put("replacement_strategy", track.replacementStrategy ?: "")
            .put("replacement_score", track.replacementScore ?: 0.0)
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

    private fun shouldRetryPlaybackError(payload: PlaybackPayload, error: PlaybackException): Boolean {
        if (playbackRetryAttempt >= MAX_PLAYBACK_RETRY_ATTEMPTS || !payload.url.isRemoteUrl()) {
            return false
        }
        val codeName = error.errorCodeName
        return codeName.contains("IO_NETWORK", ignoreCase = true) ||
            codeName.contains("IO_UNSPECIFIED", ignoreCase = true) ||
            codeName.contains("TIMEOUT", ignoreCase = true)
    }

    private fun playbackErrorMessage(error: PlaybackException): String {
        return listOf(error.errorCodeName, error.message)
            .filterNot { it.isNullOrBlank() }
            .joinToString(": ")
            .ifBlank { "播放失败" }
    }

    private fun String.isRemoteUrl(): Boolean {
        val scheme = runCatching { Uri.parse(this).scheme }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    private fun String.summarizePlaybackUrl(): String {
        val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return "<invalid>"
        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        return if (host.isBlank()) {
            "$scheme:$path"
        } else {
            "$scheme://$host$path"
        }
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
        private const val PLAYBACK_START_TIMEOUT_MS = 30_000L
        private const val PLAYBACK_RETRY_DELAY_MS = 1_000L
        private const val MAX_PLAYBACK_RETRY_ATTEMPTS = 1
    }
}
