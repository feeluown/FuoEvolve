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
import org.feeluown.mobile.AppLogger

/** JVM-only libmpv transport. The playback state machine lives in desktopRuntime. */
internal class LibMpvBackend(
    private val listener: (DesktopMpvBackendEvent) -> Unit,
    private val library: MpvNative = loadMpvLibrary(),
) : DesktopMpvBackend {
    private val closed = AtomicBoolean(false)
    private val handle: Pointer
    private val eventThread: Thread

    @Volatile
    private var expectedPath: String? = null

    @Volatile
    private var expectedPlaylistEntryId: Long? = null

    @Volatile
    private var polledActivePath: String? = null

    init {
        configureLibMpvNumericLocale()
        handle = library.mpv_create()
            ?: throw IllegalStateException("libmpv mpv_create() returned null")
        try {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("input-default-bindings", "no")
            setOption("vid", "no")
            setOption("ytdl", "no")
            val audioOutput = System.getProperty("fuoevolve.libmpv.ao")
                ?.takeIf(String::isNotBlank)
                ?: System.getenv("FUOEVOLVE_LIBMPV_AO")?.takeIf(String::isNotBlank)
            AppLogger.i(
                DESKTOP_MPV_LOG_TAG,
                "initializing libmpv audioOutput=${audioOutput ?: "default"}",
            )
            audioOutput?.let { setOption("ao", it) }
            checkMpv(library.mpv_initialize(handle), "mpv_initialize")
            checkMpv(library.mpv_request_log_messages(handle, "warn"), "request log messages")
            OBSERVED_PROPERTIES.forEach { property ->
                checkMpv(
                    library.mpv_observe_property(handle, 0L, property, MPV_FORMAT_STRING),
                    "observe $property",
                )
            }
            AppLogger.i(
                DESKTOP_MPV_LOG_TAG,
                "libmpv initialized observedProperties=${OBSERVED_PROPERTIES.joinToString(",")}",
            )
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
        expectedPath = url
        expectedPlaylistEntryId = null
        polledActivePath = null
        val perFileOptions = encodeMpvLoadfileOptions(headers)
        try {
            if (perFileOptions.isEmpty()) {
                command("loadfile", url, "replace")
            } else {
                // mpv >= 0.38 uses the third loadfile argument as insertion index, so the
                // per-file option list is the fourth argument and requires an explicit -1.
                command("loadfile", url, "replace", "-1", perFileOptions)
            }
            AppLogger.i(
                DESKTOP_MPV_LOG_TAG,
                "loadfile submitted sourceKind=${desktopMpvSourceKind(url)} " +
                    "headers=${headers.size} hasPerFileOptions=${perFileOptions.isNotEmpty()}",
            )
            getPropertyString("playlist/0/id")?.toLongOrNull()?.let { playlistEntryId ->
                expectedPlaylistEntryId = playlistEntryId
            }
        } catch (throwable: Throwable) {
            AppLogger.e(DESKTOP_MPV_LOG_TAG, "backend load failed", throwable)
            expectedPath = null
            expectedPlaylistEntryId = null
            polledActivePath = null
            throw throwable
        }
    }

    override fun setPaused(paused: Boolean) {
        ensureOpen()
        setProperty("pause", if (paused) "yes" else "no")
    }

    override fun setVolume(volume: Double) {
        ensureOpen()
        setProperty("volume", (volume.coerceIn(0.0, 1.0) * MPV_VOLUME_SCALE).toString())
    }

    override fun stop() {
        ensureOpen()
        expectedPath = null
        expectedPlaylistEntryId = null
        polledActivePath = null
        command("stop")
    }

    override fun seekTo(positionMs: Long) {
        ensureOpen()
        command("seek", (positionMs / 1000.0).toString(), "absolute")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        expectedPath = null
        expectedPlaylistEntryId = null
        polledActivePath = null
        library.mpv_wakeup(handle)
        if (Thread.currentThread() !== eventThread) {
            runCatching { eventThread.join() }
        }
        library.mpv_terminate_destroy(handle)
    }

    private fun eventLoop() {
        try {
            var nextPollAtNanos = System.nanoTime()
            while (!closed.get()) {
                val eventPointer = library.mpv_wait_event(handle, MPV_STATE_POLL_INTERVAL_SECONDS)
                val event = MpvNativeEvent(eventPointer)
                when (event.eventId) {
                    MPV_EVENT_NONE -> Unit
                    MPV_EVENT_SHUTDOWN -> break
                    MPV_EVENT_LOG_MESSAGE -> event.data?.let { data ->
                        val logMessage = MpvNativeLogMessage(data)
                        val text = logMessage.text
                            ?.getString(0, StandardCharsets.UTF_8.name())
                            ?.let(::sanitizeMpvLogText)
                            .orEmpty()
                        if (text.isNotBlank()) {
                            val prefix = logMessage.prefix
                                ?.getString(0, StandardCharsets.UTF_8.name())
                                .orEmpty()
                            val level = logMessage.level
                                ?.getString(0, StandardCharsets.UTF_8.name())
                                .orEmpty()
                            val message = "libmpv[$prefix/$level] $text"
                            when (level) {
                                "error", "fatal" -> AppLogger.e(DESKTOP_MPV_LOG_TAG, message)
                                "warn" -> AppLogger.w(DESKTOP_MPV_LOG_TAG, message)
                                else -> AppLogger.d(DESKTOP_MPV_LOG_TAG, message)
                            }
                        }
                    }
                    MPV_EVENT_START_FILE -> event.data?.let { data ->
                        val startFile = MpvNativeStartFile(data)
                        if (startFileMatchesCurrentRequest(startFile.playlistEntryId)) {
                            listener(DesktopMpvBackendEvent.StartFile(startFile.playlistEntryId))
                        }
                    }
                    MPV_EVENT_FILE_LOADED -> activateExpectedRequestFromFileLoaded()
                    MPV_EVENT_PLAYBACK_RESTART -> listener(DesktopMpvBackendEvent.PlaybackRestart)
                    MPV_EVENT_PROPERTY_CHANGE -> event.data?.let { data ->
                        val property = MpvNativeEventProperty(data)
                        dispatchProperty(
                            name = property.name?.getString(0, StandardCharsets.UTF_8.name()).orEmpty(),
                            value = property.stringValue(),
                        )
                    }
                    MPV_EVENT_END_FILE -> event.data?.let { data ->
                        val endFile = MpvNativeEndFile(data)
                        val expectedEntryId = expectedPlaylistEntryId ?: currentPlaylistEntryId()
                        val matches = expectedEntryId != null && endFile.playlistEntryId == expectedEntryId
                        if (matches) {
                            expectedPlaylistEntryId = expectedEntryId
                            AppLogger.i(
                                DESKTOP_MPV_LOG_TAG,
                                "event END_FILE entry=${endFile.playlistEntryId} " +
                                    "reason=${endFile.reason} error=${endFile.error}",
                            )
                            listener(
                                DesktopMpvBackendEvent.EndFile(
                                    playlistEntryId = endFile.playlistEntryId,
                                    reason = endFile.reason,
                                    errorMessage = endFile.error
                                        .takeIf { endFile.reason == MPV_END_FILE_REASON_ERROR && it < 0 }
                                        ?.let(library::mpv_error_string),
                                ),
                            )
                        }
                    }
                }

                val nowNanos = System.nanoTime()
                if (nowNanos >= nextPollAtNanos) {
                    publishPolledState()
                    nextPollAtNanos = nowNanos + MPV_STATE_POLL_INTERVAL_NANOS
                }
            }
        } catch (throwable: Throwable) {
            if (!closed.get()) {
                AppLogger.e(DESKTOP_MPV_LOG_TAG, "backend event loop failed", throwable)
                listener(DesktopMpvBackendEvent.Failure(throwable))
            }
        }
    }

    private fun startFileMatchesCurrentRequest(playlistEntryId: Long): Boolean {
        if (expectedPath == null) return false
        val expectedEntryId = expectedPlaylistEntryId
        val accepted = expectedEntryId == null || playlistEntryId == expectedEntryId
        if (accepted && expectedEntryId == null) expectedPlaylistEntryId = playlistEntryId
        return accepted
    }

    private fun activateExpectedRequestFromFileLoaded(): Boolean {
        val requestedPath = expectedPath ?: return false
        if (polledActivePath == requestedPath) return true
        val playlistEntryId = expectedPlaylistEntryId ?: currentPlaylistEntryId()
        if (playlistEntryId != null) expectedPlaylistEntryId = playlistEntryId
        polledActivePath = requestedPath
        listener(
            DesktopMpvBackendEvent.FileLoaded(
                path = requestedPath,
                playlistEntryId = playlistEntryId,
            ),
        )
        return true
    }

    private fun activateCurrentRequestFromPolling(): Boolean {
        val requestedPath = expectedPath ?: return false
        if (polledActivePath == requestedPath) {
            if (expectedPlaylistEntryId == null) {
                currentPlaylistEntryId()?.let { playlistEntryId ->
                    expectedPlaylistEntryId = playlistEntryId
                    listener(
                        DesktopMpvBackendEvent.FileLoaded(
                            path = requestedPath,
                            playlistEntryId = playlistEntryId,
                        ),
                    )
                }
            }
            return true
        }

        val currentPlaylistEntryId = currentPlaylistEntryId()
        val currentPath = getPropertyString("path")
        val currentPlaylistFilename = currentPlaylistEntryFilename()
        val matches = desktopMpvSourceMatchesRequest(
            requestedPath = requestedPath,
            expectedPlaylistEntryId = expectedPlaylistEntryId,
            currentPlaylistEntryId = currentPlaylistEntryId,
            currentPath = currentPath,
            currentPlaylistFilename = currentPlaylistFilename,
        )
        if (!matches) return false

        val playlistEntryId = currentPlaylistEntryId ?: expectedPlaylistEntryId
        if (playlistEntryId != null) expectedPlaylistEntryId = playlistEntryId
        polledActivePath = requestedPath
        listener(
            DesktopMpvBackendEvent.FileLoaded(
                path = requestedPath,
                playlistEntryId = playlistEntryId,
            ),
        )
        return true
    }

    private fun currentPlaylistEntryId(): Long? {
        val playingPosition = currentPlaylistPlayingPosition() ?: return null
        return getPropertyString("playlist/$playingPosition/id")?.toLongOrNull()
    }

    private fun currentPlaylistEntryFilename(): String? {
        val playingPosition = currentPlaylistPlayingPosition() ?: return null
        return getPropertyString("playlist/$playingPosition/filename")
    }

    private fun currentPlaylistPlayingPosition(): Int? = getPropertyString("playlist-playing-pos")
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }

    private fun publishPolledState() {
        if (!activateCurrentRequestFromPolling()) return
        POLLED_PROPERTIES.forEach { property ->
            getPropertyString(property)?.let { value ->
                dispatchProperty(property, value)
            }
        }
    }

    private fun dispatchProperty(name: String, value: String?) {
        listener(DesktopMpvBackendEvent.Property(name, value))
    }

    private fun getPropertyString(name: String): String? {
        val value = library.mpv_get_property_string(handle, name) ?: return null
        return try {
            value.getString(0, StandardCharsets.UTF_8.name())
        } finally {
            library.mpv_free(value)
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

internal fun desktopMpvSourceMatchesRequest(
    requestedPath: String,
    expectedPlaylistEntryId: Long?,
    currentPlaylistEntryId: Long?,
    currentPath: String?,
    currentPlaylistFilename: String?,
): Boolean {
    if (expectedPlaylistEntryId != null && currentPlaylistEntryId != null) {
        return expectedPlaylistEntryId == currentPlaylistEntryId
    }
    return currentPath == requestedPath || currentPlaylistFilename == requestedPath
}

internal interface MpvNative : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer?)
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String?
}

internal class MpvNativeEvent(pointer: Pointer) : Structure(pointer) {
    @JvmField var eventId: Int = 0
    @JvmField var error: Int = 0
    @JvmField var replyUserdata: Long = 0L
    @JvmField var data: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf("eventId", "error", "replyUserdata", "data")

    init {
        read()
    }
}

internal class MpvNativeStartFile(pointer: Pointer) : Structure(pointer) {
    @JvmField var playlistEntryId: Long = 0L

    override fun getFieldOrder(): List<String> = listOf("playlistEntryId")

    init {
        read()
    }
}

internal class MpvNativeEventProperty(pointer: Pointer) : Structure(pointer) {
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

internal class MpvNativeLogMessage(pointer: Pointer) : Structure(pointer) {
    @JvmField var prefix: Pointer? = null
    @JvmField var level: Pointer? = null
    @JvmField var text: Pointer? = null
    @JvmField var logLevel: Int = 0

    override fun getFieldOrder(): List<String> = listOf("prefix", "level", "text", "logLevel")

    init {
        read()
    }
}

internal class MpvNativeEndFile(pointer: Pointer) : Structure(pointer) {
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
            return Native.load(candidate, MpvNative::class.java).also {
                AppLogger.i(DESKTOP_MPV_LOG_TAG, "libmpv loaded candidate=$candidate")
            }
        } catch (throwable: Throwable) {
            lastFailure = throwable
        }
    }
    AppLogger.e(
        DESKTOP_MPV_LOG_TAG,
        "libmpv load failed candidates=${candidates.joinToString()}",
        lastFailure,
    )
    throw IllegalStateException(
        "Unable to load libmpv from ${candidates.joinToString()}",
        lastFailure,
    )
}

