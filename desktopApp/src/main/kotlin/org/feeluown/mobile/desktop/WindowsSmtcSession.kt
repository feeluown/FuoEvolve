package org.feeluown.mobile.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

internal class WindowsSmtcSession(
    private val playbackSession: PlaybackSession,
    window: Window,
    private val native: WindowsSmtcNative = loadWindowsSmtcNative(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val callback = object : WindowsSmtcNative.EventCallback {
        override fun invoke(action: Int, value: Long) {
            if (closed.get()) return
            scope.launch {
                when (action) {
                    WindowsSmtcNative.ACTION_PLAY -> playbackSession.play()
                    WindowsSmtcNative.ACTION_PAUSE -> playbackSession.pause()
                    WindowsSmtcNative.ACTION_STOP -> playbackSession.stop()
                    WindowsSmtcNative.ACTION_NEXT -> playbackSession.next()
                    WindowsSmtcNative.ACTION_PREVIOUS -> playbackSession.previous()
                    WindowsSmtcNative.ACTION_SEEK_TO -> playbackSession.seekTo(value.coerceAtLeast(0L))
                }
            }
        }
    }
    private val handle: Pointer

    init {
        val hwnd = Pointer.nativeValue(Native.getComponentPointer(window))
        val error = Memory(ERROR_BUFFER_BYTES.toLong()).apply { clear() }
        handle = native.fuo_smtc_create(
            hwnd = hwnd,
            callback = callback,
            errorBuffer = error,
            errorCapacity = ERROR_BUFFER_BYTES.toLong(),
        ) ?: error("Failed to initialize Windows SMTC: ${error.getString(0, Charsets.UTF_8.name())}")

        val initial = windowsSmtcProjection(playbackSession.state.value)
        publishProjection(initial, previous = null)
        scope.launch {
            var previous = initial
            playbackSession.state.collect { state ->
                val projected = windowsSmtcProjection(state)
                publishProjection(projected, previous)
                previous = projected
            }
        }
    }

    private fun publishProjection(
        current: WindowsSmtcProjection,
        previous: WindowsSmtcProjection?,
    ) {
        native.fuo_smtc_update_state(
            bridge = handle,
            status = current.status,
            positionMs = current.positionMs,
            durationMs = current.durationMs,
            hasTrack = current.hasTrack.asNativeFlag(),
            canPlay = current.canPlay.asNativeFlag(),
            canPause = current.canPause.asNativeFlag(),
            canNext = current.canNext.asNativeFlag(),
            canPrevious = current.canPrevious.asNativeFlag(),
        )
        if (current.metadata != previous?.metadata) {
            val metadata = current.metadata
            if (metadata == null) {
                native.fuo_smtc_clear_metadata(handle)
            } else {
                native.fuo_smtc_update_metadata(
                    bridge = handle,
                    trackId = metadata.trackId,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                )
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        native.fuo_smtc_destroy(handle)
    }
}

internal data class WindowsSmtcProjection(
    val status: Int,
    val positionMs: Long,
    val durationMs: Long,
    val hasTrack: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val metadata: WindowsSmtcMetadata?,
)

internal data class WindowsSmtcMetadata(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
)

internal fun windowsSmtcProjection(state: PlaybackSessionState): WindowsSmtcProjection {
    val track = state.currentTrack
    val durationMs = state.durationMs.takeIf { it > 0L }
        ?: track?.durationMs?.takeIf { it > 0L }
        ?: 0L
    return WindowsSmtcProjection(
        status = when (state.status) {
            PlaybackSessionStatus.Playing -> WindowsSmtcNative.STATUS_PLAYING
            PlaybackSessionStatus.Paused -> WindowsSmtcNative.STATUS_PAUSED
            PlaybackSessionStatus.Loading -> WindowsSmtcNative.STATUS_CHANGING
            PlaybackSessionStatus.Idle,
            PlaybackSessionStatus.Error,
            PlaybackSessionStatus.Ended -> WindowsSmtcNative.STATUS_STOPPED
        },
        positionMs = state.positionMs.coerceAtLeast(0L),
        durationMs = durationMs,
        hasTrack = track != null,
        canPlay = track != null || state.queueTrackIds.isNotEmpty(),
        canPause = track != null,
        canNext = state.queueIndex >= 0 && state.queueIndex < state.queueTrackIds.lastIndex,
        canPrevious = state.queueIndex > 0,
        metadata = track?.toWindowsSmtcMetadata(),
    )
}

private fun TrackRef.toWindowsSmtcMetadata() = WindowsSmtcMetadata(
    trackId = id,
    title = title,
    artist = artists,
    album = album,
)

private fun Boolean.asNativeFlag(): Int = if (this) 1 else 0

internal interface WindowsSmtcNative : Library {
    interface EventCallback : Callback {
        fun invoke(action: Int, value: Long)
    }

    fun fuo_smtc_create(
        hwnd: Long,
        callback: EventCallback,
        errorBuffer: Pointer,
        errorCapacity: Long,
    ): Pointer?

    fun fuo_smtc_update_state(
        bridge: Pointer,
        status: Int,
        positionMs: Long,
        durationMs: Long,
        hasTrack: Int,
        canPlay: Int,
        canPause: Int,
        canNext: Int,
        canPrevious: Int,
    )

    fun fuo_smtc_update_metadata(
        bridge: Pointer,
        trackId: String,
        title: String,
        artist: String,
        album: String,
    )

    fun fuo_smtc_clear_metadata(bridge: Pointer)
    fun fuo_smtc_destroy(bridge: Pointer)

    companion object {
        const val ACTION_PLAY = 1
        const val ACTION_PAUSE = 2
        const val ACTION_STOP = 3
        const val ACTION_NEXT = 4
        const val ACTION_PREVIOUS = 5
        const val ACTION_SEEK_TO = 6

        const val STATUS_STOPPED = 0
        const val STATUS_PLAYING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_CHANGING = 3
    }
}

private fun loadWindowsSmtcNative(): WindowsSmtcNative {
    val options = mapOf<String, Any>(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name())
    val explicit = System.getenv("FUOEVOLVE_SMTC_BRIDGE_PATH")
        ?.takeIf(String::isNotBlank)
        ?.let { Paths.get(it) }
    val library = sequenceOf(
        explicit,
        developmentBridgePath("desktopApp/native/windows-smtc"),
        developmentBridgePath("native/windows-smtc"),
    ).filterNotNull().firstOrNull { Files.isRegularFile(it) }

    return if (library != null) {
        Native.load(library.toAbsolutePath().toString(), WindowsSmtcNative::class.java, options)
    } else {
        Native.load(WINDOWS_SMTC_LIBRARY_NAME, WindowsSmtcNative::class.java, options)
    }
}

private fun developmentBridgePath(relativeRoot: String): Path =
    Paths.get(System.getProperty("user.dir"), relativeRoot, "target", "release", WINDOWS_SMTC_DLL_NAME)

private const val ERROR_BUFFER_BYTES = 2048
private const val WINDOWS_SMTC_LIBRARY_NAME = "fuoevolve_smtc_bridge"
private const val WINDOWS_SMTC_DLL_NAME = "fuoevolve_smtc_bridge.dll"
