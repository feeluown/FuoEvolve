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
            if (line.startsWith("track\t")) {
                line.split("\t").take(2 + 26).joinToString("\t")
            } else {
                line
            }
        }
        assertNull(PlaybackQueueCodec.decode(legacy).mainQueue.single().localDirectoryId)
    }

    @Test
    fun playbackQueueReadsHistoricalV2TrackAfterCoreModelMove() {
        val raw = listOf(
            "v2",
            "0\tfalse\tQUEUE\tfalse\t",
            listOf(
                "track",
                "main",
                "netease:1",
                "Song",
                "Artist",
                "Album",
                "netease",
                "Provider",
                "",
                "180000",
                "",
                "",
                "netease:1",
                "网易云音乐",
                "false",
                "",
                "",
                "false",
                "artist:netease:10",
                "album:netease:20",
            ).joinToString("\t"),
        ).joinToString("\n")

        val decoded = PlaybackQueueCodec.decode(raw).mainQueue.single()

        assertEquals("netease:1", decoded.id)
        assertEquals(TrackSourceType.Provider, decoded.sourceType)
        assertEquals(180_000L, decoded.durationMs)
        assertEquals("artist:netease:10", decoded.artistItemId)
        assertEquals("album:netease:20", decoded.albumItemId)
        assertTrue(PlaybackQueueCodec.encode(PlaybackQueueSnapshot(mainQueue = listOf(decoded))).startsWith("v2\n"))
    }

    @Test
    fun playbackQueueKeepsSmartReplacementDetailTargets() {
        val track = localTrack(TrackSourceType.Provider).copy(
            isSmartReplacement = true,
            originalId = "netease:1",
            originalArtists = "原始歌手",
            originalAlbum = "原始专辑",
            originalSource = "netease",
            replacementId = "qqmusic:2",
            replacementAlbum = "替换专辑",
        )
        val decoded = PlaybackQueueCodec.decode(
            PlaybackQueueCodec.encode(PlaybackQueueSnapshot(mainQueue = listOf(track))),
        ).mainQueue.single()

        assertEquals("netease:1", decoded.originalId)
        assertEquals("原始歌手", decoded.originalArtists)
        assertEquals("原始专辑", decoded.originalAlbum)
        assertEquals("netease", decoded.originalSource)
        assertEquals("qqmusic:2", decoded.replacementId)
        assertEquals("替换专辑", decoded.replacementAlbum)
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
