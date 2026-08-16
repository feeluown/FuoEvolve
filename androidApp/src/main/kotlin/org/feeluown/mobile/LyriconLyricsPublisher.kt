package org.feeluown.mobile

import android.content.Context
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
import kotlinx.coroutines.delay
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
    private var positionSyncJob: Job? = null
    private var provider: LyriconProvider? = null
    private var lastTrackKey: String? = null
    private var lastPayload: StatusBarLyricsPayload? = null
    private var lastPublishedStatus: PlayerStatus? = null
    private var lastObservedPositionMs: Long? = null
    private var lastObservedRealtimeMs: Long = 0L
    private var lastObservedStatus: PlayerStatus? = null
    private var anchorPositionMs: Long = 0L
    private var anchorRealtimeMs: Long = 0L
    private var anchorPlaying: Boolean = false
    @Volatile
    private var latestSnapshot: Snapshot? = null

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(provider: LyriconProvider) {
            Log.d(TAG, "Lyricon connected")
            syncManualPlaybackState(provider)
        }

        override fun onReconnected(provider: LyriconProvider) {
            Log.d(TAG, "Lyricon reconnected")
            syncManualPlaybackState(provider)
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
        val safePositionMs = snapshot.positionMs.coerceAtLeast(0L)

        latestSnapshot = snapshot
        updatePositionAnchor(snapshot, nowRealtimeMs)

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

            // Follow Lyricon's documented manual synchronization path. setPosition() writes the
            // current position to the shared-memory bridge and setPlaybackState(Boolean) makes the
            // central service consume that bridge. Do not mix this with PlaybackState/State2,
            // because seekTo() does not replace State2's position anchor and the next tick can jump
            // back to the old position.
            activeProvider.player.setPosition(safePositionMs)
            if (positionDiscontinuity) {
                activeProvider.player.seekTo(safePositionMs)
            }
            if (songChanged || playbackStateChanged) {
                activeProvider.player.setPlaybackState(snapshot.status == PlayerStatus.Playing)
            }
            lastPublishedStatus = snapshot.status
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to publish Lyricon state; playback is unaffected", throwable)
        }

        if (snapshot.status == PlayerStatus.Playing) {
            ensurePositionSyncLoop()
        } else {
            stopPositionSyncLoop()
        }

        lastObservedPositionMs = safePositionMs
        lastObservedRealtimeMs = nowRealtimeMs
        lastObservedStatus = snapshot.status
    }

    private fun updatePositionAnchor(snapshot: Snapshot, realtimeMs: Long) {
        anchorPositionMs = snapshot.positionMs.coerceAtLeast(0L)
        anchorRealtimeMs = realtimeMs
        anchorPlaying = snapshot.status == PlayerStatus.Playing
    }

    private fun estimatedPositionMs(realtimeMs: Long = SystemClock.elapsedRealtime()): Long {
        val base = anchorPositionMs
        if (!anchorPlaying) return base
        return (base + (realtimeMs - anchorRealtimeMs).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun ensurePositionSyncLoop() {
        if (positionSyncJob?.isActive == true) return
        positionSyncJob = scope.launch {
            while (true) {
                val activeProvider = provider ?: break
                val snapshot = latestSnapshot ?: break
                if (!snapshot.enabled || snapshot.status != PlayerStatus.Playing) break

                // Lyricon 0.1.70 reads the manual position bridge at ~24 fps by default. Keep the
                // shared-memory value moving at a similar cadence, while the coarser playback-state
                // updates periodically correct this elapsed-realtime interpolation anchor.
                runCatching {
                    activeProvider.player.setPosition(estimatedPositionMs())
                }
                delay(POSITION_SYNC_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionSyncLoop() {
        positionSyncJob?.cancel()
        positionSyncJob = null
    }

    private fun syncManualPlaybackState(activeProvider: LyriconProvider) {
        val snapshot = latestSnapshot ?: return
        if (!snapshot.enabled) return

        runCatching {
            val positionMs = estimatedPositionMs()
            activeProvider.player.setPosition(positionMs)
            activeProvider.player.setPlaybackState(snapshot.status == PlayerStatus.Playing)
            if (snapshot.status == PlayerStatus.Playing) {
                ensurePositionSyncLoop()
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to resync Lyricon playback state", throwable)
        }
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
        stopPositionSyncLoop()
        latestSnapshot = null
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
        anchorPositionMs = 0L
        anchorRealtimeMs = 0L
        anchorPlaying = false
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
        const val POSITION_SYNC_INTERVAL_MS = 40L
    }
}
