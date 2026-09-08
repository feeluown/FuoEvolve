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