private interface CLocaleNative : Library {
    fun setlocale(category: Int, locale: String): String?
}

private val cLocaleNative: CLocaleNative by lazy {
    Native.load(Platform.C_LIBRARY_NAME, CLocaleNative::class.java)
}

internal fun configureLibMpvNumericLocale() {
    val lcNumeric = when {
        Platform.isLinux() -> 1
        Platform.isMac() || Platform.isWindows() -> 4
        else -> return
    }
    check(cLocaleNative.setlocale(lcNumeric, "C") != null) {
        "Failed to set LC_NUMERIC=C for libmpv"
    }
}

private fun desktopMpvSourceKind(url: String): String = when {
    url.startsWith("file:", ignoreCase = true) -> "file"
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> "http"
    else -> "other"
}

private fun sanitizeMpvLogText(value: String): String = value
    .replace(Regex("https?://\\S+"), "<url>")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(1_000)

internal fun encodeMpvLoadfileOptions(headers: Map<String, String>): String {
    val sanitized = headers.mapNotNull { (name, value) ->
        if (name.isBlank() || name.any(::isHeaderLineBreak) || value.any(::isHeaderLineBreak)) {
            null
        } else {
            name to value
        }
    }
    if (sanitized.isEmpty()) return ""

    val userAgent = sanitized.firstOrNull { (name, _) -> name.equals("User-Agent", ignoreCase = true) }?.second
    val headerFields = sanitized
        .filterNot { (name, _) -> name.equals("User-Agent", ignoreCase = true) }
        .map { (name, value) -> escapeMpvStringListItem("$name: $value") }
        .joinToString(",")

    return buildList {
        userAgent?.let { add("user-agent=${mpvFixedLength(it)}") }
        if (headerFields.isNotEmpty()) {
            add("http-header-fields=${mpvFixedLength(headerFields)}")
        }
    }.joinToString(",")
}

