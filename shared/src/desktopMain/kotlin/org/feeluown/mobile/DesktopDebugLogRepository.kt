package org.feeluown.mobile

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
            rotateIfNeeded()
            val sink = Files.newOutputStream(
                logFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
            ).buffered()
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

    private fun rotateIfNeeded() {
        if (!Files.isRegularFile(logFile) || Files.size(logFile) < MAX_DEBUG_LOG_BYTES) return
        val previous = logDirectory.resolve("application.previous.log")
        Files.move(logFile, previous, StandardCopyOption.REPLACE_EXISTING)
    }
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
            .asSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .takeLast(MAX_DEBUG_LOG_LINES)
            .toList()
    }

    override suspend fun exportLogFile(lines: List<String>): String {
        if (lines.isEmpty()) return "没有可导出的日志"
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "fuo-evolve-log-$timestamp.txt"
        val saved = withContext(Dispatchers.IO) {
            saveDesktopTextFile(
                dialogTitle = "导出应用日志",
                suggestedFileName = fileName,
                filterDescription = "文本日志 (*.txt)",
                extensions = listOf("txt"),
                content = lines.joinToString("\n"),
                onFeedback = {},
            )
        }
        return if (saved) "日志已导出：$fileName" else "已取消导出日志"
    }
}
