package org.feeluown.mobile.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType

/**
 * Runs only from [desktopMpvSmokeTest] because it requires a host libmpv installation.
 * The CI job selects `ao=null` so this validates loading, decoding and event propagation without
 * depending on a physical audio device on the runner.
 */
class DesktopMpvLibmpvSmokeTest {
    @Test
    fun localWavReachesPlayingThroughRealLibmpv() {
        val fixture = Files.createTempFile("fuoevolve-libmpv-smoke-", ".wav")
        val engine = DesktopMpvPlaybackEngine { listener -> LibMpvBackend(listener) }
        try {
            writeSineWave(fixture)
            val track = MusicTrack(
                id = "local:libmpv-smoke",
                title = "libmpv smoke",
                artists = "FuoEvolve",
                album = "CI",
                source = "local",
                sourceType = TrackSourceType.LocalMediaStore,
                durationMs = 5_000L,
                providerId = "local:libmpv-smoke",
                providerName = "local",
            )

            engine.play(
                track,
                PlaybackPayload(
                    url = fixture.toUri().toString(),
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    source = track.source,
                    durationMs = 5_000L,
                ),
            )

            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline && engine.state.value.status == PlayerStatus.Loading) {
                Thread.sleep(50L)
            }

            val state = engine.state.value
            assertEquals(
                PlayerStatus.Playing,
                state.status,
                "libmpv smoke test did not start playback: ${state.errorMessage}",
            )
        } finally {
            engine.close()
            Files.deleteIfExists(fixture)
        }
    }

    private fun writeSineWave(path: java.nio.file.Path) {
        val sampleRate = 8_000
        val sampleCount = sampleRate * 5
        val dataSize = sampleCount * Short.SIZE_BYTES
        val audio = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        audio.put("RIFF".toByteArray())
        audio.putInt(36 + dataSize)
        audio.put("WAVE".toByteArray())
        audio.put("fmt ".toByteArray())
        audio.putInt(16)
        audio.putShort(1)
        audio.putShort(1)
        audio.putInt(sampleRate)
        audio.putInt(sampleRate * Short.SIZE_BYTES)
        audio.putShort(Short.SIZE_BYTES.toShort())
        audio.putShort(16)
        audio.put("data".toByteArray())
        audio.putInt(dataSize)
        repeat(sampleCount) { index ->
            val sample = (sin(2.0 * PI * 440.0 * index / sampleRate) * Short.MAX_VALUE * 0.2).toInt()
            audio.putShort(sample.toShort())
        }
        Files.write(path, audio.array())
    }
}
