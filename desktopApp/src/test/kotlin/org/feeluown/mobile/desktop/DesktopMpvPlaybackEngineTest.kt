package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType

class DesktopMpvPlaybackEngineTest {
    @Test
    fun playResolvedKeepsLogicalIdentityAndPassesHeaders() {
        lateinit var backend: FakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            FakeDesktopMpvBackend(listener).also { backend = it }
        }
        val logicalTrack = track(id = "netease:1", source = "netease")
        val resolveTrack = track(id = "bilibili:BV1", source = "bilibili")
        val payload = PlaybackPayload(
            url = "https://example.test/audio.m4a",
            title = "Replacement",
            artists = "Artist",
            album = "Album",
            source = "bilibili",
            headers = mapOf("Referer" to "https://www.bilibili.com/", "Cookie" to "SESSDATA=test"),
            durationMs = 123_000L,
            audioQuality = "320k",
            isSmartReplacement = true,
            replacementId = "bilibili:BV1",
            replacementTitle = "Replacement",
            replacementArtists = "Artist",
            replacementSource = "bilibili",
        )

        engine.prepareLoading(logicalTrack)
        engine.playResolved(logicalTrack, resolveTrack, payload)
        backend.emit(DesktopMpvBackendEvent.PlaybackRestart)

        assertEquals("https://example.test/audio.m4a", backend.loadedUrl)
        assertEquals(payload.headers, backend.loadedHeaders)
        assertEquals(PlayerStatus.Playing, engine.state.value.status)
        assertEquals("netease:1", engine.state.value.currentTrack?.id)
        assertEquals("bilibili:BV1", engine.state.value.resolvedSource?.trackId)
        assertTrue(engine.state.value.resolvedSource?.isReplacement == true)
        assertEquals("320k", engine.state.value.audioQuality)
    }

    @Test
    fun observedPropertiesDriveTimelineAndFormatState() {
        lateinit var backend: FakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            FakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track(id = "qqmusic:1", source = "qqmusic")
        engine.play(
            track,
            PlaybackPayload(
                url = "https://example.test/audio.flac",
                title = track.title,
                artists = track.artists,
                album = track.album,
                source = track.source,
            ),
        )

        backend.emit(DesktopMpvBackendEvent.PlaybackRestart)
        backend.emit(DesktopMpvBackendEvent.Property("time-pos", "12.5"))
        backend.emit(DesktopMpvBackendEvent.Property("duration", "200"))
        backend.emit(DesktopMpvBackendEvent.Property("demuxer-cache-time", "42.75"))
        backend.emit(DesktopMpvBackendEvent.Property("file-format", "flac"))
        backend.emit(DesktopMpvBackendEvent.Property("audio-codec-name", "flac"))
        backend.emit(DesktopMpvBackendEvent.Property("audio-bitrate", "921600"))

        val state = engine.state.value
        assertEquals(12_500L, state.positionMs)
        assertEquals(200_000L, state.durationMs)
        assertEquals(42_750L, state.bufferedMs)
        assertEquals("flac", state.audioFormatInfo?.format)
        assertEquals("flac", state.audioFormatInfo?.codec)
        assertEquals(921_600L, state.audioFormatInfo?.averageBitrate)
        assertEquals("libmpv / flac", state.audioDecoderInfo?.name)
    }

    @Test
    fun pauseResumeSeekAndEofAreMapped() {
        lateinit var backend: FakeDesktopMpvBackend
        val engine = DesktopMpvPlaybackEngine { listener ->
            FakeDesktopMpvBackend(listener).also { backend = it }
        }
        val track = track(id = "local:1", source = "local", sourceType = TrackSourceType.LocalMediaStore)
        engine.play(
            track,
            PlaybackPayload(
                url = "/music/test.mp3",
                title = track.title,
                artists = track.artists,
                album = track.album,
                source = track.source,
                durationMs = 100_000L,
            ),
        )
        backend.emit(DesktopMpvBackendEvent.PlaybackRestart)

        engine.pause()
        assertEquals(true, backend.paused)
        assertEquals(PlayerStatus.Paused, engine.state.value.status)

        engine.seekTo(150_000L)
        assertEquals(100_000L, backend.seekPositionMs)
        assertEquals(100_000L, engine.state.value.positionMs)

        engine.resume()
        assertEquals(false, backend.paused)
        assertEquals(PlayerStatus.Playing, engine.state.value.status)

        backend.emit(DesktopMpvBackendEvent.EndFile(reason = 0))
        assertEquals(PlayerStatus.Ended, engine.state.value.status)
    }

    @Test
    fun backendLoadFailurePublishesActionableError() {
        val engine = DesktopMpvPlaybackEngine {
            throw UnsatisfiedLinkError("libmpv.so not found")
        }
        val track = track(id = "netease:2", source = "netease")

        engine.play(
            track,
            PlaybackPayload(
                url = "https://example.test/audio.mp3",
                title = track.title,
                artists = track.artists,
                album = track.album,
                source = track.source,
            ),
        )

        assertEquals(PlayerStatus.Error, engine.state.value.status)
        assertTrue(engine.state.value.errorMessage.orEmpty().contains("libmpv"))
        assertTrue(engine.state.value.errorMessage.orEmpty().contains("FUOEVOLVE_LIBMPV_PATH"))
    }

    @Test
    fun headerFieldsUseMpvListQuotingAndRejectLineInjection() {
        val validHeaders = listOf(
            "Referer: https://www.bilibili.com/",
            "X-Title: 中文",
        )
        val encoded = encodeHeaderFields(
            linkedMapOf(
                "Referer" to "https://www.bilibili.com/",
                "X-Title" to "中文",
                "Injected\nHeader" to "ignored",
                "Cookie" to "ok\r\nbad",
            ),
        )
        val expected = validHeaders.joinToString(",") { header ->
            "%${header.toByteArray(Charsets.UTF_8).size}%$header"
        }

        assertEquals(expected, encoded)
    }

    private fun track(
        id: String,
        source: String,
        sourceType: TrackSourceType = TrackSourceType.Provider,
    ) = MusicTrack(
        id = id,
        title = "Track",
        artists = "Artist",
        album = "Album",
        source = source,
        sourceType = sourceType,
        durationMs = 100_000L,
        providerId = id,
        providerName = source,
    )
}

private class FakeDesktopMpvBackend(
    private val listener: (DesktopMpvBackendEvent) -> Unit,
) : DesktopMpvBackend {
    var loadedUrl: String? = null
    var loadedHeaders: Map<String, String> = emptyMap()
    var paused: Boolean? = null
    var seekPositionMs: Long? = null

    override fun load(url: String, headers: Map<String, String>) {
        loadedUrl = url
        loadedHeaders = headers
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    override fun stop() = Unit

    override fun seekTo(positionMs: Long) {
        seekPositionMs = positionMs
    }

    override fun close() = Unit

    fun emit(event: DesktopMpvBackendEvent) {
        listener(event)
    }
}
