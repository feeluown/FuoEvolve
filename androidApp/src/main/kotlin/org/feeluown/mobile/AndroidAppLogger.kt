package org.feeluown.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private const val MAX_APP_LOG_BYTES = 4L * 1024L * 1024L

internal object AndroidAppLogFiles {
    private const val MAX_STORED_STARTUPS = 10
    const val EXPORT_STARTUPS = 3
    private val namePattern = Regex("application-\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}\\.log")
    private var lastStartMillis = 0L

    fun directory(context: Context): File = File(context.filesDir, "logs")

    fun latest(context: Context, limit: Int = EXPORT_STARTUPS): List<File> {
        require(limit >= 0)
        return directory(context).listFiles()
            .orEmpty()
            .filter { it.isFile && namePattern.matches(it.name) }
            .sortedByDescending { it.name }
            .take(limit)
    }

    @Synchronized
    fun start(context: Context): File {
        val directory = directory(context).also { check(it.isDirectory || it.mkdirs()) }
        lastStartMillis = maxOf(System.currentTimeMillis(), lastStartMillis + 1)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(lastStartMillis))
        var active: File
        do {
            active = File(directory, "application-$timestamp-${UUID.randomUUID().toString().take(8)}.log")
        } while (!active.createNewFile())
        latest(context, Int.MAX_VALUE).drop(MAX_STORED_STARTUPS).forEach { it.delete() }
        return active
    }
}

/** Install before constructing the application container so startup failures are persisted too. */
internal fun installAndroidAppLogger(context: Context) {
    AppLogger.install(AndroidAppLogSink(context.applicationContext))
    AppLogger.i("AppLogger", "Android application logging initialized")
}

private class AndroidAppLogSink(
    context: Context,
) : AppLogSink {
    private val activeFile = AndroidAppLogFiles.start(context)
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var output = FileOutputStream(activeFile, true)
    private var written = activeFile.length()

    @Synchronized
    override fun write(level: AppLogLevel, tag: String, message: String, throwableText: String?) {
        val text = buildString {
            append(formatter.format(Date()))
            append(' ')
            append(level.code)
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (!throwableText.isNullOrBlank()) {
                append('\n')
                append(throwableText)
            }
            append('\n')
        }
        val consoleText = if (throwableText.isNullOrBlank()) message else "$message\n$throwableText"
        Log.println(level.androidPriority, tag, consoleText)

        runCatching {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size.toLong() >= MAX_APP_LOG_BYTES) {
                replaceContents(bytes.copyOfRange(bytes.size - MAX_APP_LOG_BYTES.toInt(), bytes.size))
            } else {
                if (written + bytes.size > MAX_APP_LOG_BYTES) compactFor(bytes.size)
                output.write(bytes)
                written += bytes.size
            }
            output.flush()
        }.onFailure { failure ->
            Log.e("AppLogger", "Unable to persist application log: ${failure.message.orEmpty()}")
        }
    }

    private fun compactFor(incoming: Int) {
        output.flush()
        output.close()
        val previous = activeFile.readBytes()
        val keep = minOf(MAX_APP_LOG_BYTES - incoming, MAX_APP_LOG_BYTES / 2).toInt()
        var start = (previous.size - keep).coerceAtLeast(0)
        while (start > 0 && start < previous.size && previous[start - 1] != '\n'.code.toByte()) start++
        replaceClosedContents(previous.copyOfRange(start, previous.size))
    }

    private fun replaceContents(bytes: ByteArray) {
        output.flush()
        output.close()
        replaceClosedContents(bytes)
    }

    private fun replaceClosedContents(bytes: ByteArray) {
        FileOutputStream(activeFile, false).use { it.write(bytes) }
        written = bytes.size.toLong()
        output = FileOutputStream(activeFile, true)
    }
}

private val AppLogLevel.code: Char
    get() = when (this) {
        AppLogLevel.Debug -> 'D'
        AppLogLevel.Info -> 'I'
        AppLogLevel.Warning -> 'W'
        AppLogLevel.Error -> 'E'
    }

private val AppLogLevel.androidPriority: Int
    get() = when (this) {
        AppLogLevel.Debug -> Log.DEBUG
        AppLogLevel.Info -> Log.INFO
        AppLogLevel.Warning -> Log.WARN
        AppLogLevel.Error -> Log.ERROR
    }
