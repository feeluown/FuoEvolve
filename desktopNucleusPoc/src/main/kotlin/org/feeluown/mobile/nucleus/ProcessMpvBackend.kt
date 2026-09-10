package org.feeluown.mobile.nucleus

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.desktop.DesktopMpvBackend
import org.feeluown.mobile.desktop.DesktopMpvBackendEvent

/**
 * GraalVM-safe libmpv transport.
 *
 * The Native Image process never loads JNA or libmpv itself. A tiny Rust sidecar owns libmpv and
 * exchanges line-delimited JSON commands/events with the shared Kotlin playback state machine.
 */
internal class ProcessMpvBackend(
    private val listener: (DesktopMpvBackendEvent) -> Unit,
    helper: File = resolveNucleusMpvHost()
        ?: throw IllegalStateException("mpv helper not found in packaged desktop resources"),
) : DesktopMpvBackend {
    private val closed = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val writerLock = Any()
    private val diagnosticTail = StringBuilder()
    private val process: Process
    private val writer: java.io.BufferedWriter

    @Volatile
    private var startupFailure: String? = null

    @Volatile
    private var failureReported = false

    init {
        process = ProcessBuilder(helper.absolutePath)
            .redirectErrorStream(false)
            .start()
        writer = process.outputStream.bufferedWriter(Charsets.UTF_8)

        thread(start = true, isDaemon = true, name = "fuoevolve-mpv-host-events") {
            readEvents()
        }
        thread(start = true, isDaemon = true, name = "fuoevolve-mpv-host-stderr") {
            readDiagnostics()
        }

        if (!ready.await(10, TimeUnit.SECONDS)) {
            val detail = diagnosticSnapshot().ifBlank { "no diagnostics" }
            close()
            throw IllegalStateException("mpv helper did not become ready: $detail")
        }
        startupFailure?.let { message ->
            close()
            throw IllegalStateException("mpv helper failed to start: $message")
        }
        if (!process.isAlive) {
            val detail = diagnosticSnapshot().ifBlank { "exit=${runCatching { process.exitValue() }.getOrNull()}" }
            close()
            throw IllegalStateException("mpv helper exited during startup: $detail")
        }
        AppLogger.i(LOG_TAG, "Rust libmpv helper ready")
    }

    override fun load(url: String, headers: Map<String, String>) {
        sendCommand(
            buildJsonObject {
                put("type", "load")
                put("url", url)
                putJsonObject("headers") {
                    headers.forEach { (name, value) -> put(name, value) }
                }
            },
        )
    }

    override fun setPaused(paused: Boolean) {
        sendCommand(
            buildJsonObject {
                put("type", "pause")
                put("paused", paused)
            },
        )
    }

    override fun setVolume(volume: Double) {
        sendCommand(
            buildJsonObject {
                put("type", "volume")
                put("value", volume.coerceIn(0.0, 1.0))
            },
        )
    }

    override fun stop() {
        sendCommand(buildJsonObject { put("type", "stop") })
    }

    override fun seekTo(positionMs: Long) {
        sendCommand(
            buildJsonObject {
                put("type", "seek")
                put("positionMs", positionMs.coerceAtLeast(0L))
            },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(writerLock) {
            runCatching {
                if (process.isAlive) {
                    writer.write("{\"type\":\"close\"}")
                    writer.newLine()
                    writer.flush()
                }
            }
            runCatching { writer.close() }
        }
        if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            process.destroy()
            if (!runCatching { process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) {
                process.destroyForcibly()
            }
        }
    }

    private fun sendCommand(command: kotlinx.serialization.json.JsonObject) {
        check(!closed.get()) { "mpv helper backend is closed" }
        synchronized(writerLock) {
            check(process.isAlive) {
                "mpv helper is not running: ${diagnosticSnapshot().ifBlank { "no diagnostics" }}"
            }
            writer.write(command.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private fun readEvents() {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val event = runCatching { Json.parseToJsonElement(line).jsonObject }
                        .getOrElse { error ->
                            reportFailure(IllegalStateException("Invalid mpv helper event", error))
                            return@forEach
                        }
                    when (event["type"]?.jsonPrimitive?.contentOrNull) {
                        "ready" -> ready.countDown()
                        "startFile" -> event["playlistEntryId"]?.jsonPrimitive?.longOrNull?.let { id ->
                            listener(DesktopMpvBackendEvent.StartFile(id))
                        }
                        "fileLoaded" -> {
                            val path = event["path"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            val entryId = event["playlistEntryId"]
                                ?.takeUnless { it is JsonNull }
                                ?.jsonPrimitive
                                ?.longOrNull
                            listener(DesktopMpvBackendEvent.FileLoaded(path, entryId))
                        }
                        "playbackRestart" -> listener(DesktopMpvBackendEvent.PlaybackRestart)
                        "property" -> {
                            val name = event["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            val value = event["value"]
                                ?.takeUnless { it is JsonNull }
                                ?.jsonPrimitive
                                ?.contentOrNull
                            listener(DesktopMpvBackendEvent.Property(name, value))
                        }
                        "endFile" -> {
                            val entryId = event["playlistEntryId"]?.jsonPrimitive?.longOrNull ?: return@forEach
                            val reason = event["reason"]?.jsonPrimitive?.intOrNull ?: return@forEach
                            val errorMessage = event["errorMessage"]
                                ?.takeUnless { it is JsonNull }
                                ?.jsonPrimitive
                                ?.contentOrNull
                            listener(DesktopMpvBackendEvent.EndFile(entryId, reason, errorMessage))
                        }
                        "failure" -> {
                            val message = event["message"]?.jsonPrimitive?.contentOrNull
                                ?: "Unknown mpv helper failure"
                            if (ready.count > 0L) {
                                startupFailure = message
                                ready.countDown()
                            }
                            reportFailure(IllegalStateException(message))
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (!closed.get()) reportFailure(throwable)
        } finally {
            if (ready.count > 0L) ready.countDown()
            if (!closed.get() && !failureReported) {
                reportFailure(
                    IllegalStateException(
                        "mpv helper exited unexpectedly: ${diagnosticSnapshot().ifBlank { "no diagnostics" }}",
                    ),
                )
            }
        }
    }

    private fun readDiagnostics() {
        runCatching {
            process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    synchronized(diagnosticTail) {
                        diagnosticTail.appendLine(line)
                        if (diagnosticTail.length > MAX_DIAGNOSTIC_CHARS) {
                            diagnosticTail.delete(0, diagnosticTail.length - MAX_DIAGNOSTIC_CHARS)
                        }
                    }
                    AppLogger.d(LOG_TAG, line.take(MAX_LOG_LINE_CHARS))
                }
            }
        }
    }

    private fun diagnosticSnapshot(): String = synchronized(diagnosticTail) {
        diagnosticTail.toString().trim()
    }

    private fun reportFailure(throwable: Throwable) {
        failureReported = true
        AppLogger.e(LOG_TAG, "Rust libmpv helper failure", throwable)
        listener(DesktopMpvBackendEvent.Failure(throwable))
    }

    private companion object {
        const val LOG_TAG = "NucleusMpv"
        const val MAX_DIAGNOSTIC_CHARS = 8 * 1024
        const val MAX_LOG_LINE_CHARS = 1_000
    }
}

internal fun resolveNucleusMpvHost(): File? {
    val executableName = if (isWindowsHost()) "fuoevolve-mpv-host.exe" else "fuoevolve-mpv-host"
    val explicit = System.getenv("FUOEVOLVE_MPV_HOST_PATH")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val resourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })

    val candidates = listOfNotNull(
        explicit,
        resourcesDir?.resolve("native/helpers/$executableName"),
        userDir.resolve("desktopNucleusPoc/native/mpv-host/target/release/$executableName"),
        userDir.resolve("native/mpv-host/target/release/$executableName"),
    )
    candidates.firstOrNull(::isUsableMpvHost)?.let { return it }

    return resourcesDir
        ?.takeIf(File::isDirectory)
        ?.walkTopDown()
        ?.maxDepth(6)
        ?.firstOrNull { candidate -> candidate.name == executableName && isUsableMpvHost(candidate) }
}

private fun isUsableMpvHost(file: File): Boolean = file.isFile && (isWindowsHost() || file.canExecute())

private fun isWindowsHost(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)
