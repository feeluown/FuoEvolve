package org.feeluown.mobile.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.Structure
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.feeluown.mobile.AudioDecoderInfo
import org.feeluown.mobile.AudioDecoderType
import org.feeluown.mobile.AudioFormatInfo
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackEngine
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackState
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.ResolvedPlaybackSourceAwareEngine
import org.feeluown.mobile.logicalPlaybackTrack
import org.feeluown.mobile.toResolvedPlaybackSource

internal class DesktopMpvPlaybackEngine(
    private val backendFactory: ((DesktopMpvBackendEvent) -> Unit) -> DesktopMpvBackend = { listener ->
        LibMpvBackend(listener)
    },
) : PlaybackEngine, ResolvedPlaybackSourceAwareEngine, AutoCloseable {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var backend: DesktopMpvBackend? = null
    private var backendFailure: Throwable? = null
    @Volatile
    private var paused = false

    override fun prepareLoading(track: MusicTrack) {
        backend?.runCatching { stop() }
        val logicalTrack = track.logicalPlaybackTrack()
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = logicalTrack,
            durationMs = logicalTrack.durationMs ?: 0L,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        val logicalTrack = track.logicalPlaybackTrack()
        startPlayback(
            logicalTrack = logicalTrack,
            payload = payload,
            resolvedSource = payload.toResolvedPlaybackSource(logicalTrack),
        )
    }

    override fun playResolved(
        logicalTrack: MusicTrack,
        resolveTrack: MusicTrack,
        payload: PlaybackPayload,
    ) {
        val logical = logicalTrack.logicalPlaybackTrack()
        startPlayback(
            logicalTrack = logical,
            payload = payload,
            resolvedSource = payload.toResolvedPlaybackSource(
                logicalTrack = logical,
                resolveTrack = resolveTrack,
            ),
        )
    }

    override fun pause() {
        val activeBackend = backend ?: return
        runCatching { activeBackend.setPaused(true) }
            .onSuccess {
                paused = true
                if (mutableState.value.status == PlayerStatus.Playing) {
                    mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
                }
            }
            .onFailure(::publishBackendFailure)
    }

    override fun resume() {
        val activeBackend = backend ?: return
        runCatching { activeBackend.setPaused(false) }
            .onSuccess {
                paused = false
                if (mutableState.value.status == PlayerStatus.Paused) {
                    mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
                }
            }
            .onFailure(::publishBackendFailure)
    }

    override fun stop() {
        backend?.runCatching { stop() }?.onFailure(::publishBackendFailure)
        paused = false
        mutableState.value = PlaybackState()
    }

    override fun seekTo(positionMs: Long) {
        val current = mutableState.value
        if (current.currentTrack == null) return
        val upperBound = current.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, upperBound)
        backend?.runCatching { seekTo(target) }?.onFailure(::publishBackendFailure)
        mutableState.value = mutableState.value.copy(positionMs = target)
    }

    override fun close() {
        val activeBackend = backend
        backend = null
        runCatching { activeBackend?.close() }
    }

    private fun startPlayback(
        logicalTrack: MusicTrack,
        payload: PlaybackPayload,
        resolvedSource: org.feeluown.mobile.ResolvedPlaybackSource,
    ) {
        paused = false
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = logicalTrack,
            resolvedSource = resolvedSource,
            durationMs = payload.durationMs ?: logicalTrack.durationMs ?: 0L,
            lyrics = payload.lyrics ?: logicalTrack.lyrics,
            audioQuality = payload.audioQuality,
            playbackParts = payload.parts,
            currentPartIndex = payload.currentPartIndex,
        )
        val activeBackend = ensureBackend() ?: run {
            publishBackendFailure(backendFailure ?: IllegalStateException("libmpv unavailable"))
            return
        }
        runCatching {
            activeBackend.load(payload.url, payload.headers)
        }.onFailure(::publishBackendFailure)
    }

    private fun ensureBackend(): DesktopMpvBackend? {
        backend?.let { return it }
        if (backendFailure != null) return null
        return runCatching { backendFactory(::handleBackendEvent) }
            .onSuccess { backend = it }
            .onFailure { backendFailure = it }
            .getOrNull()
    }

    private fun handleBackendEvent(event: DesktopMpvBackendEvent) {
        when (event) {
            DesktopMpvBackendEvent.FileLoaded -> Unit
            DesktopMpvBackendEvent.PlaybackRestart -> {
                val current = mutableState.value
                if (current.currentTrack != null && current.status != PlayerStatus.Error) {
                    mutableState.value = current.copy(
                        status = if (paused) PlayerStatus.Paused else PlayerStatus.Playing,
                        errorMessage = null,
                    )
                }
            }
            is DesktopMpvBackendEvent.Property -> handleProperty(event.name, event.value)
            is DesktopMpvBackendEvent.EndFile -> when (event.reason) {
                MPV_END_FILE_REASON_EOF -> {
                    val current = mutableState.value
                    if (current.currentTrack != null) {
                        mutableState.value = current.copy(
                            status = PlayerStatus.Ended,
                            positionMs = current.durationMs.takeIf { it > 0L } ?: current.positionMs,
                        )
                    }
                }
                MPV_END_FILE_REASON_ERROR -> publishBackendFailure(
                    IllegalStateException(event.errorMessage ?: "libmpv playback failed"),
                )
                // STOP is emitted when a file is replaced or explicitly stopped. The application
                // state is already owned by prepareLoading()/stop(), so do not let this stale event
                // overwrite the next playback transaction.
                MPV_END_FILE_REASON_STOP,
                MPV_END_FILE_REASON_QUIT,
                MPV_END_FILE_REASON_REDIRECT,
                -> Unit
            }
            is DesktopMpvBackendEvent.Failure -> publishBackendFailure(event.throwable)
        }
    }

    private fun handleProperty(name: String, value: String?) {
        when (name) {
            "pause" -> {
                paused = value == "yes" || value == "true"
                val current = mutableState.value
                if (current.status == PlayerStatus.Playing || current.status == PlayerStatus.Paused) {
                    mutableState.value = current.copy(
                        status = if (paused) PlayerStatus.Paused else PlayerStatus.Playing,
                    )
                }
            }
            "time-pos" -> value.secondsToMsOrNull()?.let { positionMs ->
                mutableState.value = mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0L))
            }
            "duration" -> value.secondsToMsOrNull()?.let { durationMs ->
                mutableState.value = mutableState.value.copy(durationMs = durationMs.coerceAtLeast(0L))
            }
            "demuxer-cache-time" -> value.secondsToMsOrNull()?.let { bufferedMs ->
                val duration = mutableState.value.durationMs
                mutableState.value = mutableState.value.copy(
                    bufferedMs = if (duration > 0L) bufferedMs.coerceIn(0L, duration) else bufferedMs.coerceAtLeast(0L),
                )
            }
            "file-format" -> updateAudioFormat { it.copy(format = value?.takeIf(String::isNotBlank)) }
            "audio-codec-name" -> {
                val codec = value?.takeIf(String::isNotBlank)
                updateAudioFormat { it.copy(codec = codec) }
                mutableState.value = mutableState.value.copy(
                    audioDecoderInfo = codec?.let {
                        AudioDecoderInfo(
                            type = AudioDecoderType.Software,
                            name = "libmpv / $it",
                        )
                    },
                )
            }
            "audio-bitrate" -> {
                val bitrate = value?.toDoubleOrNull()?.toLong()?.takeIf { it > 0L }
                updateAudioFormat { it.copy(averageBitrate = bitrate) }
            }
        }
    }

    private fun updateAudioFormat(transform: (AudioFormatInfo) -> AudioFormatInfo) {
        val current = mutableState.value
        mutableState.value = current.copy(
            audioFormatInfo = transform(current.audioFormatInfo ?: AudioFormatInfo()),
        )
    }

    private fun publishBackendFailure(throwable: Throwable) {
        val current = mutableState.value
        mutableState.value = current.copy(
            status = PlayerStatus.Error,
            errorMessage = desktopMpvFailureMessage(throwable),
        )
    }
}

