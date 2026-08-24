package org.feeluown.mobile

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

internal const val BYD_INSTRUMENT_DEVICE_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"

internal fun isBydInstrumentLyricsAvailable(): Boolean = runCatching {
    val instrumentClass = Class.forName(BYD_INSTRUMENT_DEVICE_CLASS)
    val hasInstanceFactory = instrumentClass.methods.any { method -> method.name == "getInstance" }
    val hasThreeLineLyrics = instrumentClass.methods.any(::isThreeLineLyricsMethod)
    val hasMusicName = instrumentClass.methods.any(::isMusicNameMethod)
    hasInstanceFactory && (hasThreeLineLyrics || hasMusicName)
}.getOrDefault(false)

private fun isThreeLineLyricsMethod(method: Method): Boolean =
    method.name == "sendThreeLineLyrics" &&
        method.parameterTypes.contentEquals(
            arrayOf(String::class.java, String::class.java, String::class.java),
        )

private fun isMusicNameMethod(method: Method): Boolean =
    method.name == "sendMusicName" &&
        method.parameterTypes.contentEquals(arrayOf(String::class.java))

internal class BydInstrumentLyricsPublisher(
    context: Context,
    private val playbackSession: PlaybackSession,
    private val enabled: Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var collectJob: Job? = null
    private var positionSyncJob: Job? = null
    private var bridge: BydInstrumentLyricsBridge? = null
    private var bridgeInitializationAttempted = false
    private var publicationFailed = false
    private var lastTrackKey: String? = null
    private var lastWindow: InstrumentLyricsWindow? = null
    private var lastStatus: PlaybackSessionStatus? = null
    private var hasPublishedLyrics = false
    private var cachedLyricsKey: String? = null
    private var cachedPayload: StatusBarLyricsPayload = StatusBarLyricsPayload.Empty
    private var anchorPositionMs: Long = 0L
    private var anchorRealtimeMs: Long = 0L
    private var anchorPlaying: Boolean = false
    private var latestSnapshot: Snapshot? = null

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            combine(
                playbackSession.state,
                enabled.distinctUntilChanged(),
                BydInstrumentPermissionState.granted,
            ) { state, isEnabled, permissionGranted ->
                Snapshot(
                    enabled = isEnabled,
                    permissionGranted = permissionGranted,
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
        bridge = null
    }

    private fun publish(snapshot: Snapshot) {
        latestSnapshot = snapshot
        updatePositionAnchor(snapshot)

        val track = snapshot.track
        if (
            !snapshot.enabled ||
            track == null ||
            snapshot.status == PlaybackSessionStatus.Idle ||
            snapshot.status == PlaybackSessionStatus.Error ||
            snapshot.status == PlaybackSessionStatus.Ended
        ) {
            deactivate()
            return
        }

        if (!snapshot.permissionGranted) {
            suspendForPermission()
            return
        }

        val canSync = publishAtPosition(snapshot, snapshot.positionMs)
        if (canSync && snapshot.status == PlaybackSessionStatus.Playing) {
            ensurePositionSyncLoop()
        } else {
            stopPositionSyncLoop()
        }
    }

    private fun publishAtPosition(snapshot: Snapshot, positionMs: Long): Boolean {
        if (publicationFailed) return false
        val track = snapshot.track ?: return false
        val payload = payloadFor(snapshot, track)
        val window = buildInstrumentLyricsWindow(payload, positionMs)
        if (window == null) {
            clearPublishedLyrics()
            return false
        }

        val trackKey = listOf(track.source, track.id, track.title, track.artists).joinToString("\u0000")
        if (trackKey == lastTrackKey && window == lastWindow && snapshot.status == lastStatus) return true

        val activeBridge = ensureBridge() ?: return false
        if (!activeBridge.publish(window, snapshot.status)) {
            publicationFailed = true
            return false
        }
        lastTrackKey = trackKey
        lastWindow = window
        lastStatus = snapshot.status
        hasPublishedLyrics = true
        return true
    }

    private fun payloadFor(snapshot: Snapshot, track: TrackRef): StatusBarLyricsPayload {
        val durationMs = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs
        val lyricsKey = listOf(track.source, track.id, snapshot.lyrics.orEmpty(), durationMs).joinToString("\u0000")
        if (lyricsKey != cachedLyricsKey) {
            cachedLyricsKey = lyricsKey
            cachedPayload = buildStatusBarLyricsPayload(
                rawLyrics = snapshot.lyrics,
                durationMs = durationMs,
            )
        }
        return cachedPayload
    }

    private fun updatePositionAnchor(snapshot: Snapshot, realtimeMs: Long = SystemClock.elapsedRealtime()) {
        anchorPositionMs = snapshot.positionMs.coerceAtLeast(0L)
        anchorRealtimeMs = realtimeMs
        anchorPlaying = snapshot.status == PlaybackSessionStatus.Playing
    }

    private fun estimatedPositionMs(realtimeMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!anchorPlaying) return anchorPositionMs
        return (anchorPositionMs + (realtimeMs - anchorRealtimeMs).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun ensurePositionSyncLoop() {
        if (positionSyncJob?.isActive == true) return
        positionSyncJob = scope.launch {
            while (true) {
                val snapshot = latestSnapshot ?: break
                if (
                    !snapshot.enabled ||
                    !snapshot.permissionGranted ||
                    snapshot.status != PlaybackSessionStatus.Playing ||
                    snapshot.track == null
                ) {
                    break
                }
                if (!publishAtPosition(snapshot, estimatedPositionMs())) break
                bridge?.refreshSessionLease(snapshot.status)
                delay(POSITION_SYNC_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionSyncLoop() {
        positionSyncJob?.cancel()
        positionSyncJob = null
    }

    private fun ensureBridge(): BydInstrumentLyricsBridge? {
        bridge?.let { return it }
        if (bridgeInitializationAttempted) return null
        bridgeInitializationAttempted = true
        return BydInstrumentLyricsBridge.create(appContext)
            .onFailure { throwable ->
                Log.w(TAG, "Unable to initialize BYD instrument lyrics; playback is unaffected", throwable)
            }
            .getOrNull()
            ?.also { createdBridge ->
                Log.i(TAG, "Using BYD instrument lyrics transport=${createdBridge.transportName}")
                bridge = createdBridge
            }
    }

    private fun suspendForPermission() {
        stopPositionSyncLoop()
        bridge = null
        bridgeInitializationAttempted = false
        publicationFailed = false
        lastTrackKey = null
        lastWindow = null
        lastStatus = null
        hasPublishedLyrics = false
    }

    private fun deactivate() {
        stopPositionSyncLoop()
        clearPublishedLyrics()
        latestSnapshot = null
        anchorPositionMs = 0L
        anchorRealtimeMs = 0L
        anchorPlaying = false
        cachedLyricsKey = null
        cachedPayload = StatusBarLyricsPayload.Empty
        bridgeInitializationAttempted = false
        publicationFailed = false
    }

    private fun clearPublishedLyrics() {
        if (hasPublishedLyrics) {
            bridge?.clear()
        }
        lastTrackKey = null
        lastWindow = null
        lastStatus = null
        hasPublishedLyrics = false
    }

    private data class Snapshot(
        val enabled: Boolean,
        val permissionGranted: Boolean,
        val status: PlaybackSessionStatus,
        val track: TrackRef?,
        val positionMs: Long,
        val durationMs: Long,
        val lyrics: String?,
    )

    private companion object {
        const val TAG = "BydInstrumentLyrics"
        const val POSITION_SYNC_INTERVAL_MS = 100L
    }
}

private class BydInstrumentLyricsBridge(
    private val device: Any,
    private val threeLineLyricsMethod: Method?,
    private val musicNameMethod: Method?,
    private val musicStateMethod: Method?,
    private val musicSourceMethod: Method?,
    private val musicPlayValue: Int?,
    private val musicPauseValue: Int?,
    private val musicStopValue: Int?,
    private val musicSourceOthersValue: Int?,
) {
    val transportName: String
        get() = if (useMusicNameTransport || threeLineLyricsMethod == null) {
            "DiLink-music-name"
        } else {
            "three-line"
        }

    private var useMusicNameTransport = threeLineLyricsMethod == null
    private var lastMusicStateValue: Int? = null
    private var lastMusicSessionRefreshRealtimeMs = 0L

    fun publish(window: InstrumentLyricsWindow, status: PlaybackSessionStatus): Boolean {
        if (!useMusicNameTransport) {
            val method = threeLineLyricsMethod
            if (method != null && invokeRequired(method, window.previous, window.current, window.next)) {
                return true
            }
            if (musicNameMethod == null) return false
            useMusicNameTransport = true
            Log.w(TAG, "Falling back to BYD sendMusicName after three-line lyrics publishing failed")
        }

        val nameMethod = musicNameMethod ?: return false
        refreshMusicSession(status)
        return invokeRequired(nameMethod, window.current)
    }

    fun refreshSessionLease(
        status: PlaybackSessionStatus,
        realtimeMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (!useMusicNameTransport || status != PlaybackSessionStatus.Playing) return
        if (
            lastMusicSessionRefreshRealtimeMs != 0L &&
            realtimeMs - lastMusicSessionRefreshRealtimeMs < MUSIC_SESSION_LEASE_INTERVAL_MS
        ) {
            return
        }
        refreshMusicSession(status, realtimeMs)
    }

    fun clear() {
        if (!useMusicNameTransport) {
            val method = threeLineLyricsMethod
            if (method != null && invokeOptional(method, "", "", "")) return
        }
        musicNameMethod?.let { invokeOptional(it, "") }
        publishMusicStateValueIfAvailable(musicStopValue, force = true)
        lastMusicStateValue = null
        lastMusicSessionRefreshRealtimeMs = 0L
    }

    private fun refreshMusicSession(
        status: PlaybackSessionStatus,
        realtimeMs: Long = SystemClock.elapsedRealtime(),
    ) {
        // Reassert source/state on actual lyric/status changes, then maintain the same ownership
        // with a low-frequency lease while playing. The lease intentionally never resends
        // sendMusicName, so long scrolling lyrics are not restarted.
        lastMusicSessionRefreshRealtimeMs = realtimeMs
        publishMusicSourceIfAvailable()
        publishMusicStateIfAvailable(status, force = true)
    }

    private fun publishMusicSourceIfAvailable() {
        val method = musicSourceMethod ?: return
        val value = musicSourceOthersValue ?: return
        invokeOptional(method, value)
    }

    private fun publishMusicStateIfAvailable(status: PlaybackSessionStatus, force: Boolean = false) {
        val value = when (status) {
            PlaybackSessionStatus.Playing -> musicPlayValue
            PlaybackSessionStatus.Paused -> musicPauseValue
            PlaybackSessionStatus.Ended,
            PlaybackSessionStatus.Idle,
            PlaybackSessionStatus.Error,
            -> musicStopValue
            PlaybackSessionStatus.Loading -> null
        }
        publishMusicStateValueIfAvailable(value, force)
    }

    private fun publishMusicStateValueIfAvailable(value: Int?, force: Boolean = false) {
        val method = musicStateMethod ?: return
        val stateValue = value ?: return
        if (!force && lastMusicStateValue == stateValue) return
        if (invokeOptional(method, stateValue)) lastMusicStateValue = stateValue
    }

    private fun invokeRequired(method: Method, vararg args: Any): Boolean = runCatching {
        val result = method.invoke(device, *args)
        commandSucceeded(result)
    }.onFailure { throwable ->
        Log.w(TAG, "Failed to publish lyrics through ${method.name}", unwrapInvocationException(throwable))
    }.getOrDefault(false)

    private fun invokeOptional(method: Method, vararg args: Any): Boolean = runCatching {
        val result = method.invoke(device, *args)
        commandSucceeded(result)
    }.onFailure { throwable ->
        Log.d(TAG, "Optional BYD instrument call ${method.name} failed", unwrapInvocationException(throwable))
    }.getOrDefault(false)

    private fun commandSucceeded(result: Any?): Boolean {
        val success = (result as? Number)?.toInt()?.let { it == 0 } ?: true
        if (!success) Log.w(TAG, "BYD instrument command returned $result")
        return success
    }

    companion object {
        private const val TAG = "BydInstrumentLyrics"
        private const val MUSIC_SESSION_LEASE_INTERVAL_MS = 5_000L

        fun create(context: Context): Result<BydInstrumentLyricsBridge> = runCatching {
            val instrumentClass = Class.forName(BYD_INSTRUMENT_DEVICE_CLASS)
            val permissionContext = BydPermissionContext(context.applicationContext)
            val getInstanceMethod = instrumentClass.methods.firstOrNull { method ->
                method.name == "getInstance" &&
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java))
            } ?: instrumentClass.methods.firstOrNull { method ->
                method.name == "getInstance" && method.parameterCount == 0
            } ?: error("BYDAutoInstrumentDevice.getInstance is unavailable")

            val device = if (getInstanceMethod.parameterCount == 0) {
                getInstanceMethod.invoke(null)
            } else {
                getInstanceMethod.invoke(null, permissionContext)
            } ?: error("BYDAutoInstrumentDevice.getInstance returned null")

            val threeLineMethod = instrumentClass.methods.firstOrNull(::isThreeLineLyricsMethod)
            val musicNameMethod = instrumentClass.methods.firstOrNull(::isMusicNameMethod)
            if (threeLineMethod == null && musicNameMethod == null) {
                error("No supported BYD instrument lyrics transport is available")
            }

            BydInstrumentLyricsBridge(
                device = device,
                threeLineLyricsMethod = threeLineMethod,
                musicNameMethod = musicNameMethod,
                musicStateMethod = instrumentClass.optionalIntMethod("sendMusicState"),
                musicSourceMethod = instrumentClass.optionalIntMethod("sendMusicSource"),
                musicPlayValue = instrumentClass.optionalStaticInt("MUSIC_PLAY"),
                musicPauseValue = instrumentClass.optionalStaticInt("MUSIC_PAUSE"),
                musicStopValue = instrumentClass.optionalStaticInt("MUSIC_STOP"),
                musicSourceOthersValue = instrumentClass.optionalStaticInt("MUSIC_SOURCE_OTHERS"),
            )
        }.recoverCatching { throwable ->
            throw unwrapInvocationException(throwable)
        }
    }
}

private fun Class<*>.optionalIntMethod(name: String): Method? = methods.firstOrNull { method ->
    method.name == name && method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
}

private fun Class<*>.optionalStaticInt(name: String): Int? = runCatching {
    getField(name).getInt(null)
}.getOrNull()

private fun unwrapInvocationException(throwable: Throwable): Throwable =
    (throwable as? InvocationTargetException)?.targetException ?: throwable
