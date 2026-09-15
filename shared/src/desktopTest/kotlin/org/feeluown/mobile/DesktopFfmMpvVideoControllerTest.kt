package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopFfmMpvVideoControllerTest {
    @Test
    fun videoDecodeModesMapToExpectedLibmpvHwdecOptions() {
        assertEquals("auto-copy", desktopVideoHwdecOption(DesktopVideoDecodeMode.HardwareCompatible))
        assertEquals("auto", desktopVideoHwdecOption(DesktopVideoDecodeMode.HardwareDirect))
        assertEquals("no", desktopVideoHwdecOption(DesktopVideoDecodeMode.Software))
    }

    @Test
    fun directModeUsesWindowsGpuInteropOnly() {
        assertEquals(
            null,
            desktopVideoHwdecInteropOption(DesktopVideoDecodeMode.HardwareCompatible, "Windows 11"),
        )
        assertEquals(
            "d3d11-egl",
            desktopVideoHwdecInteropOption(DesktopVideoDecodeMode.HardwareDirect, "Windows 11"),
        )
        assertEquals(
            null,
            desktopVideoHwdecInteropOption(DesktopVideoDecodeMode.HardwareDirect, "Linux"),
        )
        assertEquals(
            null,
            desktopVideoHwdecInteropOption(DesktopVideoDecodeMode.Software, "Windows 11"),
        )
        assertEquals("x11-exact", desktopVideoNativeDisplayDescription(0x11))
        assertEquals("wayland", desktopVideoNativeDisplayDescription(2))
    }

    @Test
    fun softwareRenderSizeCaps4kAt1080p() {
        assertEquals(1920 to 1080, boundedDesktopFfmVideoRenderSize(3840, 2160))
        assertEquals(1280 to 720, boundedDesktopFfmVideoRenderSize(1280, 720))
        assertEquals(0 to 0, boundedDesktopFfmVideoRenderSize(0, 720))
    }

    @Test
    fun splitDashPayloadAddsExternalAudioAndRequestHeaders() {
        val options = encodeDesktopFfmVideoLoadfileOptions(
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
    fun sourceCandidatesRetryCombinedThenSplitFallbacks() {
        val payload = VideoPlaybackPayload(
            video = ProviderVideo(
                id = "video-1",
                title = "Video",
                providerId = "bilibili",
                providerName = "Bilibili",
            ),
            url = "https://cdn.test/combined-primary.mp4",
            fallbackUrls = listOf(
                "https://cdn.test/combined-backup.mp4",
                "https://cdn.test/combined-primary.mp4",
            ),
            videoUrl = "https://cdn.test/video-primary.m4s",
            audioUrl = "https://cdn.test/audio-primary.m4s",
            fallbackVideoUrls = listOf("https://cdn.test/video-backup.m4s"),
            fallbackAudioUrls = listOf("https://cdn.test/audio-backup.m4s"),
        )

        val candidates = desktopFfmVideoSourceCandidates(payload)

        assertEquals(6, candidates.size)
        assertEquals("https://cdn.test/combined-primary.mp4", candidates[0].url)
        assertEquals("https://cdn.test/combined-backup.mp4", candidates[1].url)
        assertEquals("https://cdn.test/video-primary.m4s", candidates[2].videoUrl)
        assertEquals("https://cdn.test/audio-primary.m4s", candidates[2].audioUrl)
        assertEquals("https://cdn.test/video-backup.m4s", candidates[3].videoUrl)
        assertEquals("https://cdn.test/audio-primary.m4s", candidates[3].audioUrl)
        assertEquals("https://cdn.test/video-primary.m4s", candidates[4].videoUrl)
        assertEquals("https://cdn.test/audio-backup.m4s", candidates[4].audioUrl)
        assertEquals("https://cdn.test/video-backup.m4s", candidates[5].videoUrl)
        assertEquals("https://cdn.test/audio-backup.m4s", candidates[5].audioUrl)
    }

    @Test
    fun endEventParserUsesPlaylistEntryReasonAndError() {
        assertEquals(42L, parseDesktopFfmVideoStartEvent("start:42"))
        assertEquals(null, parseDesktopFfmVideoStartEvent("start:broken"))

        val event = assertNotNull(parseDesktopFfmVideoEndEvent("end:42:4:-13"))
        assertEquals(42L, event.playlistEntryId)
        assertEquals(4, event.reason)
        assertEquals(-13, event.error)
        assertEquals(null, parseDesktopFfmVideoEndEvent("end:broken"))
    }

    @Test
    fun playbackRestartCoversEofIdleAndNearEnd() {
        assertTrue(
            shouldRestartDesktopFfmVideoPlayback(
                reachedEof = true,
                playbackActive = false,
                positionMs = 10_000,
                durationMs = 10_000,
            ),
        )
        assertTrue(
            shouldRestartDesktopFfmVideoPlayback(
                reachedEof = false,
                playbackActive = false,
                positionMs = 3_000,
                durationMs = 10_000,
            ),
        )
        assertTrue(
            shouldRestartDesktopFfmVideoPlayback(
                reachedEof = false,
                playbackActive = true,
                positionMs = 9_600,
                durationMs = 10_000,
            ),
        )
        assertFalse(
            shouldRestartDesktopFfmVideoPlayback(
                reachedEof = false,
                playbackActive = true,
                positionMs = 5_000,
                durationMs = 10_000,
            ),
        )
    }
}