private fun escapeMpvStringListItem(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            ',' -> append("\\,")
            else -> append(char)
        }
    }
}

internal fun encodeHeaderFields(headers: Map<String, String>): String = headers
    .mapNotNull { (name, value) ->
        if (name.isBlank() || name.any(::isHeaderLineBreak) || value.any(::isHeaderLineBreak)) {
            null
        } else {
            "$name: $value"
        }
    }
    .joinToString(",") { header -> mpvFixedLength(header) }

private fun mpvFixedLength(value: String): String {
    val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
    return "%$byteLength%$value"
}

private fun isHeaderLineBreak(char: Char): Boolean = char == '\r' || char == '\n'

private const val MPV_FORMAT_STRING = 1
private const val MPV_EVENT_NONE = 0
private const val MPV_EVENT_SHUTDOWN = 1
private const val MPV_EVENT_LOG_MESSAGE = 2
private const val MPV_EVENT_START_FILE = 6
private const val MPV_EVENT_END_FILE = 7
private const val MPV_EVENT_FILE_LOADED = 8
private const val MPV_EVENT_PLAYBACK_RESTART = 21
private const val MPV_EVENT_PROPERTY_CHANGE = 22
private const val MPV_END_FILE_REASON_ERROR = 4
private const val MPV_VOLUME_SCALE = 100.0
private const val MPV_STATE_POLL_INTERVAL_SECONDS = 0.25
private const val MPV_STATE_POLL_INTERVAL_NANOS = 250_000_000L
private const val DESKTOP_MPV_LOG_TAG = "DesktopMpv"

private val OBSERVED_PROPERTIES = listOf(
    "pause",
    "time-pos",
    "duration",
    "demuxer-cache-time",
    "volume",
    "file-format",
    "audio-codec-name",
    "audio-bitrate",
)

private val POLLED_PROPERTIES = listOf(
    "pause",
    "time-pos",
    "duration",
    "demuxer-cache-time",
    "volume",
)
