package org.feeluown.mobile

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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
import org.json.JSONObject

class AndroidNativeAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    private var mediaController: MediaController? = null
    private var controllerConnecting = false
    private var pendingLockScreenLyrics: PendingLockScreenLyrics? = null

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
            FuoPlaybackService.playbackState.collect { serviceState ->
                mutableState.value = serviceState.copy(
                    audioDecoderInfo = mutableState.value.audioDecoderInfo,
                    audioFormatInfo = mutableState.value.audioFormatInfo,
                )
                applyPendingLockScreenLyrics()
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
        pendingLockScreenLyrics = null
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = track,
            positionMs = 0,
            durationMs = track.durationMs ?: 0,
            bufferedMs = 0,
            lyrics = track.lyrics,
            audioQuality = null,
            audioFormatInfo = null,
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
        pendingLockScreenLyrics = null
        clearCurrentLockScreenLyrics()
        mediaController?.stop()
        FuoPlaybackService.stop(context)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Idle, positionMs = 0)
    }

    override fun seekTo(positionMs: Long) {
        val duration = mutableState.value.durationMs
        val normalizedPosition = positionMs.coerceAtLeast(0).let { position ->
            duration.takeIf { it > 0 }?.let(position::coerceAtMost) ?: position
        }
        mediaController?.seekTo(normalizedPosition)
        mutableState.value = mutableState.value.copy(positionMs = normalizedPosition)
    }

    /**
     * Publishes complete timed lyrics through the OPlus/ColorOS media-session extension.
     * Other Android systems ignore this metadata extra.
     */
    internal fun publishLockScreenLyrics(trackId: String, lyrics: String?) {
        val normalizedLyrics = lyrics?.takeIf { it.isNotBlank() }
        if (normalizedLyrics == null) {
            pendingLockScreenLyrics = null
            if (mutableState.value.currentTrack?.id == trackId) {
                clearCurrentLockScreenLyrics(trackId)
            }
            return
        }
        pendingLockScreenLyrics = PendingLockScreenLyrics(trackId, normalizedLyrics)
        applyPendingLockScreenLyrics()
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
                        applyPendingLockScreenLyrics()
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

    private fun applyPendingLockScreenLyrics() {
        val pending = pendingLockScreenLyrics ?: return
        val controller = mediaController ?: return
        val track = mutableState.value.currentTrack ?: return
        if (track.id != pending.trackId) return
        if (!controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            Log.w(TAG, "media session does not allow metadata replacement for ColorOS lyrics")
            return
        }
        val currentItem = controller.currentMediaItem ?: return
        if (!currentItem.matchesTrack(pending.trackId)) return
        val currentIndex = controller.currentMediaItemIndex
        if (currentIndex < 0) return
        val lineLyrics = toTimedLineLrc(pending.lyrics)
        if (lineLyrics == null) {
            pendingLockScreenLyrics = null
            clearCurrentLockScreenLyrics(pending.trackId)
            return
        }
        val lyricInfo = buildLockScreenLyricInfo(track, lineLyrics)
        val currentExtras = currentItem.mediaMetadata.extras
        if (currentExtras?.getString(OPLUS_LYRIC_INFO_KEY) == lyricInfo) {
            pendingLockScreenLyrics = null
            return
        }
        val extras = Bundle(currentExtras ?: Bundle.EMPTY).apply {
            putString(OPLUS_LYRIC_INFO_KEY, lyricInfo)
        }
        replaceMediaItemMetadata(controller, currentIndex, currentItem, extras)
            .onSuccess {
                pendingLockScreenLyrics = null
                Log.d(TAG, "published ColorOS lock-screen lyrics trackId=${track.id}")
            }
            .onFailure { throwable ->
                Log.w(TAG, "failed to publish ColorOS lock-screen lyrics trackId=${track.id}", throwable)
            }
    }

    private fun clearCurrentLockScreenLyrics(trackId: String? = null) {
        val controller = mediaController ?: return
        if (!controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val currentItem = controller.currentMediaItem ?: return
        if (trackId != null && !currentItem.matchesTrack(trackId)) return
        val currentExtras = currentItem.mediaMetadata.extras ?: return
        if (!currentExtras.containsKey(OPLUS_LYRIC_INFO_KEY)) return
        val currentIndex = controller.currentMediaItemIndex
        if (currentIndex < 0) return
        val extras = Bundle(currentExtras).apply {
            remove(OPLUS_LYRIC_INFO_KEY)
        }
        replaceMediaItemMetadata(controller, currentIndex, currentItem, extras)
            .onFailure { throwable ->
                Log.w(TAG, "failed to clear ColorOS lock-screen lyrics", throwable)
            }
    }

    private fun replaceMediaItemMetadata(
        controller: MediaController,
        currentIndex: Int,
        currentItem: MediaItem,
        extras: Bundle,
    ): Result<Unit> = runCatching {
        // Preserve URI and playback configuration; only session-visible metadata changes.
        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(
                currentItem.mediaMetadata.buildUpon()
                    .setExtras(extras)
                    .build(),
            )
            .build()
        controller.replaceMediaItem(currentIndex, updatedItem)
    }

    private fun MediaItem.matchesTrack(trackId: String): Boolean =
        mediaId.endsWith(":$trackId")

    private fun buildLockScreenLyricInfo(track: MusicTrack, lineLyrics: String): String =
        JSONObject()
            .put("songName", track.title)
            .put("artist", track.artists)
            .put("songId", track.id)
            .put("lyric", lineLyrics)
            .toString()

    private fun updatePosition() {
        val controller = mediaController ?: return
        if (controller.playbackState == Player.STATE_IDLE) return
        val currentState = mutableState.value
        val currentItem = controller.currentMediaItem
        val sessionLyrics = currentItem
            ?.takeIf { item -> currentState.currentTrack?.let { item.matchesTrack(it.id) } ?: true }
            ?.mediaMetadata
            ?.extras
            ?.getString("lyrics")
            ?.takeIf { it.isNotBlank() }
        mutableState.value = currentState.copy(
            currentTrack = currentState.currentTrack ?: currentItem?.toMusicTrack(),
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.takeIf { it > 0 } ?: currentState.durationMs,
            bufferedMs = controller.bufferedPosition.coerceAtLeast(0),
            lyrics = currentState.lyrics ?: sessionLyrics,
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
            originalId = mediaMetadata.extras?.getString("original_id")?.takeIf { it.isNotBlank() },
            originalTitle = mediaMetadata.extras?.getString("original_title")?.takeIf { it.isNotBlank() },
            originalArtists = mediaMetadata.extras?.getString("original_artists")?.takeIf { it.isNotBlank() },
            originalAlbum = mediaMetadata.extras?.getString("original_album")?.takeIf { it.isNotBlank() },
            originalSource = mediaMetadata.extras?.getString("original_source")?.takeIf { it.isNotBlank() },
            originalProviderName = mediaMetadata.extras?.getString("original_provider_name")?.takeIf { it.isNotBlank() },
            originalCoverUrl = mediaMetadata.extras?.getString("original_cover_url")?.takeIf { it.isNotBlank() },
            replacementId = mediaMetadata.extras?.getString("replacement_id")?.takeIf { it.isNotBlank() },
            replacementTitle = mediaMetadata.extras?.getString("replacement_title")?.takeIf { it.isNotBlank() },
            replacementArtists = mediaMetadata.extras?.getString("replacement_artists")?.takeIf { it.isNotBlank() },
            replacementAlbum = mediaMetadata.extras?.getString("replacement_album")?.takeIf { it.isNotBlank() },
            replacementSource = mediaMetadata.extras?.getString("replacement_source")?.takeIf { it.isNotBlank() },
            replacementProviderName = mediaMetadata.extras?.getString("replacement_provider_name")?.takeIf { it.isNotBlank() },
            replacementCoverUrl = mediaMetadata.extras?.getString("replacement_cover_url")?.takeIf { it.isNotBlank() },
            replacementStrategy = mediaMetadata.extras?.getString("replacement_strategy")?.takeIf { it.isNotBlank() },
            replacementScore = mediaMetadata.extras?.getDouble("replacement_score")?.takeIf { it > 0.0 },
        )
    }

    private data class PendingLockScreenLyrics(
        val trackId: String,
        val lyrics: String,
    )

    private companion object {
        private const val TAG = "FuoAudioEngine"
        private const val OPLUS_LYRIC_INFO_KEY = "lyricInfo"
    }
}
