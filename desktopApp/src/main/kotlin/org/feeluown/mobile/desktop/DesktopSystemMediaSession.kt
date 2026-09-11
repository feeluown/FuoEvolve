package org.feeluown.mobile.desktop

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.RepeatMode
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

internal fun createDesktopSystemMediaSession(playbackSession: PlaybackSession): AutoCloseable {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    if (!os.contains("linux")) return AutoCloseable { }
    return runCatching { LinuxMprisSession(playbackSession) }
        .onSuccess { AppLogger.i("SystemMediaSession", "MPRIS session created bus=$MPRIS_BUS_NAME") }
        .getOrElse { error ->
            AppLogger.w("SystemMediaSession", "MPRIS unavailable", error)
            AutoCloseable { }
        }
}

private class LinuxMprisSession(
    private val playbackSession: PlaybackSession,
) : AutoCloseable {
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus()
        .withShared(false)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var expectedExplicitSeekPositionUs: Long? = null
    private val exportedObject = LinuxMprisObject(
        playbackSession = playbackSession,
        onSeeked = ::publishSeeked,
        onSeekRequested = ::rememberExplicitSeek,
    )
    private var lastStateLogNanos = 0L

    init {
        connection.requestBusName(MPRIS_BUS_NAME)
        connection.exportObject(exportedObject)
        scope.launch {
            var previous = playbackSession.state.value
            playbackSession.state.collect { current ->
                val changed = mprisChangedProperties(previous, current)
                val currentPositionUs = current.positionMs * MICROSECONDS_PER_MILLISECOND
                val explicitSeekObserved = expectedExplicitSeekPositionUs == currentPositionUs
                if (explicitSeekObserved) expectedExplicitSeekPositionUs = null
                if (!explicitSeekObserved) {
                    mprisSeekedPositionUs(previous, current)?.let(::publishSeeked)
                }
                if (changed.isNotEmpty()) {
                    runCatching {
                        connection.sendMessage(
                            Properties.PropertiesChanged(
                                MPRIS_OBJECT_PATH,
                                MPRIS_PLAYER_INTERFACE,
                                changed,
                                emptyList(),
                            ),
                        )
                    }
                }
                previous = current
            }
        }
    }

    private fun publishSeeked(positionUs: Long) {
        runCatching {
            connection.sendMessage(MprisPlayer.Seeked(MPRIS_OBJECT_PATH, positionUs))
        }
    }

    private fun rememberExplicitSeek(positionUs: Long) {
        expectedExplicitSeekPositionUs =
            (positionUs / MICROSECONDS_PER_MILLISECOND) * MICROSECONDS_PER_MILLISECOND
    }

    override fun close() {
        scope.cancel()
        runCatching { connection.close() }
    }
}

@DBusInterfaceName(MPRIS_ROOT_INTERFACE)
internal interface MprisMediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()

    @DBusBoundProperty(access = Access.READ, name = "CanQuit")
    fun getCanQuit(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "Fullscreen")
    fun getFullscreen(): Boolean

    @DBusBoundProperty(access = Access.WRITE, name = "Fullscreen")
    fun setFullscreen(value: Boolean)

    @DBusBoundProperty(access = Access.READ, name = "CanSetFullscreen")
    fun getCanSetFullscreen(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "CanRaise")
    fun getCanRaise(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "HasTrackList")
    fun getHasTrackList(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "Identity")
    fun getIdentity(): String

    @DBusBoundProperty(access = Access.READ, name = "DesktopEntry")
    fun getDesktopEntry(): String

    @DBusBoundProperty(access = Access.READ, name = "SupportedUriSchemes")
    fun getSupportedUriSchemes(): Array<String>

    @DBusBoundProperty(access = Access.READ, name = "SupportedMimeTypes")
    fun getSupportedMimeTypes(): Array<String>
}