internal sealed interface DesktopMpvBackendEvent {
    data object FileLoaded : DesktopMpvBackendEvent
    data object PlaybackRestart : DesktopMpvBackendEvent
    data class Property(val name: String, val value: String?) : DesktopMpvBackendEvent
    data class EndFile(
        val reason: Int,
        val errorMessage: String? = null,
    ) : DesktopMpvBackendEvent
    data class Failure(val throwable: Throwable) : DesktopMpvBackendEvent
}

internal interface DesktopMpvBackend : AutoCloseable {
    fun load(url: String, headers: Map<String, String>)
    fun setPaused(paused: Boolean)
    fun stop()
    fun seekTo(positionMs: Long)
}

private class LibMpvBackend(
    private val listener: (DesktopMpvBackendEvent) -> Unit,
    private val library: MpvNative = loadMpvLibrary(),
) : DesktopMpvBackend {
    private val closed = AtomicBoolean(false)
    private val handle: Pointer
    private val eventThread: Thread

    init {
        handle = library.mpv_create()
            ?: throw IllegalStateException("libmpv mpv_create() returned null")
        try {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("input-default-bindings", "no")
            setOption("vid", "no")
            setOption("ytdl", "no")
            checkMpv(library.mpv_initialize(handle), "mpv_initialize")
            OBSERVED_PROPERTIES.forEach { property ->
                checkMpv(
                    library.mpv_observe_property(handle, 0L, property, MPV_FORMAT_STRING),
                    "observe $property",
                )
            }
        } catch (throwable: Throwable) {
            library.mpv_terminate_destroy(handle)
            throw throwable
        }
        eventThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-libmpv-events",
            block = ::eventLoop,
        )
        Runtime.getRuntime().addShutdownHook(
            thread(start = false, name = "fuoevolve-libmpv-shutdown") { close() },
        )
    }

    override fun load(url: String, headers: Map<String, String>) {
        ensureOpen()
        // Keep headers file-local so source switches cannot leak Referer/Cookie values into
        // the following provider item. This mirrors mpv's own ytdl integration behavior.
        setProperty("file-local-options/http-header-fields", encodeHeaderFields(headers))
        command("loadfile", url, "replace")
    }

    override fun setPaused(paused: Boolean) {
        ensureOpen()
        setProperty("pause", if (paused) "yes" else "no")
    }

    override fun stop() {
        ensureOpen()
        command("stop")
    }

    override fun seekTo(positionMs: Long) {
        ensureOpen()
        command("seek", (positionMs / 1000.0).toString(), "absolute")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        library.mpv_wakeup(handle)
        if (Thread.currentThread() !== eventThread) {
            runCatching { eventThread.join() }
        }
        library.mpv_terminate_destroy(handle)
    }

    private fun eventLoop() {
        try {
            while (!closed.get()) {
                val eventPointer = library.mpv_wait_event(handle, -1.0)
                val event = MpvNativeEvent(eventPointer)
                when (event.eventId) {
                    MPV_EVENT_NONE -> Unit
                    MPV_EVENT_SHUTDOWN -> break
                    MPV_EVENT_FILE_LOADED -> listener(DesktopMpvBackendEvent.FileLoaded)
                    MPV_EVENT_PLAYBACK_RESTART -> listener(DesktopMpvBackendEvent.PlaybackRestart)
                    MPV_EVENT_PROPERTY_CHANGE -> {
                        val data = event.data ?: continue
                        val property = MpvNativeEventProperty(data)
                        listener(
                            DesktopMpvBackendEvent.Property(
                                name = property.name?.getString(0, StandardCharsets.UTF_8.name()).orEmpty(),
                                value = property.stringValue(),
                            ),
                        )
                    }
                    MPV_EVENT_END_FILE -> {
                        val data = event.data ?: continue
                        val endFile = MpvNativeEndFile(data)
                        listener(
                            DesktopMpvBackendEvent.EndFile(
                                reason = endFile.reason,
                                errorMessage = endFile.error
                                    .takeIf { endFile.reason == MPV_END_FILE_REASON_ERROR && it < 0 }
                                    ?.let(library::mpv_error_string),
                            ),
                        )
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (!closed.get()) listener(DesktopMpvBackendEvent.Failure(throwable))
        }
    }

    private fun setOption(name: String, value: String) {
        checkMpv(library.mpv_set_option_string(handle, name, value), "set option $name")
    }

    private fun setProperty(name: String, value: String) {
        checkMpv(library.mpv_set_property_string(handle, name, value), "set property $name")
    }

    private fun command(vararg args: String) {
        val nativeArgs = StringArray(args)
        checkMpv(library.mpv_command(handle, nativeArgs), "command ${args.firstOrNull().orEmpty()}")
    }

    private fun checkMpv(result: Int, operation: String) {
        if (result >= 0) return
        val detail = library.mpv_error_string(result) ?: "error $result"
        throw IllegalStateException("libmpv $operation failed: $detail")
    }

    private fun ensureOpen() {
        check(!closed.get()) { "libmpv backend is closed" }
    }
}

internal interface MpvNative : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String?
}

private class MpvNativeEvent(pointer: Pointer) : Structure(pointer) {
    @JvmField var eventId: Int = 0
    @JvmField var error: Int = 0
    @JvmField var replyUserdata: Long = 0L
    @JvmField var data: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf("eventId", "error", "replyUserdata", "data")

    init {
        read()
    }
}

private class MpvNativeEventProperty(pointer: Pointer) : Structure(pointer) {
    @JvmField var name: Pointer? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf("name", "format", "data")

    init {
        read()
    }

    fun stringValue(): String? {
        if (format != MPV_FORMAT_STRING) return null
        val stringPointer = data?.getPointer(0L) ?: return null
        return stringPointer.getString(0L, StandardCharsets.UTF_8.name())
    }
}

private class MpvNativeEndFile(pointer: Pointer) : Structure(pointer) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlistEntryId: Long = 0L
    @JvmField var playlistInsertId: Long = 0L
    @JvmField var playlistInsertNumEntries: Int = 0

    override fun getFieldOrder(): List<String> = listOf(
        "reason",
        "error",
        "playlistEntryId",
        "playlistInsertId",
        "playlistInsertNumEntries",
    )

    init {
        read()
    }
}

