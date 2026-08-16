package org.feeluown.mobile

import android.content.Context
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import io.github.proify.lyricon.lyric.model.LyricWord as LyriconLyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine as LyriconRichLyricLine
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val LYRICON_PACKAGE_NAME = "io.github.proify.lyricon"

@Suppress("DEPRECATION")
internal fun isLyriconInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(LYRICON_PACKAGE_NAME, 0)
}.isSuccess

internal class LyriconLyricsPublisher(
    context: Context,
    private val controller: FuoPlayerController,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var collectJob: Job? = null
    private var provider: LyriconProvider? = null
    private var lastTrackKey: String? = null
    private var lastPayload: StatusBarLyricsPayload? = null
    private var lastPublishedStatus: PlayerStatus? = null
    private var lastObservedPositionMs: Long? = null
    private var lastObservedRealtimeMs: Long = 0L
    private var lastObservedStatus: PlayerStatus? = null

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(provider: LyriconProvider) {
            Log.d(TAG, "Lyricon connected")
        }

        override fun onReconnected(provider: LyriconProvider) {
            Log.d(TAG, "Lyricon reconnected")
        }

        override fun onDisconnected(provider: LyriconProvider) {
            Log.w(TAG, "Lyricon disconnected")
        }

        override fun onConnectTimeout(provider: LyriconProvider) {
            Log.w(TAG, "Lyricon connection timed out; playback continues normally")
        }
    }

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            snapshotFlow {
                val state = controller.playbackState
                Snapshot(
                    enabled = controller.statusBarLyricsEnabled,
                    status = state.status,
                    track = state.currentTrack,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    lyrics = state.lyrics,
                )
            }
                .distinctUntilChanged()
                .collect(::publish)
        }
    }

    fun close() {
        collectJob?.cancel()
        collectJob = null
        deactivate()
    }

    private fun publish(snapshot: Snapshot) {
        val track = snapshot.track
        if (
            !snapshot.enabled ||
            track == null ||
            snapshot.status == PlayerStatus.Idle ||
            snapshot.status == PlayerStatus.Error ||
            snapshot.status == PlayerStatus.Ended
        ) {
            deactivate()
            return
        }

        val payload = buildStatusBarLyricsPayload(
            rawLyrics = snapshot.lyrics,
            durationMs = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs,
        )
        if (payload == StatusBarLyricsPayload.Empty) {
            deactivate()
            return
        }

        val activeProvider = ensureProvider() ?: return
        val trackKey = listOf(track.source, track.id, track.title, track.artists).joinToString("\u0000")
        val nowRealtimeMs = SystemClock.elapsedRealtime()
        val songChanged = trackKey != lastTrackKey || payload != lastPayload
        val positionDiscontinuity = songChanged || isPositionDiscontinuity(snapshot, nowRealtimeMs)
        val playbackStateChanged = snapshot.status != lastPublishedStatus

        runCatching {
            if (songChanged) {
                when (payload) {
                    StatusBarLyricsPayload.Empty -> Unit
                    is StatusBarLyricsPayload.Text -> activeProvider.player.sendText(payload.text)
                    is StatusBarLyricsPayload.Timed -> activeProvider.player.setSong(
                        LyriconSong(
                            id = "${track.source}:${track.id}",
                            name = track.title,
                            artist = track.artists,
                            duration = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs ?: 0L,
                            lyrics = payload.lines.map { line ->
                                LyriconRichLyricLine(
                                    begin = line.beginMs,
                                    end = line.endMs,
                                    duration = line.endMs - line.beginMs,
                                    text = line.text,
                                    words = line.words?.map { word ->
                                        LyriconLyricWord(
                                            begin = word.beginMs,
                                            end = word.endMs,
                                            duration = word.endMs - word.beginMs,
                                            text = word.text,
                                        )
                                    },
                                    translation = line.translation,
                                    roma = line.romanization,
                                )
                            },
                        ),
                    )
                }
                lastTrackKey = trackKey
                lastPayload = payload
            }

            // Lyricon has two distinct position paths. PlaybackState supplies a monotonic anchor
            // from which the central service advances position at its own update rate; seekTo()
            // explicitly tells subscribers that playback jumped. Re-sending a fresh PlaybackState
            // for every coarse FuoEvolve position poll can repeatedly re-anchor lyric progress and
            // makes word-synced lyrics appear stuck. Keep the anchor stable during ordinary playback
            // and only replace it when the song/state changes or a real discontinuity is observed.
            if (positionDiscontinuity) {
                activeProvider.player.seekTo(snapshot.positionMs.coerceAtLeast(0L))
            }
            if (songChanged || playbackStateChanged || positionDiscontinuity) {
                activeProvider.player.setPlaybackState(snapshot.toAndroidPlaybackState(nowRealtimeMs))
                lastPublishedStatus = snapshot.status
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to publish Lyricon state; playback is unaffected", throwable)
        }

        lastObservedPositionMs = snapshot.positionMs.coerceAtLeast(0L)
        lastObservedRealtimeMs = nowRealtimeMs
        lastObservedStatus = snapshot.status
    }

    private fun isPositionDiscontinuity(snapshot: Snapshot, nowRealtimeMs: Long): Boolean {
        val previousPositionMs = lastObservedPositionMs ?: return true
        val previousStatus = lastObservedStatus ?: return true
        val elapsedMs = (nowRealtimeMs - lastObservedRealtimeMs).coerceAtLeast(0L)
        val expectedPositionMs = if (previousStatus == PlayerStatus.Playing) {
            previousPositionMs + elapsedMs
        } else {
            previousPositionMs
        }
        return abs(snapshot.positionMs.coerceAtLeast(0L) - expectedPositionMs) > POSITION_DISCONTINUITY_THRESHOLD_MS
    }

    private fun Snapshot.toAndroidPlaybackState(updateRealtimeMs: Long): AndroidPlaybackState {
        val isPlaying = status == PlayerStatus.Playing
        return AndroidPlaybackState.Builder()
            .setState(
                if (isPlaying) AndroidPlaybackState.STATE_PLAYING else AndroidPlaybackState.STATE_PAUSED,
                positionMs.coerceAtLeast(0L),
                if (isPlaying) 1f else 0f,
                updateRealtimeMs,
            )
            .build()
    }

    private fun ensureProvider(): LyriconProvider? {
        provider?.let { return it }
        if (!isLyriconInstalled(appContext)) {
            Log.d(TAG, "Lyricon is not installed; skip status bar lyrics")
            return null
        }
        return runCatching {
            LyriconFactory.createProvider(appContext).also { created ->
                created.autoSync = true
                created.player.setDisplayTranslation(true)
                created.player.setDisplayRoma(true)
                created.service.addConnectionListener(connectionListener)
                if (!created.register()) {
                    Log.w(TAG, "Lyricon provider registration was not started; playback is unaffected")
                }
                provider = created
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to initialize Lyricon; playback is unaffected", throwable)
        }.getOrNull()
    }

    private fun deactivate() {
        val activeProvider = provider ?: return
        runCatching {
            activeProvider.player.setPlaybackState(false)
            activeProvider.player.setSong(null)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to clear Lyricon song", throwable)
        }
        runCatching { activeProvider.unregister() }
            .onFailure { throwable -> Log.w(TAG, "Unable to unregister Lyricon", throwable) }
        runCatching {
            activeProvider.service.removeConnectionListener(connectionListener)
            activeProvider.destroy()
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to destroy Lyricon provider", throwable)
        }
        provider = null
        lastTrackKey = null
        lastPayload = null
        lastPublishedStatus = null
        lastObservedPositionMs = null
        lastObservedRealtimeMs = 0L
        lastObservedStatus = null
    }

    private data class Snapshot(
        val enabled: Boolean,
        val status: PlayerStatus,
        val track: MusicTrack?,
        val positionMs: Long,
        val durationMs: Long,
        val lyrics: String?,
    )

    private companion object {
        const val TAG = "LyriconLyricsPublisher"
        const val POSITION_DISCONTINUITY_THRESHOLD_MS = 750L
    }
}
