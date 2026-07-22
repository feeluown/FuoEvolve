package org.feeluown.mobile.desktop

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.feeluown.mobile.FuoPlayerController
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.RepeatMode
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.PropertiesEmitsChangedSignal.EmitChangeSignal
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

@DBusInterfaceName(MPRIS_ROOT_INTERFACE)
internal interface MprisRoot : DBusInterface {
    @DBusMemberName("Raise")
    fun raise()

    @DBusMemberName("Quit")
    fun quit()

    @DBusBoundProperty(name = "CanQuit", emitChangeSignal = EmitChangeSignal.CONST)
    fun getCanQuit(): Boolean

    @DBusBoundProperty(name = "Fullscreen")
    fun getFullscreen(): Boolean

    @DBusBoundProperty(name = "Fullscreen")
    fun setFullscreen(fullscreen: Boolean)

    @DBusBoundProperty(name = "CanSetFullscreen", emitChangeSignal = EmitChangeSignal.CONST)
    fun getCanSetFullscreen(): Boolean

    @DBusBoundProperty(name = "CanRaise", emitChangeSignal = EmitChangeSignal.CONST)
    fun getCanRaise(): Boolean

    @DBusBoundProperty(name = "HasTrackList", emitChangeSignal = EmitChangeSignal.CONST)
    fun getHasTrackList(): Boolean

    @DBusBoundProperty(name = "Identity", emitChangeSignal = EmitChangeSignal.CONST)
    fun getIdentity(): String

    @DBusBoundProperty(name = "DesktopEntry", emitChangeSignal = EmitChangeSignal.CONST)
    fun getDesktopEntry(): String

    @DBusBoundProperty(
        name = "SupportedUriSchemes",
        type = MprisStringListType::class,
        emitChangeSignal = EmitChangeSignal.CONST,
    )
    fun getSupportedUriSchemes(): List<String>

    @DBusBoundProperty(
        name = "SupportedMimeTypes",
        type = MprisStringListType::class,
        emitChangeSignal = EmitChangeSignal.CONST,
    )
    fun getSupportedMimeTypes(): List<String>
}

@DBusInterfaceName(MPRIS_PLAYER_INTERFACE)
internal interface MprisPlayer : DBusInterface {
    @DBusMemberName("Next")
    fun next()

    @DBusMemberName("Previous")
    fun previous()

    @DBusMemberName("Pause")
    fun pause()

    @DBusMemberName("PlayPause")
    fun playPause()

    @DBusMemberName("Stop")
    fun stop()

    @DBusMemberName("Play")
    fun play()

    @DBusMemberName("Seek")
    fun seek(offset: Long)

    @DBusMemberName("SetPosition")
    fun setPosition(trackId: DBusPath, position: Long)

    @DBusMemberName("OpenUri")
    fun openUri(uri: String)

    @DBusBoundProperty(name = "PlaybackStatus")
    fun getPlaybackStatus(): String

    @DBusBoundProperty(name = "LoopStatus")
    fun getLoopStatus(): String

    @DBusBoundProperty(name = "LoopStatus")
    fun setLoopStatus(loopStatus: String)

    @DBusBoundProperty(name = "Rate")
    fun getRate(): Double

    @DBusBoundProperty(name = "Rate")
    fun setRate(rate: Double)

    @DBusBoundProperty(name = "Shuffle")
    fun getShuffle(): Boolean

    @DBusBoundProperty(name = "Shuffle")
    fun setShuffle(shuffle: Boolean)

    @DBusBoundProperty(name = "Metadata", type = MprisMetadataType::class)
    fun getMetadata(): Map<String, Variant<*>>

    @DBusBoundProperty(name = "Volume")
    fun getVolume(): Double

    @DBusBoundProperty(name = "Volume")
    fun setVolume(volume: Double)

    @DBusBoundProperty(name = "Position", emitChangeSignal = EmitChangeSignal.FALSE)
    fun getPosition(): Long

    @DBusBoundProperty(name = "MinimumRate", emitChangeSignal = EmitChangeSignal.CONST)
    fun getMinimumRate(): Double

    @DBusBoundProperty(name = "MaximumRate", emitChangeSignal = EmitChangeSignal.CONST)
    fun getMaximumRate(): Double

    @DBusBoundProperty(name = "CanGoNext")
    fun getCanGoNext(): Boolean

    @DBusBoundProperty(name = "CanGoPrevious")
    fun getCanGoPrevious(): Boolean

