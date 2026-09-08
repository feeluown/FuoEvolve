package org.feeluown.mobile

/** Log levels shared by every FuoEvolve target. */
enum class AppLogLevel {
    Debug,
    Info,
    Warning,
    Error,
}

/** Platform sink installed once during process startup. */
fun interface AppLogSink {
    fun write(level: AppLogLevel, tag: String, message: String, throwableText: String?)
}

/**
 * Single application logging facade.
 *
 * Application code must log through this object rather than Android Log, println, NSLog, or other
 * platform APIs. Sensitive values are redacted before a record reaches any sink, so persisted
 * diagnostics and console output follow the same privacy policy.
 */
object AppLogger {
    private var sink: AppLogSink = AppLogSink { _, _, _, _ -> }

    fun install(sink: AppLogSink) {
        this.sink = sink
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.Debug, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.Info, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.Warning, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.Error, tag, message, throwable)

    fun redact(value: String): String {
        var redacted = value
        SECRET_ASSIGNMENT_PATTERNS.forEach { pattern ->
            redacted = pattern.replace(redacted) { match ->
                "${match.groupValues[1]}=<redacted>"
            }
        }
        redacted = BEARER_PATTERN.replace(redacted, "Bearer <redacted>")
        redacted = URL_SECRET_PATTERN.replace(redacted) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}=<redacted>"
        }
        return redacted
    }

    private fun log(level: AppLogLevel, tag: String, message: String, throwable: Throwable?) {
        val safeMessage = redact(message)
        val safeThrowable = throwable?.stackTraceToString()?.let(::redact)
        sink.write(level, tag, safeMessage, safeThrowable)
    }

    private val SECRET_ASSIGNMENT_PATTERNS = listOf(
        Regex(
            pattern = "(?i)\\b(authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|password|passwd|secret|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+",
        ),
    )
    private val BEARER_PATTERN = Regex("(?i)\\bBearer\\s+[^\\s,;]+")
    private val URL_SECRET_PATTERN = Regex(
        pattern = "(?i)([?&])(token|access_token|refresh_token|key|api_key|signature|sig|auth|code)=[^&#\\s]+",
    )
}
