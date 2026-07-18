package org.feeluown.mobile

import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

@OptIn(UnstableApi::class)
class FuoPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var preloadJob: Job? = null
    private val pendingLock = Any()
    private val pendingRequests = ArrayDeque<PlaybackRequest>()
    private var preloadingGeneration: Long? = null
    private val preparedItems = mutableMapOf<String, PreparedPlayback>()
    private var activePlayback: PreparedPlayback? = null
    private var pendingPreloadError: String? = null
    @Volatile
    private var activeGeneration: Long = 0L
    private var itemSerial: Long = 0L
    private val spectrumAnalyzer = AudioSpectrumAnalyzer { levels ->
        mutableSpectrumLevels.value = levels
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val renderersFactory = spectrumRenderersFactory()
        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .build()
            .also { player ->
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?,
                    ) {
                        mutableAudioFormatInfo.value = format.toAudioFormatInfo()
                    }

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        mutableAudioDecoderInfo.value = AudioDecoderInfo(
                            type = decoderName.toAudioDecoderType(),
                            name = decoderName,
                        )
                    }
                })
                player.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val prepared = mediaItem?.mediaId?.let(preparedItems::get) ?: return
                        activePlayback = prepared
                        enqueueRemainingParts(prepared)
                        publishPlaybackState()
                        preloadNext()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        publishPlaybackState()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        publishPlaybackState()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val item = player.currentMediaItem
                        Log.e(
                            TAG,
                            "exo playback error trackId=${item?.mediaId.orEmpty()} " +
                                "source=${item?.mediaMetadata?.extras?.getString("source").orEmpty()} " +
                                "url=${item?.localConfiguration?.uri?.toString()?.summarizePlaybackUrl().orEmpty()} " +
                                "code=${error.errorCodeName} state=${player.playbackState}",
                            error,
                        )
                        mutablePlaybackState.value = mutablePlaybackState.value.copy(
                            status = PlayerStatus.Error,
                            errorMessage = playbackErrorMessage(error),
                        )
                    }
                })
            }
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, QueueCommandPlayer(exoPlayer))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(queuePlayerCommands(exoPlayer.getAvailableCommands()))
                        .build()
                }

                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    return handleMediaButtonEvent(intent)
                }
            })
            .setMediaButtonPreferences(mediaButtonPreferences())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> runCatching {
                val rawPlan = intent.getStringExtra(EXTRA_PLAN) ?: error("Missing playback plan")
                playPlan(rawPlan.toPlaybackPlan())
            }.onFailure { throwable ->
                Log.e(TAG, "play plan failed", throwable)
                player?.stop()
                mutablePlaybackState.value = PlaybackState(
                    status = PlayerStatus.Error,
                    errorMessage = throwable.message ?: "播放计划无效",
                )
            }
            ACTION_PAUSE -> player?.pause()
            ACTION_RESUME -> player?.play()
            ACTION_STOP -> {
                player?.stop()
                mutableAudioDecoderInfo.value = null
                mutableAudioFormatInfo.value = null
                spectrumAnalyzer.clear()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loadJob?.cancel()
        preloadJob?.cancel()
        synchronized(pendingLock) {
            pendingRequests.clear()
            preloadingGeneration = null
        }
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        mutableAudioDecoderInfo.value = null
        mutableAudioFormatInfo.value = null
        mutablePlaybackState.value = PlaybackState()
        spectrumAnalyzer.clear()
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    private fun playPlan(plan: PlaybackPlan) {
        val first = plan.requests.firstOrNull() ?: error("Playback plan is empty")
        loadJob?.cancel()
        preloadJob?.cancel()
        synchronized(pendingLock) {
            pendingRequests.clear()
            pendingRequests.addAll(plan.requests.drop(1))
            preloadingGeneration = null
        }
        preparedItems.clear()
        activePlayback = null
        pendingPreloadError = null
        activeGeneration = plan.generation
        mutableAudioFormatInfo.value = null
        mutablePlaybackState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = first.track,
            durationMs = first.track.durationMs ?: 0L,
            lyrics = first.track.lyrics,
            playbackGeneration = plan.generation,
        )
        loadJob = serviceScope.launch {
            try {
                val prepared = resolvePlayback(first)
                if (activeGeneration != plan.generation) return@launch
                withContext(Dispatchers.Main) {
                    if (activeGeneration != plan.generation) return@withContext
                    activePlayback = prepared
                    preparedItems[prepared.mediaItem.mediaId] = prepared
                    player?.run {
                        setMediaSource(prepared.mediaSource)
                        prepare()
                        play()
                    }
                    publishPlaybackState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (activeGeneration != plan.generation) return@launch
                Log.e(TAG, "resolve failed trackId=${first.track.id} generation=${plan.generation}", throwable)
                mutablePlaybackState.value = PlaybackState(
                    status = PlayerStatus.Error,
                    currentTrack = first.track,
                    playbackGeneration = plan.generation,
                    errorMessage = throwable.message ?: "音频资源加载失败",
                )
            }
        }
    }

    private fun preloadNext() {
        val generation = activeGeneration
        val request = synchronized(pendingLock) {
            if (preloadingGeneration != null) {
                null
            } else {
                pendingRequests.removeFirstOrNull()?.also { preloadingGeneration = generation }
            }
        } ?: return
        preloadJob = serviceScope.launch {
            var retryNext = false
            var stopMessage: String? = null
            try {
                val prepared = resolvePlayback(request)
                if (activeGeneration != generation) return@launch
                withContext(Dispatchers.Main) {
                    if (activeGeneration != generation) return@withContext
                    preparedItems[prepared.mediaItem.mediaId] = prepared
                    player?.run {
                        addMediaSource(prepared.mediaSource)
                        if (playbackState == Player.STATE_ENDED) {
                            seekToNextMediaItem()
                            play()
                        }
                    }
                    Log.i(TAG, "preloaded trackId=${prepared.track.id} generation=$generation")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (activeGeneration == generation) {
                    Log.w(TAG, "preload failed trackId=${request.track.id}", throwable)
                    if (request.unavailablePolicy == UnavailablePlaybackPolicy.Skip ||
                        request.unavailablePolicy == UnavailablePlaybackPolicy.SmartReplace
                    ) {
                        retryNext = true
                    } else {
                        stopMessage = "下一首资源加载失败：${request.track.title}（${throwable.message ?: "未知错误"}）"
                    }
                }
            } finally {
                synchronized(pendingLock) {
                    if (preloadingGeneration == generation) preloadingGeneration = null
                }
            }
            if (retryNext) {
                withContext(Dispatchers.Main) {
                    publishPlaybackState()
                    preloadNext()
                }
            } else if (stopMessage != null) {
                withContext(Dispatchers.Main) {
                    if (activeGeneration == generation) {
                        pendingPreloadError = stopMessage
                        publishPlaybackState()
                    }
                }
            }
        }
    }

    private fun enqueueRemainingParts(prepared: PreparedPlayback) {
        val currentPartIndex = prepared.payload.currentPartIndex
            .takeIf { it in prepared.payload.parts.indices }
            ?: prepared.request.requestedPartIndex?.takeIf { it in prepared.payload.parts.indices }
            ?: -1
        if (currentPartIndex < 0 || currentPartIndex >= prepared.payload.parts.lastIndex) return
        val parts = prepared.payload.parts.drop(currentPartIndex + 1).map { part ->
            prepared.request.copy(
                resolveTrack = part.toTrack(prepared.request.track),
                requestedPartIndex = prepared.payload.parts.indexOf(part),
            )
        }
        synchronized(pendingLock) {
            parts.asReversed().forEach(pendingRequests::addFirst)
        }
    }

    private suspend fun resolvePlayback(request: PlaybackRequest): PreparedPlayback {
        val payload = withTimeout(PLAYBACK_RESOLVE_TIMEOUT_MS) {
            request.resolveTrack.localUri?.let { uri -> request.resolveTrack.toLocalPayload(uri) }
                ?: (application as FuoEvolveApplication).providerRepository.resolve(
                    request.resolveTrack,
                    request.unavailablePolicy,
                    request.smartReplacementProviderIds,
                    request.smartReplacementMinScore,
                    request.smartReplacementUseOriginalMetadata,
                    request.smartReplacementUseOriginalLyrics,
                )
        }
        val parts = payload.parts
        val currentPartIndex = when {
            parts.isEmpty() -> -1
            payload.currentPartIndex in parts.indices -> payload.currentPartIndex
            request.requestedPartIndex?.let { it in parts.indices } == true -> request.requestedPartIndex ?: -1
            else -> -1
        }
        val track = request.track.copy(
            title = if (parts.isEmpty()) payload.title.ifBlank { request.track.title } else request.track.title,
            artists = payload.artists.ifBlank { request.track.artists },
            album = payload.album.ifBlank { request.track.album },
            source = payload.source.ifBlank { request.track.source },
            coverUrl = payload.coverUrl ?: request.track.coverUrl,
            durationMs = if (parts.isEmpty()) payload.durationMs ?: request.track.durationMs else request.track.durationMs,
            providerName = payload.providerName ?: request.track.providerName,
            isSmartReplacement = payload.isSmartReplacement,
            originalTitle = payload.originalTitle,
            originalProviderName = payload.originalProviderName,
            originalCoverUrl = payload.originalCoverUrl,
            replacementTitle = payload.replacementTitle,
            replacementArtists = payload.replacementArtists,
            replacementSource = payload.replacementSource,
            replacementProviderName = payload.replacementProviderName,
            replacementCoverUrl = payload.replacementCoverUrl,
            replacementStrategy = payload.replacementStrategy,
            replacementScore = payload.replacementScore,
            isUnavailable = false,
        )
        val mediaItem = createMediaItem(track, payload, parts, currentPartIndex)
        return PreparedPlayback(
            request = request,
            track = track,
            payload = payload.copy(currentPartIndex = currentPartIndex),
            mediaItem = mediaItem,
            mediaSource = createMediaSource(mediaItem, payload.headers),
        )
    }

    private fun createMediaItem(
        track: MusicTrack,
        payload: PlaybackPayload,
        parts: List<PlaybackPart>,
        currentPartIndex: Int,
    ): MediaItem {
        val url = payload.url
        require(url.isNotBlank()) { "Playback URL is blank" }
        val extras = Bundle().apply {
            putString("source", track.source)
            putString("source_type", track.sourceType.name)
            putString("local_uri", track.localUri.orEmpty())
            putString("provider_id", track.providerId.orEmpty())
            putString("provider_name", track.providerName.orEmpty())
            putBoolean("smart_replacement", track.isSmartReplacement)
            putString("original_title", track.originalTitle.orEmpty())
            putString("original_provider_name", track.originalProviderName.orEmpty())
            putString("original_cover_url", track.originalCoverUrl.orEmpty())
            putString("replacement_title", track.replacementTitle.orEmpty())
            putString("replacement_artists", track.replacementArtists.orEmpty())
            putString("replacement_source", track.replacementSource.orEmpty())
            putString("replacement_provider_name", track.replacementProviderName.orEmpty())
            putString("replacement_cover_url", track.replacementCoverUrl.orEmpty())
            putString("replacement_strategy", track.replacementStrategy.orEmpty())
            putDouble("replacement_score", track.replacementScore ?: 0.0)
            putString("lyrics", payload.lyrics.orEmpty())
            putString("audio_quality", payload.audioQuality.orEmpty())
            putString("playback_parts", JSONArray().apply {
                parts.forEach { part -> put(JSONObject().put("id", part.id).put("title", part.title).put("duration_ms", part.durationMs)) }
            }.toString())
            putInt("current_part_index", currentPartIndex)
            putLong("playback_generation", activeGeneration)
        }
        return MediaItem.Builder()
            .setMediaId("$activeGeneration:${++itemSerial}:${track.id}")
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artists)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.coverUrl?.let(Uri::parse))
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun createMediaSource(mediaItem: MediaItem, headers: Map<String, String>): ProgressiveMediaSource {
        val url = mediaItem.localConfiguration?.uri?.toString().orEmpty()
        Log.i(
            TAG,
            "play source trackId=${mediaItem.mediaId} " +
                "source=${mediaItem.mediaMetadata.extras?.getString("source").orEmpty()} url=${mediaItem.localConfiguration?.uri.toString().summarizePlaybackUrl()} " +
                "headerKeys=${headers.keys.joinToString(prefix = "[", postfix = "]")}",
        )
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val upstreamFactory = DefaultDataSource.Factory(this, httpFactory)
        val sourceFactory = if (url.isRemoteUrl()) {
            CacheDataSource.Factory()
                .setCache(AndroidResourceCache.audioCache(this))
                .setUpstreamDataSourceFactory(upstreamFactory)
        } else {
            upstreamFactory
        }
        return ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(mediaItem)
    }

    private fun publishPlaybackState() {
        val prepared = activePlayback ?: return
        val currentPlayer = player ?: return
        if (currentPlayer.playbackState == Player.STATE_ENDED && pendingPreloadError != null) {
            mutablePlaybackState.value = PlaybackState(
                status = PlayerStatus.Error,
                currentTrack = prepared.track,
                positionMs = currentPlayer.currentPosition.coerceAtLeast(0L),
                durationMs = currentPlayer.duration.takeIf { it > 0L } ?: prepared.payload.durationMs ?: 0L,
                bufferedMs = currentPlayer.bufferedPosition.coerceAtLeast(0L),
                lyrics = prepared.payload.lyrics,
                audioQuality = prepared.payload.audioQuality,
                playbackParts = prepared.payload.parts,
                currentPartIndex = prepared.payload.currentPartIndex,
                playbackGeneration = activeGeneration,
                errorMessage = pendingPreloadError,
            )
            return
        }
        val status = when {
            currentPlayer.playbackState == Player.STATE_ENDED && hasPendingOrLoadingRequest() -> PlayerStatus.Loading
            currentPlayer.playbackState == Player.STATE_ENDED -> PlayerStatus.Ended
            currentPlayer.playbackState == Player.STATE_BUFFERING -> PlayerStatus.Loading
            currentPlayer.isPlaying -> PlayerStatus.Playing
            currentPlayer.playbackState == Player.STATE_READY -> PlayerStatus.Paused
            else -> PlayerStatus.Loading
        }
        mutablePlaybackState.value = PlaybackState(
            status = status,
            currentTrack = prepared.track,
            positionMs = currentPlayer.currentPosition.coerceAtLeast(0L),
            durationMs = currentPlayer.duration.takeIf { it > 0L } ?: prepared.payload.durationMs ?: 0L,
            bufferedMs = currentPlayer.bufferedPosition.coerceAtLeast(0L),
            lyrics = prepared.payload.lyrics,
            audioQuality = prepared.payload.audioQuality,
            playbackParts = prepared.payload.parts,
            currentPartIndex = prepared.payload.currentPartIndex,
            playbackGeneration = activeGeneration,
        )
    }

    private fun hasPendingOrLoadingRequest(): Boolean = synchronized(pendingLock) {
        preloadingGeneration == activeGeneration || pendingRequests.isNotEmpty()
    }

    private fun PlaybackPart.toTrack(parent: MusicTrack): MusicTrack = parent.copy(
        id = id,
        title = title.ifBlank { parent.title },
        durationMs = durationMs ?: parent.durationMs,
        providerId = id,
    )

    private fun MusicTrack.toLocalPayload(uri: String): PlaybackPayload = PlaybackPayload(
        url = uri,
        title = title,
        artists = artists,
        album = album,
        source = source,
        coverUrl = coverUrl,
        durationMs = durationMs,
        lyrics = lyrics,
        providerName = providerName,
        isSmartReplacement = isSmartReplacement,
        originalTitle = originalTitle,
        originalProviderName = originalProviderName,
        originalCoverUrl = originalCoverUrl,
        replacementTitle = replacementTitle,
        replacementArtists = replacementArtists,
        replacementSource = replacementSource,
        replacementProviderName = replacementProviderName,
        replacementCoverUrl = replacementCoverUrl,
        replacementStrategy = replacementStrategy,
        replacementScore = replacementScore,
    )

    private fun playbackErrorMessage(error: PlaybackException): String {
        return listOf(error.errorCodeName, error.message)
            .filterNot { it.isNullOrBlank() }
            .joinToString(": ")
            .ifBlank { "播放失败" }
    }

    private data class PreparedPlayback(
        val request: PlaybackRequest,
        val track: MusicTrack,
        val payload: PlaybackPayload,
        val mediaItem: MediaItem,
        val mediaSource: ProgressiveMediaSource,
    )

    private fun String.isRemoteUrl(): Boolean {
        val scheme = Uri.parse(this).scheme
        return scheme == "http" || scheme == "https"
    }

    private fun String.toAudioDecoderType(): AudioDecoderType {
        val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name.equals(this, ignoreCase = true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo != null) {
            return if (codecInfo.isHardwareAccelerated) {
                AudioDecoderType.Hardware
            } else {
                AudioDecoderType.Software
            }
        }
        return if (
            startsWith("ffmpeg", ignoreCase = true) ||
            startsWith("omx.google.", ignoreCase = true) ||
            startsWith("c2.android.", ignoreCase = true)
        ) {
            AudioDecoderType.Software
        } else {
            AudioDecoderType.Hardware
        }
    }

    private fun Format.toAudioFormatInfo(): AudioFormatInfo {
        val average = averageBitrate.takeIf { it > 0 }?.toLong()
        val peak = peakBitrate.takeIf { it > 0 }?.toLong()
        return AudioFormatInfo(
            format = sampleMimeType ?: containerMimeType,
            codec = codecs,
            averageBitrate = average,
            peakBitrate = peak,
        )
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

    @OptIn(UnstableApi::class)
    private class QueueCommandPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands {
            return queuePlayerCommands(super.getAvailableCommands())
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return command in queueNavigationCommands || super.isCommandAvailable(command)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun next() {
            transportControls?.next()
        }

        override fun seekToNext() {
            transportControls?.next()
        }

        override fun seekToNextMediaItem() {
            transportControls?.next()
        }

        override fun seekToPrevious() {
            transportControls?.previous()
        }

        override fun seekToPreviousMediaItem() {
            transportControls?.previous()
        }
    }

    companion object {
        private const val ACTION_PLAY = "org.feeluown.mobile.action.PLAY"
        private const val ACTION_PAUSE = "org.feeluown.mobile.action.PAUSE"
        private const val ACTION_RESUME = "org.feeluown.mobile.action.RESUME"
        private const val ACTION_STOP = "org.feeluown.mobile.action.STOP"
        private const val EXTRA_PLAN = "plan"
        private const val PLAYBACK_RESOLVE_TIMEOUT_MS = 30_000L
        private const val TAG = "FuoPlaybackService"
        private val queueNavigationCommands = setOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )

        @Volatile
        var transportControls: TransportControls? = null

        private val mutableAudioDecoderInfo = MutableStateFlow<AudioDecoderInfo?>(null)
        val audioDecoderInfo: StateFlow<AudioDecoderInfo?> = mutableAudioDecoderInfo.asStateFlow()

        private val mutableAudioFormatInfo = MutableStateFlow<AudioFormatInfo?>(null)
        val audioFormatInfo: StateFlow<AudioFormatInfo?> = mutableAudioFormatInfo.asStateFlow()

        private val mutableSpectrumLevels = MutableStateFlow<List<Float>>(emptyList())
        val spectrumLevels: StateFlow<List<Float>> = mutableSpectrumLevels.asStateFlow()

        private val mutablePlaybackState = MutableStateFlow(PlaybackState())
        val playbackState: StateFlow<PlaybackState> = mutablePlaybackState.asStateFlow()

        fun play(context: Context, plan: String) {
            start(context, Intent(context, FuoPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_PLAN, plan)
            })
        }

        fun pause(context: Context) {
            start(context, Intent(context, FuoPlaybackService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            start(context, Intent(context, FuoPlaybackService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            start(context, Intent(context, FuoPlaybackService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, intent: Intent) {
            context.startService(intent)
        }

        @OptIn(UnstableApi::class)
        private fun mediaButtonPreferences(): List<CommandButton> = listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName("上一首")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName("下一首")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )

        @OptIn(UnstableApi::class)
        @Suppress("WrongConstant")
        private fun queuePlayerCommands(commands: Player.Commands): Player.Commands {
            return Player.Commands.Builder()
                .addAll(commands)
                .addAll(*queueNavigationCommands.toIntArray())
                .build()
        }

        @Suppress("DEPRECATION")
        private fun handleMediaButtonEvent(intent: Intent): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            return when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        transportControls?.next()
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        transportControls?.previous()
                    }
                    true
                }
                else -> false
            }
        }
    }

    interface TransportControls {
        fun toggle()
        fun play()
        fun pause()
        fun previous()
        fun next()
    }

    private fun spectrumRenderersFactory(): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(this@FuoPlaybackService) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(spectrumAnalyzer)))
                    .build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
    }
}
