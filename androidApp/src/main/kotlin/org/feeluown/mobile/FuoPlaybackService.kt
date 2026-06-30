package org.feeluown.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONObject

class FuoPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> playPayload(requireNotNull(intent.getStringExtra(EXTRA_PAYLOAD)))
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
        val extras = Bundle().apply {
            putString("source", payload.optString("source"))
            putString("source_type", payload.optString("source_type"))
            putString("local_uri", payload.optString("local_uri"))
            putString("provider_id", payload.optString("provider_id"))
            putString("provider_name", payload.optString("provider_name"))
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(payload.optString("track_id").ifBlank { payload.getString("url") })
            .setUri(payload.getString("url"))
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
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val sourceFactory = DefaultDataSource.Factory(this, httpFactory)
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

    companion object {
        private const val ACTION_PLAY = "org.feeluown.mobile.action.PLAY"
        private const val ACTION_PAUSE = "org.feeluown.mobile.action.PAUSE"
        private const val ACTION_RESUME = "org.feeluown.mobile.action.RESUME"
        private const val ACTION_STOP = "org.feeluown.mobile.action.STOP"
        private const val EXTRA_PAYLOAD = "payload"

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
    }
}
