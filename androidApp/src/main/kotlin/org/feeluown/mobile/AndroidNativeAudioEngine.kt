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
import kotlin.math.abs

class AndroidNativeAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlaybackEngine {
    private val playbackResumeStore = AndroidPlaybackResumeStore(context)
    private var restoredSession: AndroidPlaybackResumeSnapshot? = playbackResumeStore.load()
    private val mutableState = MutableStateFlow(restoredSession?.toPlaybackState() ?: PlaybackState())
    private var rawAudioQuality: String? = null
    private var mediaController: MediaController? = null
    private var controllerConnecting = false
    private var pendingLockScreenLyrics: PendingLockScreenLyrics? = null
    private var activePlan: PlaybackPlan? = restoredSession?.plan
    private var pendingResumePositionMs: Long? = null
    private var preparedRestoredSession: AndroidPlaybackResumeSnapshot? = null
    private var explicitStopRequested = false
    private var startingPlayback = false
    private var lastPersistedIdentity: String? = null
    private var lastPersistedPositionMs: Long = restoredSession?.positionMs ?: 0L
    private var restoredRepublishSerial = 0L

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
                mutableState.value = mutableState.value.copy(
                    audioQuality = normalizedAudioQualityLabel(rawAudioQuality, audioFormatInfo),
                    audioFormatInfo = audioFormatInfo,
                )
            }
        }
        scope.launch {
            FuoPlaybackService.playbackState.collect { serviceState ->
                if (serviceState.isEmptyIdleState() && !explicitStopRequested && !startingPlayback) {
                    val session = restoredSession ?: playbackResumeStore.load()
                    if (session != null) {
                        restoredSession = session
                        activePlan = session.plan
                        publishRestoredState(session)
                        return@collect
                    }
                }

                if (!serviceState.isEmptyIdleState()) {
                    startingPlayback = false
                    if (serviceState.currentTrack != null) {
                        restoredSession = null
                        preparedRestoredSession = null
                    }
                }

                rawAudioQuality = serviceState.audioQuality
                val currentState = mutableState.value
                val audioFormatInfo = currentState.audioFormatInfo
                val pendingPosition = pendingResumePositionMs
                mutableState.value = serviceState.copy(
                    positionMs = pendingPosition ?: serviceState.positionMs,
                    playbackParts = if (pendingPosition != null && serviceState.playbackParts.isEmpty()) {
                        currentState.playbackParts
                    } else {
                        serviceState.playbackParts
                    },
                    currentPartIndex = if (pendingPosition != null && serviceState.currentPartIndex < 0) {
                        currentState.currentPartIndex
                    } else {
                        serviceState.currentPartIndex
                    },
                    audioQuality = normalizedAudioQualityLabel(rawAudioQuality, audioFormatInfo),
                    audioDecoderInfo = currentState.audioDecoderInfo,
                    audioFormatInfo = audioFormatInfo,
                )
                applyPendingResumeSeek()
                persistPlaybackState()
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
        pendingResumePositionMs = null

        // FuoPlayerController can briefly still expose Idle after process recreation even though
        // this engine has already restored the same track as Paused. MiniPlayer becomes visible
        // from the restored queue during that window, and its play button would otherwise call
        // startPlayback(), clearing the saved position before resume() gets a chance to use it.
        // Preserve the resumable session here and let play(plan) turn that stale restart into the
        // same resume path used by FullPlayer.
        val session = restoredSession ?: playbackResumeStore.load()
        val shouldResumeRestoredSession = mutableState.value.status == PlayerStatus.Paused &&
            session?.currentTrack?.id == track.id &&
            session.resumePlan() != null
        if (shouldResumeRestoredSession && session != null) {
            preparedRestoredSession = session
            restoredSession = session
            activePlan = session.plan
            explicitStopRequested = false
            startingPlayback = false
            connectController()
            Log.d(TAG, "preserving restored session for prepared trackId=${track.id}")
            return
        }

        preparedRestoredSession = null
        restoredSession = null
        explicitStopRequested = false
        startingPlayback = true
        rawAudioQuality = null
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
        val preparedSession = preparedRestoredSession
        if (preparedSession != null && preparedSession.currentTrack.id == first.track.id) {
            preparedRestoredSession = null
            restoredSession = preparedSession
            activePlan = preparedSession.plan
            Log.d(
                TAG,
                "converting stale restart to restored resume trackId=${first.track.id} positionMs=${preparedSession.positionMs}",
            )
            resume()
            return
        }

        preparedRestoredSession = null
        explicitStopRequested = false
        startingPlayback = true
        restoredSession = null
        pendingResumePositionMs = null
        activePlan = plan
        lastPersistedIdentity = null
        lastPersistedPositionMs = 0L
        rawAudioQuality = null
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
        persistPlaybackState(forceSession = true)
        runCatching { FuoPlaybackService.play(context, plan.toJson()) }
            .onFailure { throwable ->
                startingPlayback = false
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
        persistPlaybackState(forcePosition = true)
    }

    override fun resume() {
        explicitStopRequested = false
        preparedRestoredSession = null
        val controller = mediaController
        val canResumeLiveSession = controller?.currentMediaItem != null &&
            controller.playbackState != Player.STATE_IDLE
        if (canResumeLiveSession) {
            controller.play()
            FuoPlaybackService.resume(context)
            mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
            return
        }

        val session = restoredSession ?: playbackResumeStore.load()
        val resumePlan = session?.resumePlan()
        if (session != null && resumePlan != null) {
            activePlan = resumePlan
            restoredSession = session
            pendingResumePositionMs = session.positionMs.coerceAtLeast(0L)
            startingPlayback = true
            lastPersistedIdentity = null
            lastPersistedPositionMs = session.positionMs
            mutableState.value = session.toPlaybackState().copy(
                status = PlayerStatus.Loading,
                playbackGeneration = resumePlan.generation,
            )
            persistPlaybackState(forceSession = true)
            runCatching { FuoPlaybackService.play(context, resumePlan.toJson()) }
                .onFailure { throwable ->
                    startingPlayback = false
                    Log.e(TAG, "restore playback service failed trackId=${session.currentTrack.id}", throwable)
                    mutableState.value = session.toPlaybackState().copy(
                        status = PlayerStatus.Error,
                        errorMessage = throwable.message ?: "无法恢复播放进度",
                    )
                    return
                }
            connectController()
            return
        }

        controller?.play()
        FuoPlaybackService.resume(context)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        pendingLockScreenLyrics = null
        pendingResumePositionMs = null
        preparedRestoredSession = null
        restoredSession = null
        activePlan = null
        explicitStopRequested = true
        startingPlayback = false
        lastPersistedIdentity = null
        lastPersistedPositionMs = 0L
        playbackResumeStore.clear()
        rawAudioQuality = null
        clearCurrentLockScreenLyrics()
        mediaController?.stop()
        FuoPlaybackService.stop(context)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Idle, positionMs = 0, audioQuality = null)
    }

    override fun seekTo(positionMs: Long) {
        val duration = mutableState.value.durationMs
        val normalizedPosition = positionMs.coerceAtLeast(0).let { position ->
            duration.takeIf { it > 0 }?.let(position::coerceAtMost) ?: position
        }
        pendingResumePositionMs = null
        mediaController?.seekTo(normalizedPosition)
        mutableState.value = mutableState.value.copy(positionMs = normalizedPosition)
        persistPlaybackState(forcePosition = true)
    }

    internal fun republishRestoredState() {
        val session = restoredSession ?: return
        if (explicitStopRequested || startingPlayback) return
        restoredRepublishSerial += 1L
        publishRestoredState(session, forceGeneration = true)
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
                        applyPendingResumeSeek()
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

    private fun publishRestoredState(
        session: AndroidPlaybackResumeSnapshot,
        forceGeneration: Boolean = false,
    ) {
        val restoredState = session.toPlaybackState()
        val audioFormatInfo = mutableState.value.audioFormatInfo
        mutableState.value = restoredState.copy(
            playbackGeneration = if (forceGeneration) {
                restoredState.playbackGeneration + restoredRepublishSerial
            } else {
                restoredState.playbackGeneration
            },
            audioDecoderInfo = mutableState.value.audioDecoderInfo,
            audioFormatInfo = audioFormatInfo,
            audioQuality = normalizedAudioQualityLabel(rawAudioQuality, audioFormatInfo),
        )
    }

    private fun applyPendingResumeSeek() {
        val position = pendingResumePositionMs ?: return
        val controller = mediaController ?: return
        val currentItem = controller.currentMediaItem ?: return
        val trackId = mutableState.value.currentTrack?.id ?: restoredSession?.currentTrack?.id
        if (trackId != null && !currentItem.matchesTrack(trackId)) return
        controller.seekTo(position)
        pendingResumePositionMs = null
        mutableState.value = mutableState.value.copy(positionMs = position)
        persistPlaybackState(forcePosition = true)
    }

    private fun persistPlaybackState(
        forceSession: Boolean = false,
        forcePosition: Boolean = false,
    ) {
        val plan = activePlan ?: return
        val state = mutableState.value
        val track = state.currentTrack ?: return
        if (state.status !in RESTORABLE_STATUSES) return

        val identity = buildString {
            append(plan.generation)
            append('|')
            append(track.id)
            append('|')
            append(state.currentPartIndex)
            append('|')
            state.playbackParts.forEach { part ->
                append(part.id)
                append(',')
            }
        }
        if (forceSession || identity != lastPersistedIdentity) {
            playbackResumeStore.saveSession(plan, state)
            lastPersistedIdentity = identity
            lastPersistedPositionMs = state.positionMs
            return
        }

        if (forcePosition || abs(state.positionMs - lastPersistedPositionMs) >= POSITION_PERSIST_INTERVAL_MS) {
            playbackResumeStore.savePosition(state.positionMs, state.durationMs)
            lastPersistedPositionMs = state.positionMs
        }
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
        applyPendingResumeSeek()
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
        persistPlaybackState()
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

    private fun PlaybackState.isEmptyIdleState(): Boolean =
        status == PlayerStatus.Idle && currentTrack == null

    private data class PendingLockScreenLyrics(
        val trackId: String,
        val lyrics: String,
    )

    private companion object {
        private const val TAG = "FuoAudioEngine"
        private const val OPLUS_LYRIC_INFO_KEY = "lyricInfo"
        private const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        private val RESTORABLE_STATUSES = setOf(PlayerStatus.Loading, PlayerStatus.Playing, PlayerStatus.Paused)
    }
}
