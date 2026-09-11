package org.feeluown.mobile.nucleus

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.desktop.DesktopMpvBackend
import org.feeluown.mobile.desktop.DesktopMpvBackendEvent

/**
 * GraalVM-friendly libmpv transport.
 *
 * The JVM/JNA host and this JNI host both feed the same DesktopMpvPlaybackEngine state machine.
 * JNI is intentionally one-way: Kotlin polls mpv events/properties, so the native library never
 * has to discover or callback into managed classes at runtime.
 */
internal class JniMpvBackend(
    private val listener: (DesktopMpvBackendEvent) -> Unit,
) : DesktopMpvBackend {
    private val closed = AtomicBoolean(false)
    private val handle: Long
    private val eventThread: Thread
    private val lifecycleGate = NucleusMpvLifecycleGate()

    @Volatile
    private var expectedPath: String? = null

    @Volatile
    private var expectedPlaylistEntryId: Long? = null

    @Volatile
    private var polledActivePath: String? = null

    init {
        JniMpvBridgeLoader.ensureLoaded()
        handle = JniMpvApi.nativeCreate()
        check(handle != 0L) { "libmpv mpv_create() returned null" }
        try {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("input-default-bindings", "no")
            setOption("vid", "no")
            setOption("ytdl", "no")
            val audioOutput = System.getProperty("fuoevolve.libmpv.ao")
                ?.takeIf(String::isNotBlank)
                ?: System.getenv("FUOEVOLVE_LIBMPV_AO")?.takeIf(String::isNotBlank)
            audioOutput?.let { setOption("ao", it) }
            checkMpv(JniMpvApi.nativeInitialize(handle), "mpv_initialize")
            AppLogger.i(
                LOG_TAG,
                "JNI libmpv initialized audioOutput=${audioOutput ?: "default"}",
            )
        } catch (throwable: Throwable) {
            JniMpvApi.nativeDestroy(handle)
            throw throwable
        }

        eventThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-jni-libmpv-events",
            block = ::eventLoop,
        )
    }

    override fun load(url: String, headers: Map<String, String>) {
        ensureOpen()
        expectedPath = url
        expectedPlaylistEntryId = null
        polledActivePath = null
        lifecycleGate.reset()
        val perFileOptions = encodeMpvLoadfileOptions(headers)
        try {
            if (perFileOptions.isEmpty()) {
                command("loadfile", url, "replace")
            } else {
                command("loadfile", url, "replace", "-1", perFileOptions)
            }
            // The replace command owns playlist slot 0 synchronously. Prefer that id over the
            // currently-playing position, which may still describe the previous entry while mpv
            // drains queued lifecycle events from a rapid source switch.
            getPropertyString("playlist/0/id")?.toLongOrNull()?.let { playlistEntryId ->
                expectedPlaylistEntryId = playlistEntryId
            }
            AppLogger.i(
                LOG_TAG,
                "JNI loadfile submitted sourceKind=${sourceKind(url)} headers=${headers.size}",
            )
        } catch (throwable: Throwable) {
            expectedPath = null
            expectedPlaylistEntryId = null
            polledActivePath = null
            lifecycleGate.reset()
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
        lifecycleGate.reset()
        command("stop")
    }

    override fun seekTo(positionMs: Long) {
        ensureOpen()
        command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        expectedPath = null
        expectedPlaylistEntryId = null
        polledActivePath = null
        lifecycleGate.reset()
        JniMpvApi.nativeWakeup(handle)
        if (Thread.currentThread() !== eventThread) {
            runCatching { eventThread.join() }
        }
        JniMpvApi.nativeDestroy(handle)
    }

    private fun eventLoop() {
        try {
            var nextPollAtNanos = System.nanoTime()
            while (!closed.get()) {
                JniMpvApi.nativeWaitEvent(handle, EVENT_WAIT_SECONDS)?.let(::dispatchNativeEvent)
                val nowNanos = System.nanoTime()
                if (nowNanos >= nextPollAtNanos) {
                    publishPolledState()
                    nextPollAtNanos = nowNanos + STATE_POLL_INTERVAL_NANOS
                }
            }
        } catch (throwable: Throwable) {
            if (!closed.get()) {
                listener(DesktopMpvBackendEvent.Failure(throwable))
            }
        }
    }

    private fun dispatchNativeEvent(encoded: String) {
        when {
            encoded == "shutdown" -> closed.set(true)
            encoded == "loaded" -> activateExpectedRequestFromFileLoaded()
            encoded == "restart" -> {
                if (playbackRestartMatchesCurrentRequest()) {
                    listener(DesktopMpvBackendEvent.PlaybackRestart)
                }
            }
            encoded.startsWith("start:") -> {
                val playlistEntryId = encoded.substringAfter(':').toLongOrNull() ?: return
                if (startFileMatchesCurrentRequest(playlistEntryId)) {
                    listener(DesktopMpvBackendEvent.StartFile(playlistEntryId))
                }
            }
            encoded.startsWith("end:") -> {
                val fields = encoded.split(':', limit = 4)
                if (fields.size != 4) return
                val playlistEntryId = fields[1].toLongOrNull() ?: return
                val reason = fields[2].toIntOrNull() ?: return
                val error = fields[3].toIntOrNull() ?: 0
                val expectedEntryId = expectedPlaylistEntryId ?: currentPlaylistEntryId()
                val matches = expectedEntryId != null && playlistEntryId == expectedEntryId
                if (!matches) return
                expectedPlaylistEntryId = expectedEntryId
                listener(
                    DesktopMpvBackendEvent.EndFile(
                        playlistEntryId = playlistEntryId,
                        reason = reason,
                        errorMessage = error
                            .takeIf { reason == MPV_END_FILE_REASON_ERROR && it < 0 }
                            ?.let(JniMpvApi::nativeErrorString),
                    ),
                )
            }
        }
    }

    private fun startFileMatchesCurrentRequest(playlistEntryId: Long): Boolean {
        val requestedPath = expectedPath ?: return false
        val expectedEntryId = expectedPlaylistEntryId
        val currentEntryId = currentPlaylistEntryId()
        val queuedEntryId = getPropertyString("playlist/0/id")?.toLongOrNull()
        val idMatches = when {
            expectedEntryId != null -> playlistEntryId == expectedEntryId
            queuedEntryId != null -> playlistEntryId == queuedEntryId
            currentEntryId != null -> playlistEntryId == currentEntryId
            else -> false
        }
        if (!idMatches) return false

        val currentPath = getPropertyString("path")
        val currentPlaylistFilename = currentPlaylistEntryFilename()
        val queuedFilename = getPropertyString("playlist/0/filename")
        val sourceMatches = currentPath == requestedPath ||
            currentPlaylistFilename == requestedPath ||
            queuedFilename == requestedPath
        if (!sourceMatches) return false

        expectedPlaylistEntryId = playlistEntryId
        lifecycleGate.matchStart(playlistEntryId)
        return true
    }

    private fun activateExpectedRequestFromFileLoaded(): Boolean {
        val requestedPath = expectedPath ?: return false
        val playlistEntryId = currentPlaylistEntryId() ?: return false
        if (!lifecycleGate.canAcceptFileLoaded(playlistEntryId)) return false
        if (!currentRequestMatchesEntry(requestedPath, playlistEntryId)) return false
        if (polledActivePath == requestedPath) {
            lifecycleGate.markActivated(playlistEntryId)
            return true
        }

        expectedPlaylistEntryId = playlistEntryId
        polledActivePath = requestedPath
        lifecycleGate.markActivated(playlistEntryId)
        listener(DesktopMpvBackendEvent.FileLoaded(requestedPath, playlistEntryId))
        return true
    }

    private fun playbackRestartMatchesCurrentRequest(): Boolean {
        val requestedPath = expectedPath ?: return false
        val playlistEntryId = currentPlaylistEntryId() ?: return false
        return lifecycleGate.canAcceptPlaybackRestart(playlistEntryId) &&
            currentRequestMatchesEntry(requestedPath, playlistEntryId)
    }

    private fun currentRequestMatchesEntry(requestedPath: String, playlistEntryId: Long): Boolean {
        val currentEntryId = currentPlaylistEntryId() ?: return false
        if (currentEntryId != playlistEntryId) return false
        val currentPath = getPropertyString("path")
        val currentPlaylistFilename = currentPlaylistEntryFilename()
        return currentPath == requestedPath || currentPlaylistFilename == requestedPath
    }

    private fun activateCurrentRequestFromPolling(): Boolean {
        val requestedPath = expectedPath ?: return false
        if (polledActivePath == requestedPath) {
            val currentEntryId = currentPlaylistEntryId()
            if (expectedPlaylistEntryId == null && currentEntryId != null) {
                expectedPlaylistEntryId = currentEntryId
                lifecycleGate.markActivated(currentEntryId)
                listener(DesktopMpvBackendEvent.FileLoaded(requestedPath, currentEntryId))
            } else if (currentEntryId != null) {
                lifecycleGate.markActivated(currentEntryId)
            }
            return true
        }

        val currentEntryId = currentPlaylistEntryId()
        val currentPath = getPropertyString("path")
        val playlistFilename = currentPlaylistEntryFilename()
        val matches = when {
            expectedPlaylistEntryId != null && currentEntryId != null ->
                expectedPlaylistEntryId == currentEntryId &&
                    (currentPath == requestedPath || playlistFilename == requestedPath)
            else -> currentPath == requestedPath || playlistFilename == requestedPath
        }
        if (!matches) return false

        val playlistEntryId = currentEntryId ?: expectedPlaylistEntryId
        if (playlistEntryId != null) {
            expectedPlaylistEntryId = playlistEntryId
            lifecycleGate.markActivated(playlistEntryId)
        }
        polledActivePath = requestedPath
        listener(DesktopMpvBackendEvent.FileLoaded(requestedPath, playlistEntryId))
        return true
    }

    private fun publishPolledState() {
        if (!activateCurrentRequestFromPolling()) return
        POLLED_PROPERTIES.forEach { property ->
            getPropertyString(property)?.let { value ->
                listener(DesktopMpvBackendEvent.Property(property, value))
            }
        }
    }

    private fun currentPlaylistEntryId(): Long? {
        val playingPosition = getPropertyString("playlist-playing-pos")
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        return getPropertyString("playlist/$playingPosition/id")?.toLongOrNull()
    }

    private fun currentPlaylistEntryFilename(): String? {
        val playingPosition = getPropertyString("playlist-playing-pos")
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        return getPropertyString("playlist/$playingPosition/filename")
    }

    private fun setOption(name: String, value: String) {
        checkMpv(JniMpvApi.nativeSetOption(handle, name, value), "set option $name")
    }

    private fun setProperty(name: String, value: String) {
        checkMpv(JniMpvApi.nativeSetProperty(handle, name, value), "set property $name")
    }

    private fun getPropertyString(name: String): String? = JniMpvApi.nativeGetProperty(handle, name)

    private fun command(vararg args: String) {
        checkMpv(JniMpvApi.nativeCommand(handle, args), "command ${args.firstOrNull().orEmpty()}")
    }

    private fun checkMpv(result: Int, operation: String) {
        if (result >= 0) return
        val detail = JniMpvApi.nativeErrorString(result) ?: "error $result"
        throw IllegalStateException("libmpv $operation failed: $detail")
    }

    private fun ensureOpen() {
        check(!closed.get()) { "libmpv JNI backend is closed" }
    }
}

