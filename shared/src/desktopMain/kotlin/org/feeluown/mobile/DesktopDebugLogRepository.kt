package org.feeluown.mobile

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_DIAGNOSTIC_LOG_LINES = 2_000

internal fun createDesktopDebugLogRepository(): DebugLogRepository = DesktopDebugLogRepository()

/** Only logs from the three most recent launches are included; unrelated files are ignored. */
internal fun createDesktopDiagnosticsArchive(logDirectory: Path, summary: String): Path {
    val tempFile = Files.createTempFile("FuoEvolve-Diagnostics-", ".zip")
    try {
        ZipOutputStream(Files.newOutputStream(tempFile)).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.txt"))
            zip.write(summary.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            StartupLogFiles.latest(logDirectory).asReversed().forEach { logFile ->
                zip.putNextEntry(ZipEntry(logFile.fileName.toString()))
                Files.newInputStream(logFile).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return tempFile
    } catch (failure: Throwable) {
        Files.deleteIfExists(tempFile)
        throw failure
    }
}

private class DesktopDebugLogRepository : DebugLogRepository {
    override val isAvailable: Boolean = true

    private val logDirectory: Path
        get() = DesktopAppDirectories.state().resolve("logs")

    override suspend fun logLines(): List<String> = withContext(Dispatchers.IO) {
        StartupLogFiles.latest(logDirectory).asReversed()
            .flatMap { file -> Files.readAllLines(file, StandardCharsets.UTF_8) }
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .takeLast(MAX_DIAGNOSTIC_LOG_LINES)
    }

    override suspend fun exportLogFile(lines: List<String>): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "FuoEvolve-Diagnostics-$timestamp.zip"
        val tempFile = withContext(Dispatchers.IO) {
            createDesktopDiagnosticsArchive(logDirectory, diagnosticsSummary())
        }
        return try {
            val savedPath = saveDesktopFile(
                dialogTitle = "导出诊断信息",
                suggestedFileName = fileName,
                filterDescription = "FuoEvolve 诊断文件 (*.zip)",
                extensions = listOf("zip"),
                sourceFile = tempFile,
            )
            if (savedPath == null) {
                "已取消导出诊断信息"
            } else {
                "诊断信息已导出：${Path.of(savedPath).fileName}"
            }
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(tempFile) }
        }
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