    @DBusBoundProperty(name = "CanPlay")
    fun getCanPlay(): Boolean

    @DBusBoundProperty(name = "CanPause")
    fun getCanPause(): Boolean

    @DBusBoundProperty(name = "CanSeek")
    fun getCanSeek(): Boolean

    @DBusBoundProperty(name = "CanControl", emitChangeSignal = EmitChangeSignal.CONST)
    fun getCanControl(): Boolean

    class Seeked(
        path: String,
        @JvmField val position: Long,
    ) : DBusSignal(path, position)
}

internal interface MprisStringListType : TypeRef<List<String>>

internal interface MprisMetadataType : TypeRef<Map<String, Variant<*>>>

internal data class MprisPlaybackSnapshot(
    val status: PlayerStatus = PlayerStatus.Idle,
    val track: MusicTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Double = 1.0,
    val repeatMode: RepeatMode = RepeatMode.QUEUE,
    val shuffle: Boolean = false,
)

internal interface MprisControls {
    fun raise()
    fun next()
    fun previous()
    fun pause()
    fun playPause()
    fun stop()
    fun play()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Double)
    fun setRepeatMode(repeatMode: RepeatMode)
    fun setShuffleEnabled(enabled: Boolean)
}

internal class MprisObject(
    initialSnapshot: MprisPlaybackSnapshot,
    private val controls: MprisControls,
    private val onSeeked: (Long) -> Unit = {},
    private val monotonicTimeNanos: () -> Long = System::nanoTime,
    private val positionSourceMs: (() -> Long)? = null,
) : MprisRoot, MprisPlayer {
    @Volatile
    private var positionAnchor = MprisPositionAnchor(
        snapshot = initialSnapshot,
        sourcePositionMs = initialSnapshot.positionMs,
        sampledAtNanos = monotonicTimeNanos(),
    )

    var snapshot: MprisPlaybackSnapshot
        get() = positionAnchor.snapshot
        set(value) {
            val nowNanos = monotonicTimeNanos()
            val previousAnchor = positionAnchor
            val anchoredPositionMs = if (
                previousAnchor.snapshot.trackId == value.trackId &&
                previousAnchor.snapshot.status == PlayerStatus.Playing &&
                previousAnchor.sourcePositionMs == value.positionMs
            ) {
                previousAnchor.positionMsAt(nowNanos)
            } else {
                value.positionMs
            }
            positionAnchor = MprisPositionAnchor(
                snapshot = value.copy(positionMs = anchoredPositionMs),
                sourcePositionMs = value.positionMs,
                sampledAtNanos = nowNanos,
            )
        }

    override fun raise() = controls.raise()
    override fun quit() = Unit
    override fun getCanQuit(): Boolean = false
    override fun getFullscreen(): Boolean = false
    override fun setFullscreen(fullscreen: Boolean) = Unit
    override fun getCanSetFullscreen(): Boolean = false
    override fun getCanRaise(): Boolean = true
    override fun getHasTrackList(): Boolean = false
    override fun getIdentity(): String = "FuoEvolve"
    override fun getDesktopEntry(): String = "fuo-evolve"
    override fun getSupportedUriSchemes(): List<String> = emptyList()
    override fun getSupportedMimeTypes(): List<String> = emptyList()

    override fun next() = controls.next()
    override fun previous() = controls.previous()
    override fun pause() = controls.pause()
    override fun playPause() = controls.playPause()
    override fun stop() = controls.stop()
    override fun play() = controls.play()

    override fun seek(offset: Long) {
        val current = snapshot
        if (!current.canSeek) return
        val targetMs = currentPositionMs() + offset.microsecondsToMilliseconds()
        if (targetMs > current.durationMs) {
            controls.next()
        } else {
            val boundedTargetMs = targetMs.coerceAtLeast(0)
            controls.seekTo(boundedTargetMs)
            anchorPosition(boundedTargetMs)
            onSeeked(boundedTargetMs.millisecondsToMicroseconds())
        }
    }

    override fun setPosition(trackId: DBusPath, position: Long) {
        val current = snapshot
        if (!current.canSeek || trackId.path != current.trackId?.path || position < 0) return
        val positionMs = position.microsecondsToMilliseconds()
        if (positionMs <= current.durationMs) {
            controls.seekTo(positionMs)
            anchorPosition(positionMs)
            onSeeked(positionMs.millisecondsToMicroseconds())
        }
    }

    override fun openUri(uri: String) = Unit
    override fun getPlaybackStatus(): String = snapshot.playbackStatus
    override fun getLoopStatus(): String = snapshot.loopStatus

    override fun setLoopStatus(loopStatus: String) {
        val repeatMode = when (loopStatus) {
            "None" -> RepeatMode.OFF
            "Playlist" -> RepeatMode.QUEUE
            "Track" -> RepeatMode.SINGLE
            else -> return
        }
        controls.setRepeatMode(repeatMode)
    }

    override fun getRate(): Double = 1.0

    override fun setRate(rate: Double) {
        if (rate == 0.0) controls.pause()
    }

    override fun getShuffle(): Boolean = snapshot.shuffle
    override fun setShuffle(shuffle: Boolean) = controls.setShuffleEnabled(shuffle)
    override fun getMetadata(): Map<String, Variant<*>> = snapshot.metadata
    override fun getVolume(): Double = snapshot.volume.coerceIn(0.0, 1.0)
    override fun setVolume(volume: Double) = controls.setVolume(volume.coerceIn(0.0, 1.0))
    override fun getPosition(): Long = currentPositionMs().millisecondsToMicroseconds()
    override fun getMinimumRate(): Double = 1.0
    override fun getMaximumRate(): Double = 1.0
    override fun getCanGoNext(): Boolean = snapshot.hasTrack
    override fun getCanGoPrevious(): Boolean = snapshot.hasTrack
    override fun getCanPlay(): Boolean = snapshot.hasTrack
    override fun getCanPause(): Boolean = snapshot.hasTrack
    override fun getCanSeek(): Boolean = snapshot.canSeek
    override fun getCanControl(): Boolean = true
    override fun isRemote(): Boolean = false
    override fun getObjectPath(): String = MPRIS_OBJECT_PATH

    private fun anchorPosition(positionMs: Long) {
        val current = positionAnchor.snapshot
        val boundedPositionMs = if (current.durationMs > 0) {
            positionMs.coerceIn(0, current.durationMs)
        } else {
            positionMs.coerceAtLeast(0)
        }
        positionAnchor = MprisPositionAnchor(
            snapshot = current.copy(positionMs = boundedPositionMs),
            sourcePositionMs = boundedPositionMs,
            sampledAtNanos = monotonicTimeNanos(),
        )
    }

    private fun currentPositionMs(): Long {
        val current = snapshot
        val positionMs = positionSourceMs
            ?.let { source -> runCatching(source).getOrNull() }
            ?: positionAnchor.positionMsAt(monotonicTimeNanos())
        val nonNegativePosition = positionMs.coerceAtLeast(0)
        return if (current.durationMs > 0) {
            nonNegativePosition.coerceAtMost(current.durationMs)
        } else {
            nonNegativePosition
        }
    }
}

