package org.feeluown.mobile.nucleus

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.desktopMpvNativeApi
import org.feeluown.mobile.desktop.DesktopMpvBackend
import org.feeluown.mobile.desktop.DesktopMpvBackendEvent

/**
 * GraalVM-friendly libmpv transport.
 *
 * The desktop host installs the JDK 25 FFM implementation before constructing this backend.
 * Kotlin drains mpv's event queue and observed property changes without native callbacks into
 * managed code.
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
    private var activePath: String? = null

    init {
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
            OBSERVED_PROPERTIES.forEachIndexed { index, property ->
                checkMpv(
                    JniMpvApi.nativeObserveProperty(handle, index.toLong() + 1L, property),
                    "observe property $property",
                )
            }
            AppLogger.i(
                LOG_TAG,
                "FFM libmpv initialized audioOutput=${audioOutput ?: "default"}",
            )
        } catch (throwable: Throwable) {
            JniMpvApi.nativeDestroy(handle)
            throw throwable
        }

        eventThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-ffm-libmpv-events",
            block = ::eventLoop,
        )
    }

    override fun load(url: String, headers: Map<String, String>) {
        ensureOpen()
        expectedPath = url
        expectedPlaylistEntryId = null
        activePath = null
        lifecycleGate.reset()
        val perFileOptions = encodeMpvLoadfileOptions(headers)
        try {
            if (perFileOptions.isEmpty()) {
                command("loadfile", url, "replace")
            } else {
                command("loadfile", url, "replace", "-1", perFileOptions)
            }
            getPropertyString("playlist/0/id")?.toLongOrNull()?.let { playlistEntryId ->
                expectedPlaylistEntryId = playlistEntryId
            }
            AppLogger.i(
                LOG_TAG,
                "FFM loadfile submitted sourceKind=${sourceKind(url)} headers=${headers.size}",
            )
        } catch (throwable: Throwable) {
            expectedPath = null
            expectedPlaylistEntryId = null
            activePath = null
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
        activePath = null
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
        activePath = null
        lifecycleGate.reset()
        JniMpvApi.nativeWakeup(handle)
        if (Thread.currentThread() !== eventThread) {
            runCatching { eventThread.join() }
        }
        JniMpvApi.nativeDestroy(handle)
    }

    private fun eventLoop() {
        try {
            while (!closed.get()) {
                JniMpvApi.nativeWaitObservedEvent(handle, EVENT_WAIT_SECONDS)
                    ?.let(::dispatchNativeEvent)
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
            encoded == "queue-overflow" -> publishObservedSnapshot()
            encoded == "loaded" -> {
                if (activateExpectedRequestFromFileLoaded()) publishObservedSnapshot()
            }
            encoded == "restart" -> {
                if (playbackRestartMatchesCurrentRequest()) {
                    listener(DesktopMpvBackendEvent.PlaybackRestart)
                }
            }
            encoded.startsWith("property:") -> dispatchObservedProperty(encoded)
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

    private fun dispatchObservedProperty(encoded: String) {
        val idSeparator = encoded.indexOf(':')
        val valueSeparator = encoded.indexOf(':', startIndex = idSeparator + 1)
        if (idSeparator < 0 || valueSeparator < 0) return
        val id = encoded.substring(idSeparator + 1, valueSeparator).toIntOrNull() ?: return
        val property = OBSERVED_PROPERTIES.getOrNull(id - 1) ?: return
        if (!activateCurrentRequestFromObservedState()) return
        listener(DesktopMpvBackendEvent.Property(property, encoded.substring(valueSeparator + 1)))
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
        if (activePath == requestedPath) {
            lifecycleGate.markActivated(playlistEntryId)
            return true
        }

        expectedPlaylistEntryId = playlistEntryId
        activePath = requestedPath
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

    private fun activateCurrentRequestFromObservedState(): Boolean {
        val requestedPath = expectedPath ?: return false
        if (activePath == requestedPath) {
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
        activePath = requestedPath
        listener(DesktopMpvBackendEvent.FileLoaded(requestedPath, playlistEntryId))
        return true
    }

    private fun publishObservedSnapshot() {
        if (!activateCurrentRequestFromObservedState()) return
        OBSERVED_PROPERTIES.forEach { property ->
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
        check(!closed.get()) { "libmpv FFM backend is closed" }
    }
}

internal object JniMpvApi {
    private val api get() = desktopMpvNativeApi()

    fun nativeCreate(): Long = api.create()
    fun nativeInitialize(handle: Long): Int = api.initialize(handle)
    fun nativeSetOption(handle: Long, name: String, value: String): Int = api.setOption(handle, name, value)
    fun nativeSetProperty(handle: Long, name: String, value: String): Int = api.setProperty(handle, name, value)
    fun nativeGetProperty(handle: Long, name: String): String? = api.getProperty(handle, name)
    fun nativeCommand(handle: Long, args: Array<out String>): Int = api.command(handle, args)
    fun nativeObserveProperty(handle: Long, replyUserdata: Long, name: String): Int =
        api.observeProperty(handle, replyUserdata, name)
    fun nativeWaitObservedEvent(handle: Long, timeoutSeconds: Double): String? =
        api.waitObservedEvent(handle, timeoutSeconds)
    fun nativeWakeup(handle: Long) = api.wakeup(handle)
    fun nativeDestroy(handle: Long) = api.destroy(handle)
    fun nativeErrorString(error: Int): String? = api.errorString(error)
}

internal fun windowsMpvRuntimeLoadPlan(libraryNames: List<String>): List<String> {
    val dllNames = libraryNames.filter { name -> name.endsWith(".dll", ignoreCase = true) }
    val mpvRuntime = WINDOWS_MPV_RUNTIME_NAMES.firstNotNullOfOrNull { expected ->
        dllNames.firstOrNull { name -> name.equals(expected, ignoreCase = true) }
    } ?: return emptyList()
    val support = dllNames
        .filterNot { name ->
            name.equals(WINDOWS_MPV_BRIDGE_NAME, ignoreCase = true) ||
                name.equals(mpvRuntime, ignoreCase = true)
        }
        .sortedBy(String::lowercase)
    return support + mpvRuntime
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

private const val EVENT_WAIT_SECONDS = 0.05
private const val MPV_VOLUME_SCALE = 100.0
private const val MPV_END_FILE_REASON_ERROR = 4
private const val LOG_TAG = "NucleusMpvFfm"
private const val WINDOWS_MPV_BRIDGE_NAME = "fuoevolve_mpv_jni.dll"
private val WINDOWS_MPV_RUNTIME_NAMES = listOf("libmpv-2.dll", "mpv-2.dll", "mpv.dll")

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
