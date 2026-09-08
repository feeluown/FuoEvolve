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
    fun realLibMpvSwitchesPhysicalSourceWithoutChangingLogicalTrackIdentity() {
        if (!realLibMpvTestEnabled()) return

        val originalWav = createSilentWav(durationMs = 3_000)
        val replacementWav = createSilentWav(durationMs = 2_000)
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
                    url = replacementWav.toAbsolutePath().toString(),
                    title = logicalTrack.title,
                    artists = logicalTrack.artists,
                    album = logicalTrack.album,
                    source = "qqmusic",
                    durationMs = 2_000L,
                    providerName = "QQ Music",
                    isSmartReplacement = true,
                    originalId = logicalTrack.id,
                    originalTitle = logicalTrack.title,
                    originalArtists = logicalTrack.artists,
                    originalAlbum = logicalTrack.album,
                    originalSource = logicalTrack.source,
                    originalProviderName = logicalTrack.providerName,
                    replacementId = "qqmusic:physical-track",
                    replacementTitle = "Physical replacement",
                    replacementArtists = "Replacement artist",
                    replacementAlbum = "Replacement album",
                    replacementSource = "qqmusic",
                    replacementProviderName = "QQ Music",
                    replacementStrategy = "user_selected",
                    replacementScore = 1.0,
                ),
            )

            val replacementPlaying = awaitPlaying(engine) { state ->
                state.currentTrack?.id == logicalTrack.id &&
                    state.resolvedSource?.isReplacement == true &&
                    state.resolvedSource?.trackId == "qqmusic:physical-track"
            }

            assertEquals(logicalTrack.id, replacementPlaying.currentTrack?.id)
            assertEquals("netease", replacementPlaying.currentTrack?.source)
            assertEquals("qqmusic:physical-track", replacementPlaying.resolvedSource?.trackId)
            assertEquals("qqmusic", replacementPlaying.resolvedSource?.source)
            assertEquals(replacementWav.toAbsolutePath().toString(), replacementPlaying.resolvedSource?.url)
            assertTrue(replacementPlaying.resolvedSource?.isReplacement == true)
            assertTrue(replacementPlaying.positionMs >= 100L)
            assertTrue(replacementPlaying.durationMs in 1_500L..2_500L)
        } finally {
            engine.close()
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

    private companion object {
        const val WAV_HEADER_SIZE = 44
    }
}