private data class MprisPositionAnchor(
    val snapshot: MprisPlaybackSnapshot,
    val sourcePositionMs: Long,
    val sampledAtNanos: Long,
) {
    fun positionMsAt(nowNanos: Long): Long {
        val elapsedMs = if (snapshot.status == PlayerStatus.Playing) {
            (nowNanos - sampledAtNanos).coerceAtLeast(0) / NANOSECONDS_PER_MILLISECOND
        } else {
            0
        }
        val positionMs = snapshot.positionMs.coerceAtLeast(0) + elapsedMs
        return if (snapshot.durationMs > 0) positionMs.coerceAtMost(snapshot.durationMs) else positionMs
    }
}

internal class LinuxMprisService private constructor(
    private val connection: DBusConnection,
    private val exportedObject: MprisObject,
    private val stateJob: Job,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stateJob.cancel()
        runCatching { connection.unExportObject(MPRIS_OBJECT_PATH) }
        runCatching { connection.releaseBusName(MPRIS_BUS_NAME) }
        runCatching { connection.close() }
    }

    companion object {
        fun start(
            controller: FuoPlayerController,
            scope: CoroutineScope,
            onRaise: () -> Unit,
            positionSourceMs: () -> Long,
        ): LinuxMprisService? {
            if (!System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) return null
            var connection: DBusConnection? = null
            return runCatching {
                val controls = ControllerMprisControls(controller, scope, onRaise)
                val initialSnapshot = controller.mprisSnapshot()
                val pendingSeekPositionMs = AtomicLong(NO_PENDING_SEEK_POSITION)
                val exportedObject = MprisObject(
                    initialSnapshot = initialSnapshot,
                    controls = controls,
                    onSeeked = { position ->
                        pendingSeekPositionMs.set(position.microsecondsToMilliseconds())
                        connection?.sendMessage(MprisPlayer.Seeked(MPRIS_OBJECT_PATH, position))
                    },
                    positionSourceMs = positionSourceMs,
                )
                connection = DBusConnectionBuilder.forSessionBus().build().also {
                    it.exportObject(MPRIS_OBJECT_PATH, exportedObject)
                    it.requestBusName(MPRIS_BUS_NAME)
                }
                var previousSnapshot = initialSnapshot
                val activeConnection = checkNotNull(connection)
                val stateJob = scope.launch {
                    snapshotFlow { controller.mprisSnapshot() }
                        .distinctUntilChanged()
                        .collect { snapshot ->
                            exportedObject.snapshot = snapshot
                            val changedProperties = changedPlayerProperties(previousSnapshot, snapshot)
                            if (changedProperties.isNotEmpty()) {
                                activeConnection.sendMessage(
                                    Properties.PropertiesChanged(
                                        MPRIS_OBJECT_PATH,
                                        MPRIS_PLAYER_INTERFACE,
                                        changedProperties,
                                        emptyList(),
                                    ),
                                )
                            }
                            val pendingPositionMs = pendingSeekPositionMs.get()
                            val observedRequestedSeek = pendingPositionMs != NO_PENDING_SEEK_POSITION &&
                                previousSnapshot.trackId == snapshot.trackId &&
                                abs(snapshot.positionMs - pendingPositionMs) <= POSITION_JUMP_TOLERANCE_MS
                            if (observedRequestedSeek) {
                                pendingSeekPositionMs.compareAndSet(pendingPositionMs, NO_PENDING_SEEK_POSITION)
                            }
                            if (isUnexpectedPositionChange(previousSnapshot, snapshot) && !observedRequestedSeek) {
                                activeConnection.sendMessage(
                                    MprisPlayer.Seeked(
                                        MPRIS_OBJECT_PATH,
                                        snapshot.positionMs.millisecondsToMicroseconds(),
                                    ),
                                )
                            } else if (shouldResyncPositionAfterLoading(previousSnapshot, snapshot)) {
                                activeConnection.sendMessage(
                                    MprisPlayer.Seeked(MPRIS_OBJECT_PATH, exportedObject.getPosition()),
                                )
                            }
                            previousSnapshot = snapshot
                        }
                }
                LinuxMprisService(activeConnection, exportedObject, stateJob)
            }.onFailure { throwable ->
                runCatching { connection?.close() }
                System.err.println("FuoEvolve MPRIS service unavailable: ${throwable.message}")
            }.getOrNull()
        }
    }
}

