package org.feeluown.mobile

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DESKTOP_APP_LOG_MAX_BYTES = 4L * 1024L * 1024L

fun installDesktopAppLogger() {
    val logTarget = runCatching {
        val logDirectory = DesktopAppDirectories.state().resolve("logs")
        val logFile = logDirectory.resolve("application.log")
        val previousLogFile = logDirectory.resolve("application.previous.log")
        logFile to RollingFileOutputStream(
            activeFile = logFile,
            previousFile = previousLogFile,
            maxBytes = DESKTOP_APP_LOG_MAX_BYTES,
        )
    }.onFailure { failure ->
        System.err.println(
            "FuoEvolve: unable to initialize persisted application logging; " +
                "continuing with console logging: ${failure.message.orEmpty()}",
        )
    }.getOrNull()

    AppLogger.install(DesktopAppLogSink(logTarget?.second))
    if (logTarget != null) {
        AppLogger.i("AppLogger", "Desktop application logging initialized at ${logTarget.first}")
    } else {
        AppLogger.w("AppLogger", "Persisted desktop logging unavailable; using console logging only")
    }
}

private class DesktopAppLogSink(
    private val fileSink: RollingFileOutputStream?,
) : AppLogSink {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    override fun write(level: AppLogLevel, tag: String, message: String, throwableText: String?) {
        val line = buildString {
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
        val console = if (level == AppLogLevel.Warning || level == AppLogLevel.Error) System.err else System.out
        console.print(line)
        val persistentSink = fileSink ?: return
        runCatching {
            val bytes = line.toByteArray(StandardCharsets.UTF_8)
            persistentSink.write(bytes)
            persistentSink.flush()
        }.onFailure { failure ->
            System.err.println("FuoEvolve: unable to persist application log: ${failure.message.orEmpty()}")
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