@DBusInterfaceName(MPRIS_PLAYER_INTERFACE)
internal interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    @DBusBoundProperty(access = Access.READ, name = "PlaybackStatus")
    fun getPlaybackStatus(): String

    @DBusBoundProperty(access = Access.READ, name = "LoopStatus")
    fun getLoopStatus(): String

    @DBusBoundProperty(access = Access.WRITE, name = "LoopStatus")
    fun setLoopStatus(value: String)

    @DBusBoundProperty(access = Access.READ, name = "Rate")
    fun getRate(): Double

    @DBusBoundProperty(access = Access.WRITE, name = "Rate")
    fun setRate(value: Double)

    @DBusBoundProperty(access = Access.READ, name = "Shuffle")
    fun getShuffle(): Boolean

    @DBusBoundProperty(access = Access.WRITE, name = "Shuffle")
    fun setShuffle(value: Boolean)

    @DBusBoundProperty(access = Access.READ, name = "Metadata")
    fun getMetadata(): Map<String, Variant<*>>

    @DBusBoundProperty(access = Access.READ, name = "Volume")
    fun getVolume(): Double

    @DBusBoundProperty(access = Access.WRITE, name = "Volume")
    fun setVolume(value: Double)

    @DBusBoundProperty(access = Access.READ, name = "Position")
    fun getPosition(): Long

    @DBusBoundProperty(access = Access.READ, name = "MinimumRate")
    fun getMinimumRate(): Double

    @DBusBoundProperty(access = Access.READ, name = "MaximumRate")
    fun getMaximumRate(): Double

    @DBusBoundProperty(access = Access.READ, name = "CanGoNext")
    fun getCanGoNext(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "CanGoPrevious")
    fun getCanGoPrevious(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "CanPlay")
    fun getCanPlay(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "CanPause")
    fun getCanPause(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "CanSeek")
    fun getCanSeek(): Boolean

    @DBusBoundProperty(access = Access.READ, name = "CanControl")
    fun getCanControl(): Boolean

    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

