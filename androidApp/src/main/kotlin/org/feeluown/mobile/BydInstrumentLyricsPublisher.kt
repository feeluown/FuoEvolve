package org.feeluown.mobile

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    instrumentClass.methods.any { method ->
        method.name == "sendThreeLineLyrics" &&
            method.parameterTypes.contentEquals(
                arrayOf(String::class.java, String::class.java, String::class.java),
            )
    } && instrumentClass.methods.any { method -> method.name == "getInstance" }
}.getOrDefault(false)

internal class BydInstrumentLyricsPublisher(
    context: Context,
    private val playbackSession: PlaybackSession,
    private val enabled: Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var collectJob: Job? = null
    private var bridge: BydInstrumentLyricsBridge? = null
    private var lastTrackKey: String? = null
    private var lastWindow: InstrumentLyricsWindow? = null
    private var hasPublishedLyrics = false

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            combine(
                playbackSession.state,
                enabled.distinctUntilChanged(),
            ) { state, isEnabled ->
                Snapshot(
                    enabled = isEnabled,
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
        clearPublishedLyrics()
        bridge = null
    }

    private fun publish(snapshot: Snapshot) {
        val track = snapshot.track
        if (
            !snapshot.enabled ||
            track == null ||
            snapshot.status == PlaybackSessionStatus.Idle ||
            snapshot.status == PlaybackSessionStatus.Error ||
            snapshot.status == PlaybackSessionStatus.Ended
        ) {
            clearPublishedLyrics()
            return
        }

        val payload = buildStatusBarLyricsPayload(
            rawLyrics = snapshot.lyrics,
            durationMs = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs,
        )
        val window = buildInstrumentLyricsWindow(payload, snapshot.positionMs)
        if (window == null) {
            clearPublishedLyrics()
            return
        }

        val trackKey = listOf(track.source, track.id, track.title, track.artists).joinToString("\u0000")
        if (trackKey == lastTrackKey && window == lastWindow) return

        val activeBridge = ensureBridge() ?: return
        if (activeBridge.sendThreeLineLyrics(window.previous, window.current, window.next)) {
            lastTrackKey = trackKey
            lastWindow = window
            hasPublishedLyrics = true
        }
    }

    private fun ensureBridge(): BydInstrumentLyricsBridge? {
        bridge?.let { return it }
        return BydInstrumentLyricsBridge.create(appContext)
            .onFailure { throwable ->
                Log.w(TAG, "Unable to initialize BYD instrument lyrics; playback is unaffected", throwable)
            }
            .getOrNull()
            ?.also { bridge = it }
    }

    private fun clearPublishedLyrics() {
        if (hasPublishedLyrics) {
            bridge?.sendThreeLineLyrics("", "", "")
        }
        lastTrackKey = null
        lastWindow = null
        hasPublishedLyrics = false
    }

    private data class Snapshot(
        val enabled: Boolean,
        val status: PlaybackSessionStatus,
        val track: TrackRef?,
        val positionMs: Long,
        val durationMs: Long,
        val lyrics: String?,
    )

    private companion object {
        const val TAG = "BydInstrumentLyrics"
    }
}

private class BydInstrumentLyricsBridge(
    private val device: Any,
    private val sendThreeLineLyricsMethod: Method,
) {
    fun sendThreeLineLyrics(previous: String, current: String, next: String): Boolean = runCatching {
        val result = sendThreeLineLyricsMethod.invoke(device, previous, current, next)
        val success = (result as? Number)?.toInt()?.let { it == 0 } ?: true
        if (!success) {
            Log.w(TAG, "BYD sendThreeLineLyrics returned $result")
        }
        success
    }.onFailure { throwable ->
        Log.w(TAG, "Failed to publish lyrics to BYD instrument", unwrapInvocationException(throwable))
    }.getOrDefault(false)

    companion object {
        private const val TAG = "BydInstrumentLyrics"

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

            val sendMethod = instrumentClass.getMethod(
                "sendThreeLineLyrics",
                String::class.java,
                String::class.java,
                String::class.java,
            )
            BydInstrumentLyricsBridge(device, sendMethod)
        }.recoverCatching { throwable ->
            throw unwrapInvocationException(throwable)
        }
    }
}

private fun unwrapInvocationException(throwable: Throwable): Throwable =
    (throwable as? InvocationTargetException)?.targetException ?: throwable
