package org.feeluown.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_DIAGNOSTIC_LOG_LINES = 2_000

/**
 * Compatibility adapter for the old debug-log feature contract.
 *
 * The UI no longer exposes raw logs; this repository now reads AppLogger's rolling files and
 * exports a user-shareable diagnostics archive. Availability is intentionally enabled in release
 * builds as diagnostics are a support feature rather than a developer-only tool.
 */
class AndroidDebugLogRepository(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") legacyDebuggableOnly: Boolean,
) : DebugLogRepository {
    override val isAvailable: Boolean = true

    override suspend fun logLines(): List<String> = withContext(Dispatchers.IO) {
        listOf(AndroidAppLogFiles.previous(context), AndroidAppLogFiles.active(context))
            .filter(File::isFile)
            .flatMap { file -> file.readLines(Charsets.UTF_8) }
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .takeLast(MAX_DIAGNOSTIC_LOG_LINES)
    }

    override suspend fun exportLogFile(lines: List<String>): String {
        val archive = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "diagnostics").also { it.mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val archive = File(dir, "FuoEvolve-Diagnostics-$timestamp.zip")
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostics.txt"))
                zip.write(diagnosticsSummary().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                listOf(AndroidAppLogFiles.active(context), AndroidAppLogFiles.previous(context))
                    .filter(File::isFile)
                    .forEach { logFile ->
                        zip.putNextEntry(ZipEntry(logFile.name))
                        logFile.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            archive
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, archive.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(sendIntent, "导出诊断信息")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return "已准备导出诊断信息：${archive.name}"
    }

    @Suppress("DEPRECATION")
    private fun diagnosticsSummary(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        return buildString {
            appendLine("FuoEvolve diagnostics")
            appendLine("generatedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("package=${context.packageName}")
            appendLine("versionName=${packageInfo.versionName.orEmpty()}")
            appendLine("versionCode=$versionCode")
            appendLine("platform=Android")
            appendLine("androidRelease=${Build.VERSION.RELEASE.orEmpty()}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("manufacturer=${Build.MANUFACTURER.orEmpty()}")
            appendLine("model=${Build.MODEL.orEmpty()}")
            appendLine("device=${Build.DEVICE.orEmpty()}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("Logs are redacted by AppLogger before persistence. Credentials and app databases are not included.")
        }
    }
}
