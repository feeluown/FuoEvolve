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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class FuoPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .build()
            .also { player ->
                player.addAnalyticsListener(object : AnalyticsListener {
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
                val rawPayload = intent.getStringExtra(EXTRA_PAYLOAD) ?: error("Missing playback payload")
                playPayload(rawPayload)
            }.onFailure { throwable ->
                Log.e(TAG, "play payload failed ${intent.getStringExtra(EXTRA_PAYLOAD)?.playbackPayloadSummary().orEmpty()}", throwable)
                player?.stop()
            }
            ACTION_PAUSE -> player?.pause()
            ACTION_RESUME -> player?.play()
            ACTION_STOP -> {
                player?.stop()
                mutableAudioDecoderInfo.value = null
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        mutableAudioDecoderInfo.value = null
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    private fun playPayload(raw: String) {
        val payload = JSONObject(raw)
        val url = payload.getString("url")
        require(url.isNotBlank()) { "Playback URL is blank" }
        val extras = Bundle().apply {
            putString("source", payload.optString("source"))
            putString("source_type", payload.optString("source_type"))
            putString("local_uri", payload.optString("local_uri"))
            putString("provider_id", payload.optString("provider_id"))
            putString("provider_name", payload.optString("provider_name"))
            putBoolean("smart_replacement", payload.optBoolean("smart_replacement", false))
            putString("original_title", payload.optString("original_title"))
            putString("original_provider_name", payload.optString("original_provider_name"))
            putString("original_cover_url", payload.optString("original_cover_url"))
            putString("replacement_title", payload.optString("replacement_title"))
            putString("replacement_artists", payload.optString("replacement_artists"))
            putString("replacement_source", payload.optString("replacement_source"))
            putString("replacement_provider_name", payload.optString("replacement_provider_name"))
            putString("replacement_cover_url", payload.optString("replacement_cover_url"))
            putString("replacement_strategy", payload.optString("replacement_strategy"))
            putDouble("replacement_score", payload.optDouble("replacement_score", 0.0))
            putString("lyrics", payload.optString("lyrics"))
            putString("audio_quality", payload.optString("audio_quality"))
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(payload.optString("track_id").ifBlank { url })
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(payload.optString("title"))
                    .setArtist(payload.optString("artists"))
                    .setAlbumTitle(payload.optString("album"))
                    .setArtworkUri(payload.optString("cover_url").takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setExtras(extras)
                    .build()
            )
            .build()

        val headers = payload.optJSONObject("headers").toStringMap()
        Log.i(
            TAG,
            "play payload trackId=${payload.optString("track_id")} " +
                "source=${payload.optString("source")} url=${url.summarizePlaybackUrl()} " +
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
        val source = ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(mediaItem)

        player?.run {
            setMediaSource(source)
            prepare()
            play()
        }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val keys = keys()
        val result = linkedMapOf<String, String>()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = optString(key)
        }
        return result
    }

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

    private fun String.playbackPayloadSummary(): String {
        return runCatching {
            val payload = JSONObject(this)
            "trackId=${payload.optString("track_id")} source=${payload.optString("source")} " +
                "url=${payload.optString("url").summarizePlaybackUrl()}"
        }.getOrDefault("payload=<invalid>")
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
        private const val EXTRA_PAYLOAD = "payload"
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

        fun play(context: Context, payload: String) {
            start(context, Intent(context, FuoPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_PAYLOAD, payload)
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
}
