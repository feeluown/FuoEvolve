package org.feeluown.mobile.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
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

internal class MacNowPlayingSession(
    private val playbackSession: PlaybackSession,
    private val native: MacNowPlayingNative = loadMacNowPlayingNative(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val callback = object : MacNowPlayingNative.EventCallback {
        override fun invoke(action: Int, value: Long) {
            if (closed.get()) return
            scope.launch {
                when (action) {
                    MacNowPlayingNative.ACTION_PLAY -> playbackSession.play()
                    MacNowPlayingNative.ACTION_PAUSE -> playbackSession.pause()
                    MacNowPlayingNative.ACTION_STOP -> playbackSession.stop()
                    MacNowPlayingNative.ACTION_NEXT -> playbackSession.next()
                    MacNowPlayingNative.ACTION_PREVIOUS -> playbackSession.previous()
                    MacNowPlayingNative.ACTION_SEEK_TO -> playbackSession.seekTo(value.coerceAtLeast(0L))
                    MacNowPlayingNative.ACTION_TOGGLE -> playbackSession.toggle()
                }
            }
        }
    }
    private val handle: Pointer

    init {
        val error = Memory(ERROR_BUFFER_BYTES.toLong()).apply { clear() }
        handle = native.fuo_now_playing_create(
            callback = callback,
            errorBuffer = error,
            errorCapacity = ERROR_BUFFER_BYTES.toLong(),
        ) ?: error("Failed to initialize macOS Now Playing: ${error.getString(0, Charsets.UTF_8.name())}")

        publish(macNowPlayingProjection(playbackSession.state.value))
        scope.launch {
            playbackSession.state.collect { state ->
                publish(macNowPlayingProjection(state))
            }
        }
    }

    private fun publish(projection: MacNowPlayingProjection) {
        val metadata = projection.metadata
        native.fuo_now_playing_update(
            bridge = handle,
            status = projection.status,
            positionMs = projection.positionMs,
            durationMs = projection.durationMs,
            hasTrack = projection.hasTrack.asNativeFlag(),
            canPlay = projection.canPlay.asNativeFlag(),
            canPause = projection.canPause.asNativeFlag(),
            canNext = projection.canNext.asNativeFlag(),
            canPrevious = projection.canPrevious.asNativeFlag(),
            queueIndex = projection.queueIndex.toLong(),
            queueCount = projection.queueCount.toLong(),
            trackId = metadata?.trackId.orEmpty(),
            title = metadata?.title.orEmpty(),
            artist = metadata?.artist.orEmpty(),
            album = metadata?.album.orEmpty(),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        native.fuo_now_playing_clear(handle)
        native.fuo_now_playing_destroy(handle)
    }
}

internal data class MacNowPlayingProjection(
    val status: Int,
    val positionMs: Long,
    val durationMs: Long,
    val hasTrack: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val queueIndex: Int,
    val queueCount: Int,
    val metadata: MacNowPlayingMetadata?,
)

internal data class MacNowPlayingMetadata(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
)

internal fun macNowPlayingProjection(state: PlaybackSessionState): MacNowPlayingProjection {
    val track = state.currentTrack
    val durationMs = state.durationMs.takeIf { it > 0L }
        ?: track?.durationMs?.takeIf { it > 0L }
        ?: 0L
    return MacNowPlayingProjection(
        status = when (state.status) {
            PlaybackSessionStatus.Playing -> MacNowPlayingNative.STATUS_PLAYING
            PlaybackSessionStatus.Paused -> MacNowPlayingNative.STATUS_PAUSED
            PlaybackSessionStatus.Loading -> MacNowPlayingNative.STATUS_LOADING
            PlaybackSessionStatus.Idle,
            PlaybackSessionStatus.Error,
            PlaybackSessionStatus.Ended -> MacNowPlayingNative.STATUS_STOPPED
        },
        positionMs = state.positionMs.coerceAtLeast(0L),
        durationMs = durationMs,
        hasTrack = track != null,
        canPlay = track != null || state.queueTrackIds.isNotEmpty(),
        canPause = track != null,
        canNext = state.queueIndex >= 0 && state.queueIndex < state.queueTrackIds.lastIndex,
        canPrevious = state.queueIndex > 0,
        queueIndex = state.queueIndex,
        queueCount = state.queueTrackIds.size,
        metadata = track?.toMacNowPlayingMetadata(),
    )
}

private fun TrackRef.toMacNowPlayingMetadata() = MacNowPlayingMetadata(
    trackId = id,
    title = title,
    artist = artists,
    album = album,
)

private fun Boolean.asNativeFlag(): Int = if (this) 1 else 0

internal interface MacNowPlayingNative : Library {
    interface EventCallback : Callback {
        fun invoke(action: Int, value: Long)
    }

    fun fuo_now_playing_create(
        callback: EventCallback,
        errorBuffer: Pointer,
        errorCapacity: Long,
    ): Pointer?

    fun fuo_now_playing_update(
        bridge: Pointer,
        status: Int,
        positionMs: Long,
        durationMs: Long,
        hasTrack: Int,
        canPlay: Int,
        canPause: Int,
        canNext: Int,
        canPrevious: Int,
        queueIndex: Long,
        queueCount: Long,
        trackId: String,
        title: String,
        artist: String,
        album: String,
    )

    fun fuo_now_playing_clear(bridge: Pointer)
    fun fuo_now_playing_destroy(bridge: Pointer)

    companion object {
        const val ACTION_PLAY = 1
        const val ACTION_PAUSE = 2
        const val ACTION_STOP = 3
        const val ACTION_NEXT = 4
        const val ACTION_PREVIOUS = 5
        const val ACTION_SEEK_TO = 6
        const val ACTION_TOGGLE = 7

        const val STATUS_STOPPED = 0
        const val STATUS_PLAYING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_LOADING = 3
    }
}

private fun loadMacNowPlayingNative(): MacNowPlayingNative {
    val options = mapOf<String, Any>(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name())
    val explicit = System.getenv("FUOEVOLVE_NOW_PLAYING_BRIDGE_PATH")
        ?.takeIf(String::isNotBlank)
        ?.let { Paths.get(it) }
    val library = sequenceOf(
        explicit,
        developmentBridgePath("desktopApp/native/macos-now-playing"),
        developmentBridgePath("native/macos-now-playing"),
    ).filterNotNull().firstOrNull { Files.isRegularFile(it) }

    return if (library != null) {
        Native.load(library.toAbsolutePath().toString(), MacNowPlayingNative::class.java, options)
    } else {
        Native.load(MAC_NOW_PLAYING_LIBRARY_NAME, MacNowPlayingNative::class.java, options)
    }
}

private fun developmentBridgePath(relativeRoot: String): Path =
    Paths.get(System.getProperty("user.dir"), relativeRoot, "target", "release", MAC_NOW_PLAYING_DYLIB_NAME)

private const val ERROR_BUFFER_BYTES = 2048
private const val MAC_NOW_PLAYING_LIBRARY_NAME = "fuoevolve_now_playing_bridge"
private const val MAC_NOW_PLAYING_DYLIB_NAME = "libfuoevolve_now_playing_bridge.dylib"
