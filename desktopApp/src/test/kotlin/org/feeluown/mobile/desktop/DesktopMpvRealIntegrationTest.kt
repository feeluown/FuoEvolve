package org.feeluown.mobile.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackState
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType

class DesktopMpvRealIntegrationTest {
    @Test
    fun realLibMpvPublishesPlayingStateAndAdvancingTimeline() {
        if (!realLibMpvTestEnabled()) return

        val wav = createSilentWav(durationMs = 2_000)
        val engine = DesktopMpvPlaybackEngine()
        try {
            val track = MusicTrack(
                id = "local:real-libmpv-smoke",
                title = "Real libmpv smoke test",
                artists = "FuoEvolve",
                album = "CI",
                source = "local",
                sourceType = TrackSourceType.LocalMediaStore,
                durationMs = 2_000L,
                providerId = "local:real-libmpv-smoke",
                providerName = "local",
            )
            engine.play(
                track,
                PlaybackPayload(
                    url = wav.toAbsolutePath().toString(),
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    source = track.source,
                    durationMs = track.durationMs,
                ),
            )

            val playing = awaitPlaying(engine)

            assertEquals(PlayerStatus.Playing, playing.status)
            assertTrue(playing.positionMs >= 100L)
            assertTrue(playing.durationMs >= 1_500L)
        } finally {
            engine.close()
            Files.deleteIfExists(wav)
        }
    }

    @Test
    fun realLibMpvSwitchesPhysicalSourceAndCarriesReplacementHeaders() {
        if (!realLibMpvTestEnabled()) return

        val originalWav = createSilentWav(durationMs = 3_000)
        val replacementWav = createSilentWav(durationMs = 2_000)
        val replacementServer = serveWavWithRequiredHeaders(replacementWav)
        val engine = DesktopMpvPlaybackEngine()
        try {
            val logicalTrack = MusicTrack(
                id = "netease:logical-track",
                title = "Logical track",
                artists = "Original artist",
                album = "Original album",
                source = "netease",
                sourceType = TrackSourceType.Provider,
                durationMs = 3_000L,
                providerId = "netease:logical-track",
                providerName = "Netease",
            )
            engine.play(
                logicalTrack,
                PlaybackPayload(
                    url = originalWav.toAbsolutePath().toString(),
                    title = logicalTrack.title,
                    artists = logicalTrack.artists,
                    album = logicalTrack.album,
                    source = logicalTrack.source,
                    durationMs = logicalTrack.durationMs,
                ),
            )
            awaitPlaying(engine)

            engine.prepareLoading(logicalTrack)
            engine.playResolved(
                logicalTrack = logicalTrack,
                resolveTrack = logicalTrack,
                payload = PlaybackPayload(
                    url = replacementServer.url,
                    title = logicalTrack.title,
                    artists = logicalTrack.artists,
                    album = logicalTrack.album,
                    source = "bilibili",
                    headers = mapOf(
                        "Referer" to REQUIRED_REFERER,
                        "User-Agent" to REQUIRED_USER_AGENT,
                    ),
                    durationMs = 2_000L,
                    providerName = "Bilibili",
                    isSmartReplacement = true,
                    originalId = logicalTrack.id,
                    originalTitle = logicalTrack.title,
                    originalArtists = logicalTrack.artists,
                    originalAlbum = logicalTrack.album,
                    originalSource = logicalTrack.source,
                    originalProviderName = logicalTrack.providerName,
                    replacementId = "bilibili:physical-track",
                    replacementTitle = "Physical replacement",
                    replacementArtists = "Replacement artist",
                    replacementAlbum = "Replacement album",
                    replacementSource = "bilibili",
                    replacementProviderName = "Bilibili",
                    replacementStrategy = "user_selected",
                    replacementScore = 1.0,
                ),
            )

            val replacementPlaying = awaitPlaying(engine) { state ->
                state.currentTrack?.id == logicalTrack.id &&
                    state.resolvedSource?.isReplacement == true &&
                    state.resolvedSource?.trackId == "bilibili:physical-track"
            }

            assertTrue(replacementServer.sawRequiredHeaders.get())
            assertEquals(logicalTrack.id, replacementPlaying.currentTrack?.id)
            assertEquals("netease", replacementPlaying.currentTrack?.source)
            assertEquals("bilibili:physical-track", replacementPlaying.resolvedSource?.trackId)
            assertEquals("bilibili", replacementPlaying.resolvedSource?.source)
            assertEquals(replacementServer.url, replacementPlaying.resolvedSource?.url)
            assertTrue(replacementPlaying.resolvedSource?.isReplacement == true)
            assertTrue(replacementPlaying.positionMs >= 100L)
            assertTrue(replacementPlaying.durationMs in 1_500L..2_500L)
        } finally {
            engine.close()
            replacementServer.close()
            Files.deleteIfExists(originalWav)
            Files.deleteIfExists(replacementWav)
        }
    }

    private fun awaitPlaying(
        engine: DesktopMpvPlaybackEngine,
        extraPredicate: (PlaybackState) -> Boolean = { true },
    ): PlaybackState = runBlocking {
        withTimeout(8_000L) {
            engine.state
                .filter { state ->
                    state.status == PlayerStatus.Playing &&
                        state.positionMs >= 100L &&
                        extraPredicate(state)
                }
                .first()
        }
    }

    private fun realLibMpvTestEnabled(): Boolean =
        System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true) &&
            System.getenv("FUOEVOLVE_REAL_LIBMPV_TEST") == "1"

    private fun serveWavWithRequiredHeaders(wav: Path): HeaderProtectedWavServer {
        val sawRequiredHeaders = AtomicBoolean(false)
        val bytes = Files.readAllBytes(wav)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/replacement.wav") { exchange ->
            val validHeaders = exchange.requestHeaders.getFirst("Referer") == REQUIRED_REFERER &&
                exchange.requestHeaders.getFirst("User-Agent") == REQUIRED_USER_AGENT
            if (!validHeaders) {
                exchange.sendResponseHeaders(403, -1L)
                exchange.close()
                return@createContext
            }
            sawRequiredHeaders.set(true)
            exchange.responseHeaders.set("Content-Type", "audio/wav")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
        }
        server.start()
        val address = server.address
        val host = if (address.address is java.net.Inet6Address) "[${address.address.hostAddress}]" else address.address.hostAddress
        return HeaderProtectedWavServer(
            server = server,
            url = "http://$host:${address.port}/replacement.wav",
            sawRequiredHeaders = sawRequiredHeaders,
        )
    }

    private fun createSilentWav(durationMs: Int): Path {
        val sampleRate = 8_000
        val channels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val sampleCount = sampleRate * durationMs / 1_000
        val dataSize = sampleCount * channels * bytesPerSample
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".encodeToByteArray())
                putInt(36 + dataSize)
                put("WAVE".encodeToByteArray())
                put("fmt ".encodeToByteArray())
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".encodeToByteArray())
                putInt(dataSize)
            }

        val path = Files.createTempFile("fuoevolve-libmpv-", ".wav")
        Files.newOutputStream(path).use { output ->
            output.write(header.array())
            output.write(ByteArray(dataSize))
        }
        return path
    }

    private data class HeaderProtectedWavServer(
        val server: HttpServer,
        val url: String,
        val sawRequiredHeaders: AtomicBoolean,
    ) : AutoCloseable {
        override fun close() {
            server.stop(0)
        }
    }

    private companion object {
        const val WAV_HEADER_SIZE = 44
        const val REQUIRED_REFERER = "https://www.bilibili.com/"
        const val REQUIRED_USER_AGENT = "FuoEvolve-libmpv-replacement-test"
    }
}
