package org.feeluown.mobile

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_DEBUG_LOG_LINES = 2_000
private const val MAX_DEBUG_LOG_BYTES = 4L * 1024L * 1024L

/** Install as early as possible so native/platform startup diagnostics are available in Settings. */
fun installDesktopDebugLogCapture() {
    DesktopDebugLogCapture.install()
}

internal fun createDesktopDebugLogRepository(): DebugLogRepository = DesktopDebugLogRepository()

private object DesktopDebugLogCapture {
    private val logDirectory
        get() = DesktopAppDirectories.state().resolve("logs")
    val logFile
        get() = logDirectory.resolve("application.log")

    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        runCatching {
            Files.createDirectories(logDirectory)
            val sink = RollingFileOutputStream(
                activeFile = logFile,
                previousFile = logDirectory.resolve("application.previous.log"),
                maxBytes = MAX_DEBUG_LOG_BYTES,
            )
            val originalOut = System.out
            val originalErr = System.err
            System.setOut(PrintStream(TeeOutputStream(originalOut, sink), true, StandardCharsets.UTF_8))
            System.setErr(PrintStream(TeeOutputStream(originalErr, sink), true, StandardCharsets.UTF_8))
            installed = true
            System.err.println("FuoEvolve: desktop debug log capture enabled at $logFile")
        }.onFailure { throwable ->
            System.err.println("FuoEvolve: unable to enable desktop debug log capture: ${throwable.message}")
        }
    }
}

/**
 * A small platform filesystem primitive used by desktop log capture.
 *
 * Rotation happens at write time, so a long-running process cannot grow the active log without
 * bound. Both stdout and stderr share the same instance, making rotation and file writes serialized.
 */
internal class RollingFileOutputStream(
    private val activeFile: Path,
    private val previousFile: Path,
    private val maxBytes: Long,
) : OutputStream() {
    private var activeBytes: Long
    private var file: OutputStream

    init {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        Files.createDirectories(requireNotNull(activeFile.parent))
        if (Files.isRegularFile(activeFile) && Files.size(activeFile) >= maxBytes) {
            Files.move(activeFile, previousFile, StandardCopyOption.REPLACE_EXISTING)
        }
        activeBytes = if (Files.isRegularFile(activeFile)) Files.size(activeFile) else 0L
        file = openActiveFile()
    }

    @Synchronized
    override fun write(value: Int) {
        ensureWritableFile()
        file.write(value)
        activeBytes += 1L
    }

    @Synchronized
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        var position = offset
        var remaining = length
        while (remaining > 0) {
            ensureWritableFile()
            val capacity = maxBytes - activeBytes
            val chunkSize = minOf(remaining.toLong(), capacity).toInt()
            file.write(buffer, position, chunkSize)
            activeBytes += chunkSize.toLong()
            position += chunkSize
            remaining -= chunkSize
        }
    }

    @Synchronized
    override fun flush() {
        file.flush()
    }

    @Synchronized
    override fun close() {
        file.close()
    }

    private fun ensureWritableFile() {
        if (activeBytes < maxBytes) return
        file.flush()
        file.close()
        if (Files.isRegularFile(activeFile)) {
            Files.move(activeFile, previousFile, StandardCopyOption.REPLACE_EXISTING)
        }
        activeBytes = 0L
        file = openActiveFile()
    }

    private fun openActiveFile(): OutputStream = Files.newOutputStream(
        activeFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
        StandardOpenOption.WRITE,
    )
}

private class TeeOutputStream(
    private val console: OutputStream,
    private val file: OutputStream,
) : OutputStream() {
    @Synchronized
    override fun write(value: Int) {
        console.write(value)
        file.write(value)
    }

    @Synchronized
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        console.write(buffer, offset, length)
        file.write(buffer, offset, length)
    }

    @Synchronized
    override fun flush() {
        console.flush()
        file.flush()
    }
}

private class DesktopDebugLogRepository : DebugLogRepository {
    override val isAvailable: Boolean = true

    override suspend fun logLines(): List<String> = withContext(Dispatchers.IO) {
        val file = DesktopDebugLogCapture.logFile
        if (!Files.isRegularFile(file)) return@withContext emptyList()
        Files.readAllLines(file, StandardCharsets.UTF_8)
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .takeLast(MAX_DEBUG_LOG_LINES)
    }

    override suspend fun exportLogFile(lines: List<String>): String {
        if (lines.isEmpty()) return "没有可导出的日志"
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "fuo-evolve-log-$timestamp.txt"
        // The common feature invokes export from its UI scope. Keep JFileChooser on that thread;
        // only log reading itself belongs on Dispatchers.IO.
        val saved = saveDesktopTextFile(
            dialogTitle = "导出应用日志",
            suggestedFileName = fileName,
            filterDescription = "文本日志 (*.txt)",
            extensions = listOf("txt"),
            content = lines.joinToString("\n"),
            onFeedback = {},
        )
        return if (saved) "日志已导出：$fileName" else "已取消导出日志"
    }
}