private class ControllerMprisControls(
    private val controller: FuoPlayerController,
    private val scope: CoroutineScope,
    private val onRaise: () -> Unit,
) : MprisControls {
    private fun dispatch(action: () -> Unit) {
        scope.launch { action() }
    }

    override fun raise() = dispatch(onRaise)
    override fun next() = dispatch(controller::next)
    override fun previous() = dispatch(controller::previous)
    override fun pause() = dispatch(controller::pause)
    override fun playPause() = dispatch(controller::toggle)
    override fun stop() = dispatch(controller::stop)
    override fun play() = dispatch(controller::play)
    override fun seekTo(positionMs: Long) = dispatch { controller.seekTo(positionMs) }
    override fun setVolume(volume: Double) = dispatch { controller.setVolume(volume) }
    override fun setRepeatMode(repeatMode: RepeatMode) = dispatch { controller.setRepeatMode(repeatMode) }
    override fun setShuffleEnabled(enabled: Boolean) = dispatch { controller.setShuffleEnabled(enabled) }
}

internal val MprisPlaybackSnapshot.playbackStatus: String
    get() = when (status) {
        PlayerStatus.Playing, PlayerStatus.Loading -> "Playing"
        PlayerStatus.Paused -> "Paused"
        PlayerStatus.Idle, PlayerStatus.Error, PlayerStatus.Ended -> "Stopped"
    }

internal val MprisPlaybackSnapshot.loopStatus: String
    get() = when (repeatMode) {
        RepeatMode.OFF -> "None"
        RepeatMode.QUEUE -> "Playlist"
        RepeatMode.SINGLE -> "Track"
    }

internal val MprisPlaybackSnapshot.trackId: DBusPath?
    get() = track?.let { DBusPath("/org/feeluown/FuoEvolve/track/${it.stableTrackId()}") }

