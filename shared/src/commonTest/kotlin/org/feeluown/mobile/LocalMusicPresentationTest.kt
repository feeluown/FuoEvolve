package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMusicPresentationTest {
    @Test
    fun directoryExclusionsAcceptLegacyAndCanonicalIds() {
        assertEquals("Music/Foo/", canonicalLocalMusicDirectoryId("Music/Foo"))
        assertTrue(isLocalMusicDirectoryExcluded("Music/Foo/", setOf("Music/Foo")))
        assertTrue(isLocalMusicDirectoryExcluded("Music/Foo", setOf("Music/Foo/")))
    }

    @Test
    fun artistAndAlbumModesCreateSecondLevelCollections() {
        val tracks = listOf(
            localTrack(TrackSourceType.LocalMediaStore).copy(
                id = "local:two",
                title = "第二首",
                artists = "歌手 B",
                album = "专辑 2",
                coverUrl = "cover-2",
            ),
            localTrack(TrackSourceType.LocalMediaStore).copy(
                id = "local:one",
                title = "第一首",
                artists = "歌手 A",
                album = "专辑 1",
                coverUrl = "cover-1",
            ),
        )

        val artists = buildLocalMusicCollections(
            mode = LocalMusicViewMode.Artist,
            tracks = tracks,
            directories = emptyList(),
            excludedDirectoryIds = emptySet(),
        )
        val albums = buildLocalMusicCollections(
            mode = LocalMusicViewMode.Album,
            tracks = tracks,
            directories = emptyList(),
            excludedDirectoryIds = emptySet(),
        )

        assertEquals(listOf("歌手 A", "歌手 B"), artists.map { it.title })
        assertEquals(listOf("专辑 1", "专辑 2"), albums.map { it.title })
        assertEquals(listOf("第一首"), artists.first().tracks.map { it.title })
        assertEquals("cover-1", albums.first().coverUrl)
    }

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