private fun loadMpvLibrary(): MpvNative {
    val explicit = System.getProperty("fuoevolve.libmpv.path")
        ?.takeIf(String::isNotBlank)
        ?: System.getenv("FUOEVOLVE_LIBMPV_PATH")?.takeIf(String::isNotBlank)
    val candidates = buildList {
        explicit?.let(::add)
        when {
            Platform.isWindows() -> addAll(listOf("mpv-2", "mpv"))
            Platform.isMac() -> addAll(listOf("mpv", "libmpv.dylib"))
            else -> addAll(listOf("mpv", "libmpv.so.2", "libmpv.so"))
        }
    }.distinct()

    var lastFailure: Throwable? = null
    candidates.forEach { candidate ->
        try {
            return Native.load(candidate, MpvNative::class.java)
        } catch (throwable: Throwable) {
            lastFailure = throwable
        }
    }
    throw IllegalStateException(
        "Unable to load libmpv from ${candidates.joinToString()}",
        lastFailure,
    )
}

internal fun encodeHeaderFields(headers: Map<String, String>): String = headers
    .mapNotNull { (name, value) ->
        if (name.isBlank() || name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) {
            null
        } else {
            "$name: $value"
        }
    }
    .joinToString(",") { header ->
        val byteLength = header.toByteArray(StandardCharsets.UTF_8).size
        "%$byteLength%$header"
    }

