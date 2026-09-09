package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLoggerTest {
    @Test
    fun redactsCredentialsBeforeTheyReachSink() {
        var record: CapturedRecord? = null
        AppLogger.install(AppLogSink { level, tag, message, throwableText ->
            record = CapturedRecord(level, tag, message, throwableText)
        })

        AppLogger.e(
            tag = "Auth",
            message = "Authorization: Bearer abc.def access_token=secret-value url=https://x.test?a=1&signature=signed",
            throwable = IllegalStateException("refresh_token=refresh-secret"),
        )

        val captured = requireNotNull(record)
        assertEquals(AppLogLevel.Error, captured.level)
        assertEquals("Auth", captured.tag)
        assertFalse(captured.message.contains("abc.def"))
        assertFalse(captured.message.contains("secret-value"))
        assertFalse(captured.message.contains("signed"))
        assertTrue(captured.message.contains("<redacted>"))
        assertFalse(captured.throwableText.orEmpty().contains("refresh-secret"))
    }

    @Test
    fun redactsStructuredCredentials() {
        val redacted = AppLogger.redact(
            """payload={"access_token":"json-secret","refresh_token":"json-refresh","password":"json-password"}""",
        )

        assertFalse(redacted.contains("json-secret"))
        assertFalse(redacted.contains("json-refresh"))
        assertFalse(redacted.contains("json-password"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun redactsEntireCookieHeader() {
        val redacted = AppLogger.redact(
            "Cookie: first=value; SESSDATA=cookie-secret; MUSIC_U=music-secret\nrequestId=123",
        )

        assertFalse(redacted.contains("first=value"))
        assertFalse(redacted.contains("cookie-secret"))
        assertFalse(redacted.contains("music-secret"))
        assertTrue(redacted.contains("Cookie=<redacted>"))
        assertTrue(redacted.contains("requestId=123"))
    }

    @Test
    fun redactsSensitiveUrlParameters() {
        val redacted = AppLogger.redact(
            "url=https://x.test?a=1&access_token=url-secret&signature=signed-value requestId=123",
        )

        assertFalse(redacted.contains("url-secret"))
        assertFalse(redacted.contains("signed-value"))
        assertTrue(redacted.contains("a=1"))
        assertTrue(redacted.contains("requestId=123"))
    }

    @Test
    fun keepsNonSensitiveContext() {
        assertEquals(
            "playback trackId=123 provider=netease",
            AppLogger.redact("playback trackId=123 provider=netease"),
        )
    }

    private data class CapturedRecord(
        val level: AppLogLevel,
        val tag: String,
        val message: String,
        val throwableText: String?,
    )
}
