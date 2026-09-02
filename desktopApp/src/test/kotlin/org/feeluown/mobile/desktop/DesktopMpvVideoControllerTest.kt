package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMpvVideoControllerTest {
    @Test
    fun softwareRenderSizeCapsLargeViewportsWithoutChangingAspect() {
        val (width, height) = boundedVideoRenderSize(3840, 2160)

        assertTrue(width.toLong() * height <= 1920L * 1080L)
        assertTrue(kotlin.math.abs(width.toDouble() / height - 16.0 / 9.0) < 0.01)
    }

    @Test
    fun splitDashPayloadAddsExternalAudioAsFileLocalOption() {
        val options = encodeVideoLoadfileOptions(
            headers = mapOf("Referer" to "https://www.bilibili.com/"),
            externalAudioUrl = "https://cdn.example/audio.m4s?x=1,2",
        )

        assertTrue(options.contains("http-header-fields="))
        assertTrue(options.contains("audio-files-append="))
        assertTrue(options.contains("audio.m4s?x=1,2"))
    }

    @Test
    fun smallViewportKeepsNativeRenderSize() {
        assertEquals(1280 to 720, boundedVideoRenderSize(1280, 720))
    }
}
