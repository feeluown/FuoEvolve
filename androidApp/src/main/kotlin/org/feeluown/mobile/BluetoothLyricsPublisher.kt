package org.feeluown.mobile

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
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
    @Suppress("DEPRECATION")
    private val mediaRouter = appContext.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
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
    private var cachedLyricsRaw: String? = null
    private var cachedTimedLines: List<LyricLine> = emptyList()
    private var lastApplied: AppliedMetadata? = null
    private var lastRouteDecision: BluetoothMediaRouteDecision? = null

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
        restoreOriginalMetadata()
        mediaController?.release()
        mediaController = null
        scope.cancel()
    }

    private fun publish(snapshot: Snapshot) {
        latestSnapshot = snapshot
        anchorLyricsPositionMs = snapshot.lyricsPositionMs.coerceAtLeast(0L)
        anchorRealtimeMs = SystemClock.elapsedRealtime()
        anchorPlaying = snapshot.status == PlaybackSessionStatus.Playing
        updateLyricsTimeline(snapshot.lyrics)

        if (!snapshot.shouldPublishLyrics()) {
            tickJob?.cancel()
            tickJob = null
            restoreOriginalMetadata()
            return
        }

        applyForPosition(snapshot, anchorLyricsPositionMs)
        ensureTickLoop()
    }

    private fun updateLyricsTimeline(rawLyrics: String?) {
        if (rawLyrics == cachedLyricsRaw) return
        cachedLyricsRaw = rawLyrics
        cachedTimedLines = parseLyrics(rawLyrics).takeWhile { it.timeMs != Long.MAX_VALUE }
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
                delay(if (anchorPlaying) PLAYING_POLL_INTERVAL_MS else PAUSED_POLL_INTERVAL_MS)
            }
        }
    }

    private fun applyForPosition(snapshot: Snapshot, positionMs: Long) {
        val track = snapshot.track ?: return
        val routeDecision = bluetoothMediaRouteDecision(audioManager, mediaRouter)
        if (routeDecision != lastRouteDecision) {
            lastRouteDecision = routeDecision
            AppLogger.i(TAG, "Bluetooth media route ${routeDecision.toDiagnosticString()}")
        }
        if (!routeDecision.active) {
            restoreOriginalMetadata()
            return
        }
        val lyricLine = bluetoothLyricLine(cachedTimedLines, positionMs)
        if (lyricLine == null) {
            restoreOriginalMetadata()
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
            AppLogger.w(TAG, "media session does not allow Bluetooth lyrics metadata replacement")
            return
        }

        var currentItem = controller.currentMediaItem ?: return
        if (!currentItem.mediaId.endsWith(":${track.id}")) return

        val previousApplied = lastApplied
        if (
            previousApplied != null &&
            previousApplied.mediaId != currentItem.mediaId &&
            !restoreOriginalMetadata()
        ) {
            return
        }
        currentItem = controller.currentMediaItem ?: return
        if (!currentItem.mediaId.endsWith(":${track.id}")) return

        val currentIndex = controller.currentMediaItemIndex
        if (currentIndex < 0) return
        val appliedForCurrentItem = lastApplied?.takeIf { it.mediaId == currentItem.mediaId }
        val desired = AppliedMetadata(
            mediaId = currentItem.mediaId,
            originalTitle = if (appliedForCurrentItem != null) {
                appliedForCurrentItem.originalTitle
            } else {
                currentItem.mediaMetadata.title?.toString()
            },
            originalArtist = if (appliedForCurrentItem != null) {
                appliedForCurrentItem.originalArtist
            } else {
                currentItem.mediaMetadata.artist?.toString()
            },
            title = display.title,
            artist = display.artist,
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
                AppLogger.w(TAG, "failed to publish Bluetooth lyrics trackId=${track.id}", throwable)
            }
    }

    /** Returns true when there is no decorated item left to restore. */
    private fun restoreOriginalMetadata(): Boolean {
        val applied = lastApplied ?: return true
        val controller = mediaController ?: return false
        if (!controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false

        val target = findMediaItem(controller, applied.mediaId) ?: run {
            lastApplied = null
            return true
        }
        if (
            target.item.mediaMetadata.title?.toString() == applied.originalTitle &&
            target.item.mediaMetadata.artist?.toString() == applied.originalArtist
        ) {
            lastApplied = null
            return true
        }

        val updatedItem = target.item.buildUpon()
            .setMediaMetadata(
                target.item.mediaMetadata.buildUpon()
                    .setTitle(applied.originalTitle)
                    .setArtist(applied.originalArtist)
                    .build(),
            )
            .build()
        return runCatching { controller.replaceMediaItem(target.index, updatedItem) }
            .fold(
                onSuccess = {
                    lastApplied = null
                    true
                },
                onFailure = { throwable ->
                    AppLogger.w(TAG, "failed to restore original media metadata mediaId=${applied.mediaId}", throwable)
                    false
                },
            )
    }

    private fun findMediaItem(controller: MediaController, mediaId: String): IndexedMediaItem? {
        if (controller.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) {
            for (index in 0 until controller.mediaItemCount) {
                val item = controller.getMediaItemAt(index)
                if (item.mediaId == mediaId) return IndexedMediaItem(index, item)
            }
            return null
        }
        val currentItem = controller.currentMediaItem ?: return null
        val currentIndex = controller.currentMediaItemIndex
        return if (currentIndex >= 0 && currentItem.mediaId == mediaId) {
            IndexedMediaItem(currentIndex, currentItem)
        } else {
            null
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
                        AppLogger.w(TAG, "unable to connect Bluetooth lyrics media controller", throwable)
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
        val originalTitle: String?,
        val originalArtist: String?,
        val title: String,
        val artist: String,
    )

    private data class IndexedMediaItem(
        val index: Int,
        val item: MediaItem,
    )

    private companion object {
        const val TAG = "BluetoothLyrics"
        const val PLAYING_POLL_INTERVAL_MS = 250L
        const val PAUSED_POLL_INTERVAL_MS = 1_000L
    }
}

internal data class BluetoothLyricsDisplay(
    val title: String,
    val artist: String,
)

internal fun bluetoothLyricLine(rawLyrics: String?, positionMs: Long): String? =
    bluetoothLyricLine(
        timedLines = parseLyrics(rawLyrics).takeWhile { it.timeMs != Long.MAX_VALUE },
        positionMs = positionMs,
    )

private fun bluetoothLyricLine(timedLines: List<LyricLine>, positionMs: Long): String? {
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

internal data class BluetoothMediaRouteDecision(
    val active: Boolean,
    val routedDeviceTypes: List<Int>,
    val routedDeviceQueryAvailable: Boolean,
    val mediaRouterBluetooth: Boolean,
    val legacyA2dpOn: Boolean,
    val legacyScoOn: Boolean,
    val connectedBluetoothOutputTypes: List<Int>,
) {
    fun toDiagnosticString(): String =
        "active=$active routedTypes=$routedDeviceTypes routedQueryAvailable=$routedDeviceQueryAvailable " +
            "mediaRouterBluetooth=$mediaRouterBluetooth a2dpOn=$legacyA2dpOn scoOn=$legacyScoOn " +
            "connectedBluetoothTypes=$connectedBluetoothOutputTypes"
}

internal fun resolveBluetoothMediaRouteActive(
    routedBluetooth: Boolean,
    routedDeviceQueryAvailable: Boolean,
    mediaRouterBluetooth: Boolean,
    legacyBluetoothRoute: Boolean,
    connectedBluetoothOutput: Boolean,
): Boolean =
    routedBluetooth ||
        mediaRouterBluetooth ||
        legacyBluetoothRoute ||
        (!routedDeviceQueryAvailable && connectedBluetoothOutput)

@Suppress("DEPRECATION")
internal fun isBluetoothMediaOutputActive(
    audioManager: AudioManager?,
    mediaRouter: MediaRouter?,
): Boolean = bluetoothMediaRouteDecision(audioManager, mediaRouter).active

@Suppress("DEPRECATION")
internal fun bluetoothMediaRouteDecision(
    audioManager: AudioManager?,
    mediaRouter: MediaRouter?,
): BluetoothMediaRouteDecision {
    val routedDeviceResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && audioManager != null) {
        runCatching {
            audioManager.getAudioDevicesForAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).map(AudioDeviceInfo::getType)
        }
    } else {
        null
    }
    val routedDeviceTypes = routedDeviceResult?.getOrNull().orEmpty()
    val routedDeviceQueryAvailable = routedDeviceResult?.isSuccess == true && routedDeviceTypes.isNotEmpty()
    val routedBluetooth = routedDeviceTypes.any(::isBluetoothAudioDeviceType)

    val selectedRoute = runCatching {
        mediaRouter?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    }.getOrNull()
    val mediaRouterBluetooth = selectedRoute?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH

    val legacyA2dpOn = runCatching { audioManager?.isBluetoothA2dpOn == true }.getOrDefault(false)
    val legacyScoOn = runCatching { audioManager?.isBluetoothScoOn == true }.getOrDefault(false)
    val connectedBluetoothOutputTypes = runCatching {
        audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .map(AudioDeviceInfo::getType)
            .filter(::isBluetoothAudioDeviceType)
            .distinct()
    }.getOrElse { emptyList() }

    val active = resolveBluetoothMediaRouteActive(
        routedBluetooth = routedBluetooth,
        routedDeviceQueryAvailable = routedDeviceQueryAvailable,
        mediaRouterBluetooth = mediaRouterBluetooth,
        legacyBluetoothRoute = legacyA2dpOn || legacyScoOn,
        connectedBluetoothOutput = connectedBluetoothOutputTypes.isNotEmpty(),
    )
    return BluetoothMediaRouteDecision(
        active = active,
        routedDeviceTypes = routedDeviceTypes,
        routedDeviceQueryAvailable = routedDeviceQueryAvailable,
        mediaRouterBluetooth = mediaRouterBluetooth,
        legacyA2dpOn = legacyA2dpOn,
        legacyScoOn = legacyScoOn,
        connectedBluetoothOutputTypes = connectedBluetoothOutputTypes,
    )
}

private fun isBluetoothAudioDeviceType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    -> true
    else -> Build.VERSION.SDK_INT >= 37 && type == AudioDeviceInfo.TYPE_BLE_HEARING_AID
}
