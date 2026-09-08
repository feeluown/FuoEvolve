package org.feeluown.mobile

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_DIAGNOSTIC_LOG_LINES = 2_000

internal fun createDesktopDebugLogRepository(): DebugLogRepository = DesktopDebugLogRepository()

/**
 * Small desktop filesystem primitive shared by AppLogger and diagnostics export.
 * Rotation happens at write time and keeps only the active and immediately previous file.
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

private class DesktopDebugLogRepository : DebugLogRepository {
    override val isAvailable: Boolean = true

    private val logDirectory: Path
        get() = DesktopAppDirectories.state().resolve("logs")
    private val activeLog: Path
        get() = logDirectory.resolve("application.log")
    private val previousLog: Path
        get() = logDirectory.resolve("application.previous.log")

    override suspend fun logLines(): List<String> = withContext(Dispatchers.IO) {
        listOf(previousLog, activeLog)
            .filter(Files::isRegularFile)
            .flatMap { file -> Files.readAllLines(file, StandardCharsets.UTF_8) }
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .takeLast(MAX_DIAGNOSTIC_LOG_LINES)
    }

    override suspend fun exportLogFile(lines: List<String>): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "FuoEvolve-Diagnostics-$timestamp.zip"
        val tempFile = withContext(Dispatchers.IO) { createDiagnosticsArchive() }
        return try {
            val chooser = JFileChooser().apply {
                dialogTitle = "导出诊断信息"
                selectedFile = File(fileName)
                fileFilter = FileNameExtensionFilter("FuoEvolve 诊断文件 (*.zip)", "zip")
            }
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
                "已取消导出诊断信息"
            } else {
                val selected = chooser.selectedFile
                val destination = if (selected.name.endsWith(".zip", ignoreCase = true)) {
                    selected.toPath()
                } else {
                    selected.toPath().resolveSibling("${selected.name}.zip")
                }
                withContext(Dispatchers.IO) {
                    destination.parent?.let(Files::createDirectories)
                    Files.copy(tempFile, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                "诊断信息已导出：${destination.fileName}"
            }
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(tempFile) }
        }
    }

    private fun createDiagnosticsArchive(): Path {
        val tempFile = Files.createTempFile("FuoEvolve-Diagnostics-", ".zip")
        ZipOutputStream(Files.newOutputStream(tempFile)).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.txt"))
            zip.write(diagnosticsSummary().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            listOf(activeLog, previousLog)
                .filter(Files::isRegularFile)
                .forEach { logFile ->
                    zip.putNextEntry(ZipEntry(logFile.fileName.toString()))
                    Files.newInputStream(logFile).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return tempFile
    }

    private fun diagnosticsSummary(): String = buildString {
        appendLine("FuoEvolve diagnostics")
        appendLine("generatedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("platform=Desktop")
        appendLine("osName=${System.getProperty("os.name").orEmpty()}")
        appendLine("osVersion=${System.getProperty("os.version").orEmpty()}")
        appendLine("osArch=${System.getProperty("os.arch").orEmpty()}")
        appendLine("javaVersion=${System.getProperty("java.version").orEmpty()}")
        appendLine()
        appendLine("Logs are redacted by AppLogger before persistence. Credentials and app databases are not included.")
    }
}
