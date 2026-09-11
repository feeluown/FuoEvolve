package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopJniMpvVideoControllerTest {
    @Test
    fun softwareRenderSizeCaps4kAt1080p() {
        assertEquals(1920 to 1080, boundedDesktopJniVideoRenderSize(3840, 2160))
        assertEquals(1280 to 720, boundedDesktopJniVideoRenderSize(1280, 720))
        assertEquals(0 to 0, boundedDesktopJniVideoRenderSize(0, 720))
    }

    @Test
    fun splitDashPayloadAddsExternalAudioAndRequestHeaders() {
        val options = encodeDesktopJniVideoLoadfileOptions(
            headers = mapOf(
                "Referer" to "https://www.bilibili.com/",
                "User-Agent" to "FuoEvolve",
            ),
            externalAudioUrl = "https://example.test/audio.m4s",
        )

        assertTrue(options.contains("user-agent="))
        assertTrue(options.contains("http-header-fields="))
        assertTrue(options.contains("Referer"))
        assertTrue(options.contains("audio-files-append="))
        assertTrue(options.contains("https://example.test/audio.m4s"))
    }

    @Test
    fun endEventParserUsesReasonAndErrorAfterPlaylistEntryId() {
        val event = assertNotNull(parseDesktopJniVideoEndEvent("end:42:4:-13"))
        assertEquals(4, event.reason)
        assertEquals(-13, event.error)
        assertEquals(null, parseDesktopJniVideoEndEvent("end:broken"))
    }
}