@Suppress("FunctionName")
internal class LinuxMprisObject(
    private val playbackSession: PlaybackSession,
    private val onSeeked: (Long) -> Unit,
    private val onSeekRequested: (Long) -> Unit = {},
) : MprisMediaPlayer2, MprisPlayer {
    override fun getObjectPath(): String = MPRIS_OBJECT_PATH

    override fun Raise() = Unit
    override fun Quit() = Unit
    override fun getCanQuit(): Boolean = false
    override fun getFullscreen(): Boolean = false
    override fun setFullscreen(value: Boolean) = Unit
    override fun getCanSetFullscreen(): Boolean = false
    override fun getCanRaise(): Boolean = false
    override fun getHasTrackList(): Boolean = false
    override fun getIdentity(): String = "FuoEvolve"
    override fun getDesktopEntry(): String = "fuo-evolve"
    override fun getSupportedUriSchemes(): Array<String> = emptyArray()
    override fun getSupportedMimeTypes(): Array<String> = emptyArray()

    override fun Next() {
        val before = playbackSession.state.value
        if (!mprisCanGoNext(before)) return
        playbackSession.next()
        preserveNonPlayingStatusAfterSkip(before.status)
    }

    override fun Previous() {
        val before = playbackSession.state.value
        if (!mprisCanGoPrevious(before)) return
        playbackSession.previous()
        preserveNonPlayingStatusAfterSkip(before.status)
    }

    override fun Pause() {
        if (mprisCanPause(playbackSession.state.value)) playbackSession.pause()
    }

    override fun PlayPause() = playbackSession.toggle()
    override fun Stop() = playbackSession.stop()
    override fun Play() {
        if (mprisCanPlay(playbackSession.state.value)) playbackSession.play()
    }

    override fun Seek(offset: Long) {
        val state = playbackSession.state.value
        if (!mprisCanSeek(state)) return
        val currentUs = state.positionMs * MICROSECONDS_PER_MILLISECOND
        val targetUs = currentUs + offset
        val durationUs = state.durationMs * MICROSECONDS_PER_MILLISECOND
        if (durationUs > 0L && targetUs > durationUs) {
            Next()
            return
        }
        val boundedUs = targetUs.coerceAtLeast(0L)
        onSeekRequested(boundedUs)
        playbackSession.seekTo(boundedUs / MICROSECONDS_PER_MILLISECOND)
        onSeeked(boundedUs)
    }

    override fun SetPosition(trackId: DBusPath, position: Long) {
        val state = playbackSession.state.value
        val track = state.currentTrack ?: return
        if (!mprisCanSeek(state) || trackId != mprisTrackPath(track.id) || position < 0L) return
        val durationUs = state.durationMs * MICROSECONDS_PER_MILLISECOND
        if (durationUs > 0L && position >= durationUs) return
        onSeekRequested(position)
        playbackSession.seekTo(position / MICROSECONDS_PER_MILLISECOND)
        onSeeked(position)
    }

    override fun OpenUri(uri: String) = Unit

    override fun getPlaybackStatus(): String = mprisPlaybackStatus(playbackSession.state.value.status)
    override fun getLoopStatus(): String = mprisLoopStatus(playbackSession.state.value.repeatMode)
    override fun setLoopStatus(value: String) {
        if (!playbackSession.state.value.canChangePlaybackMode) return
        mprisRepeatMode(value)?.let(playbackSession::setRepeatMode)
    }
    override fun getRate(): Double = 1.0
    override fun setRate(value: Double) {
        if (value == 0.0) playbackSession.pause()
    }
    override fun getShuffle(): Boolean = playbackSession.state.value.shuffleEnabled
    override fun setShuffle(value: Boolean) {
        if (playbackSession.state.value.canChangePlaybackMode) {
            playbackSession.setShuffleEnabled(value)
        }
    }
    override fun getMetadata(): Map<String, Variant<*>> = mprisMetadata(playbackSession.state.value)
    override fun getVolume(): Double = playbackSession.state.value.volume
    override fun setVolume(value: Double) {
        if (value.isFinite()) playbackSession.setVolume(value.coerceIn(0.0, 1.0))
    }
    override fun getPosition(): Long {
        return playbackSession.state.value.positionMs * MICROSECONDS_PER_MILLISECOND
    }
    override fun getMinimumRate(): Double = 1.0
    override fun getMaximumRate(): Double = 1.0
    override fun getCanGoNext(): Boolean = mprisCanGoNext(playbackSession.state.value)
    override fun getCanGoPrevious(): Boolean = mprisCanGoPrevious(playbackSession.state.value)
    override fun getCanPlay(): Boolean = mprisCanPlay(playbackSession.state.value)
    override fun getCanPause(): Boolean = mprisCanPause(playbackSession.state.value)
    override fun getCanSeek(): Boolean = mprisCanSeek(playbackSession.state.value)
    override fun getCanControl(): Boolean = true

    private fun preserveNonPlayingStatusAfterSkip(status: PlaybackSessionStatus) {
        when (status) {
            PlaybackSessionStatus.Paused -> playbackSession.pause()
            PlaybackSessionStatus.Idle,
            PlaybackSessionStatus.Loading,
            PlaybackSessionStatus.Error,
            PlaybackSessionStatus.Ended -> playbackSession.stop()
            PlaybackSessionStatus.Playing -> Unit
        }
    }
}

internal fun mprisPlaybackStatus(status: PlaybackSessionStatus): String = when (status) {
    PlaybackSessionStatus.Playing -> "Playing"
    PlaybackSessionStatus.Paused -> "Paused"
    PlaybackSessionStatus.Idle,
    PlaybackSessionStatus.Loading,
    PlaybackSessionStatus.Error,
    PlaybackSessionStatus.Ended -> "Stopped"
}

internal fun mprisLoopStatus(repeatMode: RepeatMode): String = when (repeatMode) {
    RepeatMode.OFF -> "None"
    RepeatMode.SINGLE -> "Track"
    RepeatMode.QUEUE -> "Playlist"
}

internal fun mprisRepeatMode(loopStatus: String): RepeatMode? = when (loopStatus) {
    "None" -> RepeatMode.OFF
    "Track" -> RepeatMode.SINGLE
    "Playlist" -> RepeatMode.QUEUE
    else -> null
}

internal fun mprisTrackPath(trackId: String): DBusPath {
    val digest = MessageDigest.getInstance("SHA-256").digest(trackId.encodeToByteArray())
    val suffix = digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return DBusPath("/org/feeluown/FuoEvolve/tracks/t$suffix")
}

