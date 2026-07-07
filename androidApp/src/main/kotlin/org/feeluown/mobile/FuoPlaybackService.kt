package org.feeluown.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject

class FuoPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .build()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = SessionCommands.Builder()
                        .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands)
                        .add(COMMAND_PREVIOUS)
                        .add(COMMAND_NEXT)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .setAvailablePlayerCommands(playerCommands())
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_PREVIOUS -> transportControls?.previous()
                        ACTION_NEXT -> transportControls?.next()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
                playPayload(intent.getStringExtra(EXTRA_PAYLOAD) ?: error("Missing playback payload"))
            }.onFailure { throwable ->
                Log.e(TAG, "play payload failed", throwable)
                player?.stop()
            }
            ACTION_PAUSE -> player?.pause()
            ACTION_RESUME -> player?.play()
            ACTION_STOP -> {
                player?.stop()
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

    @Suppress("DEPRECATION")
    private fun handleMediaButtonEvent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
        val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return false
        val handled = when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
            else -> false
        }
        if (!handled) return false
        if (keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount > 0) return true
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> transportControls?.toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> transportControls?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> transportControls?.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> transportControls?.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> transportControls?.previous()
        }
        return true
    }

    companion object {
        private const val ACTION_PLAY = "org.feeluown.mobile.action.PLAY"
        private const val ACTION_PAUSE = "org.feeluown.mobile.action.PAUSE"
        private const val ACTION_RESUME = "org.feeluown.mobile.action.RESUME"
        private const val ACTION_STOP = "org.feeluown.mobile.action.STOP"
        private const val ACTION_PREVIOUS = "org.feeluown.mobile.action.PREVIOUS"
        private const val ACTION_NEXT = "org.feeluown.mobile.action.NEXT"
        private const val EXTRA_PAYLOAD = "payload"
        private const val TAG = "FuoPlaybackService"
        private val COMMAND_PREVIOUS = SessionCommand(ACTION_PREVIOUS, Bundle.EMPTY)
        private val COMMAND_NEXT = SessionCommand(ACTION_NEXT, Bundle.EMPTY)

        @Volatile
        var transportControls: TransportControls? = null

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
                .setSessionCommand(COMMAND_PREVIOUS)
                .setDisplayName("上一首")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setSessionCommand(COMMAND_NEXT)
                .setDisplayName("下一首")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )

        @OptIn(UnstableApi::class)
        private fun playerCommands(): Player.Commands {
            return Player.Commands.Builder()
                .addAllCommands()
                .removeAll(
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                )
                .build()
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
