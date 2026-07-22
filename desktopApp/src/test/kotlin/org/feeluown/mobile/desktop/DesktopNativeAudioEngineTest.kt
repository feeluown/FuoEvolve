package org.feeluown.mobile.desktop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopNativeAudioEngineTest {
    @Test
    fun usesLibMpvInAudioOnlyModeAndMapsPlaybackControls() = runTest {
        val client = FakeMpvClient()
        val engine = DesktopNativeAudioEngine(this) { client }

        engine.play(track, payload)

        assertEquals("no", client.options["vid"])
        assertEquals("no", client.options["audio-display"])
        assertTrue(client.initialized)
        assertEquals("100.0", client.propertiesSet["volume"])
        assertEquals(
            listOf("change-list", "http-header-fields", "append", "Referer: https://example.com/"),
            client.commands[0],
        )
        assertEquals(listOf("loadfile", payload.url), client.commands[1])
        assertEquals(PlayerStatus.Loading, engine.state.value.status)

        client.properties["time-pos"] = "12.5"
        client.properties["duration"] = "180.0"
        client.properties["pause"] = "no"
        client.properties["volume"] = "75.0"
        runCurrent()
        assertEquals(PlayerStatus.Loading, engine.state.value.status)
        assertEquals(12_500, engine.currentPositionMs())

        client.events.addLast(MpvEvent(MpvEventType.FileLoaded))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(PlayerStatus.Playing, engine.state.value.status)
        assertEquals(12_500, engine.state.value.positionMs)
        assertEquals(12_500, engine.currentPositionMs())
        assertEquals(180_000, engine.state.value.durationMs)
        assertEquals(0.75, engine.state.value.volume)

        client.properties["time-pos"] = "13.125"
        assertEquals(13_125, engine.currentPositionMs())

        engine.pause()
        assertEquals("yes", client.propertiesSet["pause"])
        assertEquals(PlayerStatus.Paused, engine.state.value.status)

        engine.resume()
        assertEquals("no", client.propertiesSet["pause"])
        assertEquals(PlayerStatus.Playing, engine.state.value.status)

        engine.seekTo(42_500)
        assertEquals(listOf("seek", "42.5", "absolute+exact"), client.commands.last())
        assertEquals(42_500, engine.state.value.positionMs)

        engine.setVolume(0.42)
        assertEquals("42.0", client.propertiesSet["volume"])
        assertEquals(0.42, engine.state.value.volume)

        client.events.addLast(MpvEvent(MpvEventType.EndFile))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(PlayerStatus.Ended, engine.state.value.status)

        engine.stop()
        assertTrue(client.closed)
        assertEquals(0, engine.currentPositionMs())
        assertEquals(PlayerStatus.Idle, engine.state.value.status)
        assertEquals(0.42, engine.state.value.volume)
    }

    @Test
    fun doesNotExposePreviousClientPositionWhilePreparingNextTrack() = runTest {
        val client = FakeMpvClient()
        val engine = DesktopNativeAudioEngine(this) { client }
        engine.play(track, payload)
        client.events.addLast(MpvEvent(MpvEventType.FileLoaded))
        client.properties["time-pos"] = "90.0"
        client.properties["pause"] = "no"
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(90_000, engine.currentPositionMs())

        engine.prepareLoading(track.copy(id = "track-2"))

        assertEquals(PlayerStatus.Loading, engine.state.value.status)
        assertEquals(0, engine.currentPositionMs())
        engine.stop()
    }

    @Test
    fun exposesLibMpvPlaybackErrors() = runTest {
        val client = FakeMpvClient()
        val engine = DesktopNativeAudioEngine(this) { client }

        engine.play(track, payload)
        runCurrent()
        client.events.addLast(MpvEvent(MpvEventType.EndFile, errorCode = -13))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(PlayerStatus.Error, engine.state.value.status)
        assertEquals("libmpv 播放失败：error -13", engine.state.value.errorMessage)
        engine.stop()
    }

    private class FakeMpvClient : MpvClient {
        val options = linkedMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        val properties = mutableMapOf<String, String>()
        val propertiesSet = mutableMapOf<String, String>()
        val events = ArrayDeque<MpvEvent>()
        var initialized = false
        var closed = false

        override fun setOption(name: String, value: String) {
            options[name] = value
        }

        override fun initialize() {
            initialized = true
        }

        override fun command(vararg args: String) {
            commands += args.toList()
        }

        override fun setProperty(name: String, value: String) {
            propertiesSet[name] = value
        }

        override fun getProperty(name: String): String? = properties[name]

        override fun pollEvent(): MpvEvent = events.removeFirstOrNull() ?: MpvEvent(MpvEventType.None)

        override fun errorDescription(errorCode: Int): String = "error $errorCode"

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val track = MusicTrack(
            id = "track-1",
            title = "Track",
            artists = "Artist",
            album = "Album",
            source = "test",
            sourceType = TrackSourceType.Provider,
        )
        val payload = PlaybackPayload(
            url = "https://example.com/audio.mp3",
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = track.source,
            headers = mapOf("Referer" to "https://example.com/"),
        )
    }
}