internal fun mprisMetadata(state: PlaybackSessionState): Map<String, Variant<*>> {
    val track = state.currentTrack ?: return emptyMap()
    return buildMap {
        put("mpris:trackid", Variant(mprisTrackPath(track.id)))
        put("xesam:title", Variant(track.title))
        if (track.artists.isNotBlank()) put("xesam:artist", Variant(arrayOf(track.artists), "as"))
        if (track.album.isNotBlank()) put("xesam:album", Variant(track.album))
        val durationMs = state.durationMs.takeIf { it > 0L } ?: track.durationMs?.takeIf { it > 0L }
        durationMs?.let { put("mpris:length", Variant(it * MICROSECONDS_PER_MILLISECOND)) }
        track.coverUrl?.takeIf(String::isNotBlank)?.let { put("mpris:artUrl", Variant(it)) }
    }
}

internal fun mprisChangedProperties(
    previous: PlaybackSessionState,
    current: PlaybackSessionState,
): Map<String, Variant<*>> = buildMap {
    val previousStatus = mprisPlaybackStatus(previous.status)
    val currentStatus = mprisPlaybackStatus(current.status)
    if (previousStatus != currentStatus) put("PlaybackStatus", Variant(currentStatus))
    if (previous.currentTrack != current.currentTrack || previous.durationMs != current.durationMs) {
        put("Metadata", Variant(mprisMetadata(current), "a{sv}"))
    }
    if (previous.repeatMode != current.repeatMode) put("LoopStatus", Variant(mprisLoopStatus(current.repeatMode)))
    if (previous.shuffleEnabled != current.shuffleEnabled) put("Shuffle", Variant(current.shuffleEnabled))
    if (previous.volume != current.volume) put("Volume", Variant(current.volume))
    if (mprisCanGoNext(previous) != mprisCanGoNext(current)) put("CanGoNext", Variant(mprisCanGoNext(current)))
    if (mprisCanGoPrevious(previous) != mprisCanGoPrevious(current)) put("CanGoPrevious", Variant(mprisCanGoPrevious(current)))
    if (mprisCanPlay(previous) != mprisCanPlay(current)) put("CanPlay", Variant(mprisCanPlay(current)))
    if (mprisCanPause(previous) != mprisCanPause(current)) put("CanPause", Variant(mprisCanPause(current)))
    if (mprisCanSeek(previous) != mprisCanSeek(current)) put("CanSeek", Variant(mprisCanSeek(current)))
}

internal fun mprisSeekedPositionUs(
    previous: PlaybackSessionState,
    current: PlaybackSessionState,
): Long? {
    val currentTrackId = current.currentTrack?.id ?: return null
    if (previous.currentTrack?.id != currentTrackId) return null
    if (current.status != PlaybackSessionStatus.Playing && current.status != PlaybackSessionStatus.Paused) {
        return null
    }
    if (abs(current.positionMs - previous.positionMs) < POSITION_JUMP_LOG_THRESHOLD_MS) return null
    return current.positionMs * MICROSECONDS_PER_MILLISECOND
}

private fun mprisCanGoNext(state: PlaybackSessionState): Boolean = state.canGoNext
private fun mprisCanGoPrevious(state: PlaybackSessionState): Boolean = state.canGoPrevious
private fun mprisCanPlay(state: PlaybackSessionState): Boolean = state.currentTrack != null || state.queueTrackIds.isNotEmpty()
private fun mprisCanPause(state: PlaybackSessionState): Boolean =
    state.currentTrack != null &&
        (state.status == PlaybackSessionStatus.Playing || state.status == PlaybackSessionStatus.Paused)
private fun mprisCanSeek(state: PlaybackSessionState): Boolean =
    state.currentTrack != null &&
        state.durationMs > 0L &&
        (state.status == PlaybackSessionStatus.Playing || state.status == PlaybackSessionStatus.Paused)

private const val MPRIS_BUS_NAME = "org.mpris.MediaPlayer2.FuoEvolve"
private const val MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2"
private const val MPRIS_ROOT_INTERFACE = "org.mpris.MediaPlayer2"
private const val MPRIS_PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
private const val MICROSECONDS_PER_MILLISECOND = 1_000L
private const val POSITION_JUMP_LOG_THRESHOLD_MS = 1_000L