internal val MprisPlaybackSnapshot.metadata: Map<String, Variant<*>>
    get() {
        val currentTrack = track ?: return emptyMap()
        return buildMap {
            put("mpris:trackid", Variant(checkNotNull(trackId)))
            put("xesam:title", Variant(currentTrack.title))
            if (currentTrack.artists.isNotBlank()) {
                put("xesam:artist", Variant(listOf(currentTrack.artists), "as"))
            }
            if (currentTrack.album.isNotBlank()) {
                put("xesam:album", Variant(currentTrack.album))
            }
            if (durationMs > 0) {
                put("mpris:length", Variant(durationMs.millisecondsToMicroseconds()))
            }
            currentTrack.coverUrl?.toMprisArtworkUrl()?.let {
                put("mpris:artUrl", Variant(it))
            }
        }
    }

internal val MprisPlaybackSnapshot.hasTrack: Boolean
    get() = track != null

internal val MprisPlaybackSnapshot.canSeek: Boolean
    get() = track != null && durationMs > 0

internal fun changedPlayerProperties(
    previous: MprisPlaybackSnapshot,
    current: MprisPlaybackSnapshot,
): Map<String, Variant<*>> {
    val previousProperties = previous.playerProperties()
    val currentProperties = current.playerProperties()
    return currentProperties.filter { (name, value) -> previousProperties[name] != value }
}

internal fun isUnexpectedPositionChange(
    previous: MprisPlaybackSnapshot,
    current: MprisPlaybackSnapshot,
): Boolean {
    if (previous.trackId != current.trackId || current.track == null) return false
    val delta = current.positionMs - previous.positionMs
    return if (previous.status == PlayerStatus.Playing && current.status == PlayerStatus.Playing) {
        delta < -POSITION_JUMP_TOLERANCE_MS || abs(delta) > PLAYING_POSITION_JUMP_THRESHOLD_MS
    } else {
        abs(delta) > POSITION_JUMP_TOLERANCE_MS
    }
}

internal fun shouldResyncPositionAfterLoading(
    previous: MprisPlaybackSnapshot,
    current: MprisPlaybackSnapshot,
): Boolean = previous.status == PlayerStatus.Loading &&
    current.status == PlayerStatus.Playing &&
    previous.trackId == current.trackId &&
    current.track != null

private fun MprisPlaybackSnapshot.playerProperties(): Map<String, Variant<*>> = mapOf(
    "PlaybackStatus" to Variant(playbackStatus),
    "LoopStatus" to Variant(loopStatus),
    "Shuffle" to Variant(shuffle),
    "Metadata" to Variant(metadata, "a{sv}"),
    "Volume" to Variant(volume.coerceIn(0.0, 1.0)),
    "CanGoNext" to Variant(hasTrack),
    "CanGoPrevious" to Variant(hasTrack),
    "CanPlay" to Variant(hasTrack),
    "CanPause" to Variant(hasTrack),
    "CanSeek" to Variant(canSeek),
)

private fun FuoPlayerController.mprisSnapshot(): MprisPlaybackSnapshot {
    val state = playbackState
    return MprisPlaybackSnapshot(
        status = state.status,
        track = state.currentTrack,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        volume = state.volume,
        repeatMode = repeatMode,
        shuffle = isShuffleEnabled,
    )
}

private fun MusicTrack.stableTrackId(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest("$source:$id".toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun String.toMprisArtworkUrl(): String? {
    val value = trim().takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(value) }.getOrNull()
    return if (uri?.scheme != null) value else File(value).toURI().toString()
}

private fun Long.millisecondsToMicroseconds(): Long =
    coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE / MICROSECONDS_PER_MILLISECOND) * MICROSECONDS_PER_MILLISECOND

private fun Long.microsecondsToMilliseconds(): Long = this / MICROSECONDS_PER_MILLISECOND

internal const val MPRIS_BUS_NAME = "org.mpris.MediaPlayer2.FuoEvolve"
internal const val MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2"
internal const val MPRIS_ROOT_INTERFACE = "org.mpris.MediaPlayer2"
internal const val MPRIS_PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
private const val MICROSECONDS_PER_MILLISECOND = 1_000L
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
private const val NO_PENDING_SEEK_POSITION = -1L
private const val POSITION_JUMP_TOLERANCE_MS = 250L
private const val PLAYING_POSITION_JUMP_THRESHOLD_MS = 2_500L
