package org.feeluown.mobile

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

/**
 * Compatibility publisher for car/head-unit lyrics over Bluetooth AVRCP.
 *
 * AVRCP has no standard lyrics field, so compatible players expose the current lyric line through
 * the media title while Bluetooth is the active media route. This publisher deliberately sits at
 * the Android MediaSession boundary: FuoEvolve's PlaybackSession, queue, history and UI continue to
 * use the canonical track metadata.
 *
 * Android currently exposes one MediaMetadata value to legacy controllers, so other platform media
 * controllers can observe the decorated title while this compatibility mode is active. The setting
 * is therefore opt-in and disabled by default.
 */
internal class BluetoothLyricsPublisher(
    context: Context,
    private val playbackSession: PlaybackSession,
    private val enabled: Flow<Boolean>,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var collectJob: Job? = null
    private var tickJob: Job? = null
    private var mediaController: MediaController? = null
    private var controllerConnecting = false
    private var closed = false
    private var latestSnapshot: Snapshot? = null
    private var anchorLyricsPositionMs = 0L
    private var anchorRealtimeMs = 0L
    private var anchorPlaying = false
    private var lastApplied: AppliedMetadata? = null

    fun start() {
        if (collectJob != null || closed) return
        collectJob = scope.launch {
            combine(
                playbackSession.state,
                enabled.distinctUntilChanged(),
            ) { state, bluetoothLyricsEnabled ->
                Snapshot(
                    enabled = bluetoothLyricsEnabled,
                    status = state.status,
                    track = state.currentTrack,
                    lyricsPositionMs = state.lyricsPositionMs,
                    lyrics = state.lyrics,
                )
            }.collect(::publish)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        collectJob?.cancel()
        collectJob = null
        tickJob?.cancel()
        tickJob = null
        restoreCanonicalMetadata(latestSnapshot?.track)
        mediaController?.release()
        mediaController = null
        scope.cancel()
    }

    private fun publish(snapshot: Snapshot) {
        latestSnapshot = snapshot
        anchorLyricsPositionMs = snapshot.lyricsPositionMs.coerceAtLeast(0L)
        anchorRealtimeMs = SystemClock.elapsedRealtime()
        anchorPlaying = snapshot.status == PlaybackSessionStatus.Playing

        if (!snapshot.shouldPublishLyrics()) {
            tickJob?.cancel()
            tickJob = null
            restoreCanonicalMetadata(snapshot.track)
            return
        }

        applyForPosition(snapshot, anchorLyricsPositionMs)
        ensureTickLoop()
    }

    private fun ensureTickLoop() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                val snapshot = latestSnapshot ?: break
                if (!snapshot.shouldPublishLyrics()) break
                val positionMs = if (anchorPlaying) {
                    anchorLyricsPositionMs + (SystemClock.elapsedRealtime() - anchorRealtimeMs).coerceAtLeast(0L)
                } else {
                    anchorLyricsPositionMs
                }
                applyForPosition(snapshot, positionMs)
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    private fun applyForPosition(snapshot: Snapshot, positionMs: Long) {
        val track = snapshot.track ?: return
        if (!isBluetoothMediaOutputActive(audioManager)) {
            restoreCanonicalMetadata(track)
            return
        }
        val lyricLine = bluetoothLyricLine(snapshot.lyrics, positionMs)
        if (lyricLine == null) {
            restoreCanonicalMetadata(track)
            return
        }
        val display = bluetoothLyricsDisplay(track.title, track.artists, lyricLine)
        replaceCurrentMetadata(track, display)
    }

    private fun replaceCurrentMetadata(track: TrackRef, display: BluetoothLyricsDisplay) {
        val controller = mediaController ?: run {
            connectController()
            return
        }
        if (!controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            Log.w(TAG, "media session does not allow Bluetooth lyrics metadata replacement")
            return
        }
        val currentItem = controller.currentMediaItem ?: return
        if (!currentItem.mediaId.endsWith(":${track.id}")) return
        val currentIndex = controller.currentMediaItemIndex
        if (currentIndex < 0) return
        val desired = AppliedMetadata(
            mediaId = currentItem.mediaId,
            title = display.title,
            artist = display.artist,
            decorated = true,
        )
        if (
            lastApplied == desired &&
            currentItem.mediaMetadata.title?.toString() == display.title &&
            currentItem.mediaMetadata.artist?.toString() == display.artist
        ) {
            return
        }
        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(
                currentItem.mediaMetadata.buildUpon()
                    .setTitle(display.title)
                    .setArtist(display.artist)
                    .build(),
            )
            .build()
        runCatching { controller.replaceMediaItem(currentIndex, updatedItem) }
            .onSuccess { lastApplied = desired }
            .onFailure { throwable ->
                Log.w(TAG, "failed to publish Bluetooth lyrics trackId=${track.id}", throwable)
            }
    }

    private fun restoreCanonicalMetadata(track: TrackRef?) {
        val applied = lastApplied?.takeIf { it.decorated } ?: return
        val canonicalTrack = track ?: return
        val controller = mediaController ?: return
        if (!controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val currentItem = controller.currentMediaItem ?: return
        if (currentItem.mediaId != applied.mediaId || !currentItem.mediaId.endsWith(":${canonicalTrack.id}")) {
            lastApplied = null
            return
        }
        val currentIndex = controller.currentMediaItemIndex
        if (currentIndex < 0) return
        if (
            currentItem.mediaMetadata.title?.toString() == canonicalTrack.title &&
            currentItem.mediaMetadata.artist?.toString() == canonicalTrack.artists
        ) {
            lastApplied = null
            return
        }
        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(
                currentItem.mediaMetadata.buildUpon()
                    .setTitle(canonicalTrack.title)
                    .setArtist(canonicalTrack.artists)
                    .build(),
            )
            .build()
        runCatching { controller.replaceMediaItem(currentIndex, updatedItem) }
            .onSuccess { lastApplied = null }
            .onFailure { throwable ->
                Log.w(TAG, "failed to restore canonical media metadata trackId=${canonicalTrack.id}", throwable)
            }
    }

    private fun connectController() {
        if (controllerConnecting || mediaController != null || closed) return
        controllerConnecting = true
        val token = SessionToken(appContext, ComponentName(appContext, FuoPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                controllerConnecting = false
                runCatching { future.get() }
                    .onSuccess { controller ->
                        if (closed) {
                            controller.release()
                            return@onSuccess
                        }
                        mediaController = controller
                        latestSnapshot?.let { snapshot ->
                            val positionMs = if (anchorPlaying) {
                                anchorLyricsPositionMs + (SystemClock.elapsedRealtime() - anchorRealtimeMs).coerceAtLeast(0L)
                            } else {
                                anchorLyricsPositionMs
                            }
                            if (snapshot.shouldPublishLyrics()) {
                                applyForPosition(snapshot, positionMs)
                            }
                        }
                    }
                    .onFailure { throwable ->
                        Log.w(TAG, "unable to connect Bluetooth lyrics media controller", throwable)
                    }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun Snapshot.shouldPublishLyrics(): Boolean =
        enabled &&
            track != null &&
            !lyrics.isNullOrBlank() &&
            (status == PlaybackSessionStatus.Playing || status == PlaybackSessionStatus.Paused)

    private data class Snapshot(
        val enabled: Boolean,
        val status: PlaybackSessionStatus,
        val track: TrackRef?,
        val lyricsPositionMs: Long,
        val lyrics: String?,
    )

    private data class AppliedMetadata(
        val mediaId: String,
        val title: String,
        val artist: String,
        val decorated: Boolean,
    )

    private companion object {
        const val TAG = "BluetoothLyrics"
        const val PUBLISH_INTERVAL_MS = 250L
    }
}

internal data class BluetoothLyricsDisplay(
    val title: String,
    val artist: String,
)

internal fun bluetoothLyricLine(rawLyrics: String?, positionMs: Long): String? {
    val timedLines = parseLyrics(rawLyrics).takeWhile { it.timeMs != Long.MAX_VALUE }
    if (timedLines.isEmpty()) return null
    val normalizedPosition = positionMs.coerceAtLeast(0L)
    if (normalizedPosition < timedLines.first().timeMs) return null
    val index = currentLyricIndex(timedLines, normalizedPosition)
    return timedLines.getOrNull(index)?.text?.trim()?.takeIf(String::isNotBlank)
}

internal fun bluetoothLyricsDisplay(
    trackTitle: String,
    trackArtists: String,
    lyricLine: String,
): BluetoothLyricsDisplay {
    val canonicalArtist = listOf(trackTitle, trackArtists)
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" · ")
        .ifBlank { trackArtists.trim() }
    return BluetoothLyricsDisplay(
        title = lyricLine.trim(),
        artist = canonicalArtist,
    )
}

internal fun isBluetoothMediaOutputActive(audioManager: AudioManager?): Boolean {
    if (audioManager == null) return false
    val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            audioManager.getAudioDevicesForAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
        }.getOrElse { emptyList() }
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }
    return devices.any(::isBluetoothMediaDevice)
}

private fun isBluetoothMediaDevice(device: AudioDeviceInfo): Boolean = when (device.type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_HEARING_AID,
    -> true
    else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        )
}
