package org.feeluown.mobile.desktop

import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.RepeatMode
import org.feeluown.mobile.TrackSourceType
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxMprisServiceTest {
    @Test
    fun mapsPlaybackStateAndMetadataToMprisValues() {
        val snapshot = MprisPlaybackSnapshot(
            status = PlayerStatus.Loading,
            track = track,
            positionMs = 12_345,
            durationMs = 180_000,
            volume = 0.75,
            repeatMode = RepeatMode.SINGLE,
            shuffle = true,
        )

        assertEquals("Playing", snapshot.playbackStatus)
        assertEquals("Track", snapshot.loopStatus)
        assertEquals(12_345_000, MprisObject(snapshot, FakeMprisControls()).getPosition())
        assertEquals(track.title, snapshot.metadata.getValue("xesam:title").value)
        assertEquals(listOf(track.artists), snapshot.metadata.getValue("xesam:artist").value)
        assertEquals(track.album, snapshot.metadata.getValue("xesam:album").value)
        assertEquals(180_000_000L, snapshot.metadata.getValue("mpris:length").value)
        assertEquals(track.coverUrl, snapshot.metadata.getValue("mpris:artUrl").value)
        assertTrue(snapshot.trackId?.path?.startsWith("/org/feeluown/FuoEvolve/track/") == true)
        assertEquals(snapshot.trackId, snapshot.copy(positionMs = 90_000).trackId)
        assertTrue(MprisPlaybackSnapshot().metadata.isEmpty())
    }

    @Test
    fun mapsPlainArtworkPathsToFileUris() {
        val snapshot = MprisPlaybackSnapshot(
            track = track.copy(coverUrl = "/tmp/fuo cover.jpg"),
        )

        assertEquals("file:/tmp/fuo%20cover.jpg", snapshot.metadata.getValue("mpris:artUrl").value)
    }

    @Test
    fun extrapolatesPlayingPositionWithMonotonicTimeAndKeepsPauseStable() {
        var nowNanos = 0L
        val initial = MprisPlaybackSnapshot(
            status = PlayerStatus.Playing,
            track = track,
            positionMs = 12_000,
            durationMs = 20_000,
        )
        val player = MprisObject(
            initialSnapshot = initial,
            controls = FakeMprisControls(),
            monotonicTimeNanos = { nowNanos },
        )

        nowNanos += 650_000_000
        assertEquals(12_650_000, player.getPosition())

        player.snapshot = initial.copy(volume = 0.5)
        nowNanos += 150_000_000
        player.snapshot = initial.copy(volume = 0.5, shuffle = true)
        nowNanos += 200_000_000
        assertEquals(13_000_000, player.getPosition())

        player.snapshot = initial.copy(status = PlayerStatus.Paused, volume = 0.5, shuffle = true)
        val pausedPosition = player.getPosition()
        nowNanos += 2_000_000_000
        assertEquals(pausedPosition, player.getPosition())
    }

    @Test
    fun keepsLoadingPositionFrozenAndRequestsResyncWhenPlaybackStarts() {
        var nowNanos = 0L
        val loading = MprisPlaybackSnapshot(
            status = PlayerStatus.Loading,
            track = track,
            positionMs = 0,
            durationMs = 20_000,
        )
        val player = MprisObject(
            initialSnapshot = loading,
            controls = FakeMprisControls(),
            monotonicTimeNanos = { nowNanos },
        )

        nowNanos += 5_000_000_000
        assertEquals(0, player.getPosition())

        val playing = loading.copy(status = PlayerStatus.Playing)
        assertTrue(shouldResyncPositionAfterLoading(loading, playing))
        player.snapshot = playing
        nowNanos += 500_000_000
        assertEquals(500_000, player.getPosition())
        assertFalse(
            shouldResyncPositionAfterLoading(
                loading,
                playing.copy(track = track.copy(id = "other")),
            ),
        )
    }

    @Test
    fun readsLiveEnginePositionAndUsesItForRelativeSeek() {
        var enginePositionMs = 12_345L
        val controls = FakeMprisControls()
        val player = MprisObject(
            initialSnapshot = MprisPlaybackSnapshot(
                status = PlayerStatus.Playing,
                track = track,
                positionMs = 10_000,
                durationMs = 20_000,
            ),
            controls = controls,
            positionSourceMs = { enginePositionMs },
        )

        assertEquals(12_345_000, player.getPosition())
        player.seek(2_000_000)
        assertEquals(listOf(14_345L), controls.seekPositions)

        enginePositionMs = 30_000
        assertEquals(20_000_000, player.getPosition())
    }

    @Test
    fun dispatchesControlsAndRejectsStaleOrOutOfRangePositions() {
        val controls = FakeMprisControls()
        val snapshot = MprisPlaybackSnapshot(
            status = PlayerStatus.Playing,
            track = track,
            positionMs = 10_000,
            durationMs = 20_000,
        )
        val seekedPositions = mutableListOf<Long>()
        val player = MprisObject(
            initialSnapshot = snapshot,
            controls = controls,
            onSeeked = seekedPositions::add,
            monotonicTimeNanos = { 0L },
        )

        player.play()
        player.pause()
        player.playPause()
        player.stop()
        player.previous()
        player.seek(5_000_000)
        player.seek(20_000_000)
        player.setPosition(DBusPath("/stale"), 2_000_000)
        player.setPosition(checkNotNull(snapshot.trackId), -1)
        player.setPosition(checkNotNull(snapshot.trackId), 21_000_000)
        player.setPosition(checkNotNull(snapshot.trackId), 8_000_000)
        player.setVolume(1.5)
        player.setLoopStatus("None")
        player.setShuffle(true)
        player.setRate(0.0)

        assertEquals(1, controls.playCount)
        assertEquals(2, controls.pauseCount)
        assertEquals(1, controls.playPauseCount)
        assertEquals(1, controls.stopCount)
        assertEquals(1, controls.previousCount)
        assertEquals(1, controls.nextCount)
        assertEquals(listOf(15_000L, 8_000L), controls.seekPositions)
        assertEquals(listOf(15_000_000L, 8_000_000L), seekedPositions)
        assertEquals(8_000_000L, player.getPosition())
        assertEquals(1.0, controls.lastVolume)
        assertEquals(RepeatMode.OFF, controls.lastRepeatMode)
        assertTrue(controls.shuffle)
    }

    @Test
    fun emitsOnlyChangedPropertiesAndDetectsPositionJumps() {
        val previous = MprisPlaybackSnapshot(
            status = PlayerStatus.Playing,
            track = track,
            positionMs = 10_000,
            durationMs = 20_000,
        )

        assertTrue(changedPlayerProperties(previous, previous.copy(positionMs = 11_000)).isEmpty())
        assertFalse(isUnexpectedPositionChange(previous, previous.copy(positionMs = 11_000)))
        assertTrue(isUnexpectedPositionChange(previous, previous.copy(positionMs = 15_000)))
        assertEquals(
            setOf("PlaybackStatus"),
            changedPlayerProperties(previous, previous.copy(status = PlayerStatus.Paused)).keys,
        )
        assertEquals(
            setOf("LoopStatus", "Shuffle", "Volume"),
            changedPlayerProperties(
                previous,
                previous.copy(repeatMode = RepeatMode.SINGLE, shuffle = true, volume = 0.5),
            ).keys,
        )
    }

    @Test
    fun exportsStandardInterfacesAndPropertiesOnSessionBus() {
        val serviceConnection = DBusConnectionBuilder.forSessionBus().build()
        val clientConnection = DBusConnectionBuilder.forSessionBus().build()
        val busName = "$MPRIS_BUS_NAME.Test${ProcessHandle.current().pid()}"
        val controls = FakeMprisControls()
        val snapshot = MprisPlaybackSnapshot(
            status = PlayerStatus.Paused,
            track = track,
            durationMs = 20_000,
        )
        try {
            serviceConnection.exportObject(
                MPRIS_OBJECT_PATH,
                MprisObject(
                    initialSnapshot = snapshot,
                    controls = controls,
                    onSeeked = { position ->
                        serviceConnection.sendMessage(MprisPlayer.Seeked(MPRIS_OBJECT_PATH, position))
                    },
                ),
            )
            serviceConnection.requestBusName(busName)

            val introspectable = clientConnection.getRemoteObject(
                busName,
                MPRIS_OBJECT_PATH,
                Introspectable::class.java,
            )
            val xml = introspectable.Introspect()
            assertTrue(xml.contains("interface name=\"$MPRIS_ROOT_INTERFACE\""))
            assertTrue(xml.contains("interface name=\"$MPRIS_PLAYER_INTERFACE\""))
            assertTrue(xml.contains("property name=\"Metadata\" type=\"a{sv}\" access=\"read\""), xml)
            assertTrue(xml.contains("signal name=\"Seeked\""), xml)

            val properties = clientConnection.getRemoteObject(
                busName,
                MPRIS_OBJECT_PATH,
                Properties::class.java,
            )
            val playerProperties = properties.GetAll(MPRIS_PLAYER_INTERFACE)
            assertEquals("Paused", playerProperties.getValue("PlaybackStatus").value)
            assertEquals("FuoEvolve", properties.Get<String>(MPRIS_ROOT_INTERFACE, "Identity"))
            properties.Set(MPRIS_PLAYER_INTERFACE, "Volume", 0.4)
            properties.Set(MPRIS_PLAYER_INTERFACE, "LoopStatus", "Track")
            properties.Set(MPRIS_PLAYER_INTERFACE, "Shuffle", true)
            val player = clientConnection.getRemoteObject(
                busName,
                MPRIS_OBJECT_PATH,
                MprisPlayer::class.java,
            )
            player.next()
            assertEquals(0.4, controls.lastVolume)
            assertEquals(RepeatMode.SINGLE, controls.lastRepeatMode)
            assertTrue(controls.shuffle)
            assertEquals(1, controls.nextCount)

            val seeked = CountDownLatch(1)
            var seekedPosition = -1L
            clientConnection.addSigHandler(MprisPlayer.Seeked::class.java) { signal ->
                seekedPosition = signal.position
                seeked.countDown()
            }.use {
                player.setPosition(checkNotNull(snapshot.trackId), 8_000_000)
                assertTrue(seeked.await(2, TimeUnit.SECONDS))
                assertEquals(8_000_000, seekedPosition)
            }
        } finally {
            runCatching { serviceConnection.unExportObject(MPRIS_OBJECT_PATH) }
            runCatching { serviceConnection.releaseBusName(busName) }
            runCatching { clientConnection.close() }
            runCatching { serviceConnection.close() }
        }
    }

    private class FakeMprisControls : MprisControls {
        var playCount = 0
        var pauseCount = 0
        var playPauseCount = 0
        var stopCount = 0
        var previousCount = 0
        var nextCount = 0
        val seekPositions = mutableListOf<Long>()
        var lastVolume = 0.0
        var lastRepeatMode = RepeatMode.QUEUE
        var shuffle = false

        override fun raise() = Unit
        override fun next() { nextCount += 1 }
        override fun previous() { previousCount += 1 }
        override fun pause() { pauseCount += 1 }
        override fun playPause() { playPauseCount += 1 }
        override fun stop() { stopCount += 1 }
        override fun play() { playCount += 1 }
        override fun seekTo(positionMs: Long) { seekPositions += positionMs }
        override fun setVolume(volume: Double) { lastVolume = volume }
        override fun setRepeatMode(repeatMode: RepeatMode) { lastRepeatMode = repeatMode }
        override fun setShuffleEnabled(enabled: Boolean) { shuffle = enabled }
    }

    private companion object {
        val track = MusicTrack(
            id = "netease:123/invalid path",
            title = "Track",
            artists = "Artist",
            album = "Album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            coverUrl = "https://example.com/cover.jpg",
        )
    }
}
