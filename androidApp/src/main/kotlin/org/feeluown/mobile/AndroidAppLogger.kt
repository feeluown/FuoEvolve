package org.feeluown.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_APP_LOG_BYTES = 4L * 1024L * 1024L

internal object AndroidAppLogFiles {
    fun directory(context: Context): File = File(context.filesDir, "logs")
    fun active(context: Context): File = File(directory(context), "application.log")
    fun previous(context: Context): File = File(directory(context), "application.previous.log")
}

/** Install before constructing the application container so startup failures are persisted too. */
internal fun installAndroidAppLogger(context: Context) {
    AppLogger.install(AndroidAppLogSink(context.applicationContext))
    AppLogger.i("AppLogger", "Android application logging initialized")
}

private class AndroidAppLogSink(
    context: Context,
) : AppLogSink {
    private val activeFile = AndroidAppLogFiles.active(context)
    private val previousFile = AndroidAppLogFiles.previous(context)
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var output: FileOutputStream

    init {
        activeFile.parentFile?.mkdirs()
        if (activeFile.isFile && activeFile.length() >= MAX_APP_LOG_BYTES) rotate()
        output = FileOutputStream(activeFile, true)
    }

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
            if (activeFile.length() + bytes.size > MAX_APP_LOG_BYTES) {
                output.flush()
                output.close()
                rotate()
                output = FileOutputStream(activeFile, true)
            }
            output.write(bytes)
            output.flush()
        }.onFailure { failure ->
            Log.e("AppLogger", "Unable to persist application log: ${failure.message.orEmpty()}")
        }
    }

    private fun rotate() {
        if (previousFile.exists()) previousFile.delete()
        if (activeFile.exists() && !activeFile.renameTo(previousFile)) {
            activeFile.copyTo(previousFile, overwrite = true)
            activeFile.delete()
        }
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