private fun String?.secondsToMsOrNull(): Long? = this
    ?.toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?.let { seconds -> (seconds * 1000.0).toLong() }

private fun desktopMpvFailureMessage(throwable: Throwable): String {
    val detail = throwable.message?.takeIf(String::isNotBlank)
    val isLoadFailure = generateSequence<Throwable>(throwable) { it.cause }
        .any { it is UnsatisfiedLinkError || it.message?.contains("Unable to load libmpv", ignoreCase = true) == true }
    return if (isLoadFailure) {
        "无法加载 libmpv。开发环境请安装 libmpv，或通过 FUOEVOLVE_LIBMPV_PATH 指定动态库路径。" +
            detail?.let { "（$it）" }.orEmpty()
    } else {
        detail?.let { "libmpv 播放失败：$it" } ?: "libmpv 播放失败"
    }
}

private const val MPV_FORMAT_STRING = 1
private const val MPV_EVENT_NONE = 0
private const val MPV_EVENT_SHUTDOWN = 1
private const val MPV_EVENT_END_FILE = 7
private const val MPV_EVENT_FILE_LOADED = 8
private const val MPV_EVENT_PLAYBACK_RESTART = 21
private const val MPV_EVENT_PROPERTY_CHANGE = 22

private const val MPV_END_FILE_REASON_EOF = 0
private const val MPV_END_FILE_REASON_STOP = 2
private const val MPV_END_FILE_REASON_QUIT = 3
private const val MPV_END_FILE_REASON_ERROR = 4
private const val MPV_END_FILE_REASON_REDIRECT = 5

private val OBSERVED_PROPERTIES = listOf(
    "pause",
    "time-pos",
    "duration",
    "demuxer-cache-time",
    "file-format",
    "audio-codec-name",
    "audio-bitrate",
)
