package org.feeluown.mobile.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType

class DesktopMpvRealIntegrationTest {
    @Test
    fun realLibMpvPublishesPlayingStateAndAdvancingTimeline() {
        if (!System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) return
        if (System.getenv("FUOEVOLVE_REAL_LIBMPV_TEST") != "1") return

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

            val playing = runBlocking {
                withTimeout(8_000L) {
                    engine.state
                        .filter { state ->
                            state.status == PlayerStatus.Playing && state.positionMs >= 100L
                        }
                        .first()
                }
            }

            assertEquals(PlayerStatus.Playing, playing.status)
            assertTrue(playing.positionMs >= 100L)
            assertTrue(playing.durationMs >= 1_500L)
        } finally {
            engine.close()
            Files.deleteIfExists(wav)
        }
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
                putShort(1)
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

    private companion object {
        const val WAV_HEADER_SIZE = 44
    }
}