internal object JniMpvApi {
    external fun nativeCreate(): Long
    external fun nativeInitialize(handle: Long): Int
    external fun nativeSetOption(handle: Long, name: String, value: String): Int
    external fun nativeSetProperty(handle: Long, name: String, value: String): Int
    external fun nativeGetProperty(handle: Long, name: String): String?
    external fun nativeCommand(handle: Long, args: Array<out String>): Int
    external fun nativeWaitEvent(handle: Long, timeoutSeconds: Double): String?
    external fun nativeWakeup(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeErrorString(error: Int): String?
}

private object JniMpvBridgeLoader {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val bridge = resolveJniMpvBridge()
                ?: throw UnsatisfiedLinkError(
                    "Nucleus libmpv JNI bridge not found in packaged resources or development build output",
                )
            System.load(bridge.absolutePath)
            loaded = true
            AppLogger.i(LOG_TAG, "loaded JNI bridge ${bridge.absolutePath}")
        }
    }
}

private fun resolveJniMpvBridge(): File? {
    val libraryName = when {
        isWindows() -> "fuoevolve_mpv_jni.dll"
        isMac() -> "libfuoevolve_mpv_jni.dylib"
        else -> "libfuoevolve_mpv_jni.so"
    }
    val resourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })
    return buildList {
        resourcesDir?.let { add(File(it, "native/lib/$libraryName")) }
        add(File(userDir, "desktopNucleusPoc/build/native/mpv-jni/$libraryName"))
        add(File(userDir, "build/native/mpv-jni/$libraryName"))
    }.firstOrNull { it.isFile }
}

