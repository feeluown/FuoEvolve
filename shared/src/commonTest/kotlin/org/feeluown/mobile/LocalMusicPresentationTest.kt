package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalMusicPresentationTest {
    @Test
    fun localSourcesUseLocalLabel() {
        assertEquals("本地", sourceLabel(localTrack(TrackSourceType.LocalMediaStore), null))
        assertEquals("本地", sourceLabel(localTrack(TrackSourceType.Downloaded), null))
        assertEquals("网易云音乐", sourceLabel(providerTrack(), null))
    }

    @Test
    fun playbackQueueKeepsDirectoryIdAndReadsOlderTracks() {
        val track = localTrack(TrackSourceType.LocalMediaStore).copy(localDirectoryId = "Music/A/")
        val snapshot = PlaybackQueueSnapshot(mainQueue = listOf(track))

        val decoded = PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(snapshot)).mainQueue.single()

        assertEquals("Music/A/", decoded.localDirectoryId)

        val legacy = PlaybackQueueCodec.encode(snapshot).lineSequence().joinToString("\n") { line ->
            if (line.startsWith("track\t")) line.substringBeforeLast('\t') else line
        }
        assertNull(PlaybackQueueCodec.decode(legacy).mainQueue.single().localDirectoryId)
    }

    private fun localTrack(sourceType: TrackSourceType) = MusicTrack(
        id = "local:track",
        title = "本地歌曲",
        artists = "歌手",
        album = "专辑",
        source = "local",
        sourceType = sourceType,
    )

    private fun providerTrack() = MusicTrack(
        id = "netease:track",
        title = "在线歌曲",
        artists = "歌手",
        album = "专辑",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        providerName = "网易云音乐",
    )
}
