package org.feeluown.mobile

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_DEBUG_LOG_LINES = 2_000

class AndroidDebugLogRepository(
    private val context: Context,
    override val isAvailable: Boolean,
) : DebugLogRepository {
    override suspend fun logLines(): List<String> {
        if (!isAvailable) return emptyList()
        return withContext(Dispatchers.IO) {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "--pid=${Process.myPid()}",
                    "*:D",
                ),
            )
            try {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val error = process.errorStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw IllegalStateException(error.ifBlank { "logcat failed: $exitCode" })
                }
                output.lines()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .takeLast(MAX_DEBUG_LOG_LINES)
            } finally {
                process.destroy()
            }
        }
    }

    override suspend fun exportLogFile(lines: List<String>): String {
        if (!isAvailable || lines.isEmpty()) return "没有可导出的日志"
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "debug-logs").also { it.mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "fuo-evolve-log-$timestamp.txt")
            file.writeText(lines.joinToString(separator = "\n"))
            file
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(sendIntent, "导出应用日志")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return "已准备导出日志文件：${file.name}"
    }
}