private fun encodeMpvLoadfileOptions(headers: Map<String, String>): String {
    val sanitized = headers.mapNotNull { (name, value) ->
        if (name.isBlank() || name.any(::isHeaderLineBreak) || value.any(::isHeaderLineBreak)) {
            null
        } else {
            name to value
        }
    }
    if (sanitized.isEmpty()) return ""

    val userAgent = sanitized
        .firstOrNull { (name, _) -> name.equals("User-Agent", ignoreCase = true) }
        ?.second
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

private fun mpvFixedLength(value: String): String =
    "%${value.toByteArray(StandardCharsets.UTF_8).size}%$value"

private fun isHeaderLineBreak(char: Char): Boolean = char == '\r' || char == '\n'

private fun sourceKind(url: String): String = when {
    url.startsWith("file:", ignoreCase = true) -> "file"
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> "http"
    else -> "other"
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

private fun isMac(): Boolean =
    System.getProperty("os.name").orEmpty().let { name ->
        name.contains("mac", ignoreCase = true) || name.contains("darwin", ignoreCase = true)
    }

private const val EVENT_WAIT_SECONDS = 0.05
private const val STATE_POLL_INTERVAL_NANOS = 250_000_000L
private const val MPV_VOLUME_SCALE = 100.0
private const val MPV_END_FILE_REASON_ERROR = 4
private const val LOG_TAG = "NucleusMpvJni"

private val POLLED_PROPERTIES = listOf(
    "pause",
    "time-pos",
    "duration",
    "demuxer-cache-time",
    "volume",
    "file-format",
    "audio-codec-name",
    "audio-bitrate",
)
