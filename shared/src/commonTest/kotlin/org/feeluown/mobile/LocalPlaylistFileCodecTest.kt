package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalPlaylistFileCodecTest {
    @Test
    fun decodesUpstreamMetadataSongDescriptionAndOrder() {
        val raw = """
            +++
            title = "现场收藏"
            description = "来自 FeelUOwn"
            created = "2026-07-29T00:00:00"
            +++
        """.trimIndent() + "\n" +
            "fuo://netease/songs/first  # \"标题 - 现场\" - 歌手 - 专辑 - 03:30\n" +
            "fuo://qqmusic/songs/second\t# 第二首 - 另一位 - 另一张 - 01:02:03\n"

        val preview = LocalPlaylistFileCodec.decode("fallback.fuo", raw)

        assertEquals("现场收藏", preview.title)
        assertEquals("来自 FeelUOwn", preview.description)
        assertEquals(listOf("fuo://netease/songs/first", "fuo://qqmusic/songs/second"), preview.tracks.map { it.uri })
        assertEquals("标题 - 现场", preview.tracks[0].title)
        assertEquals(210_000L, preview.tracks[0].durationMs)
        assertEquals(3_723_000L, preview.tracks[1].durationMs)
    }

    @Test
    fun keepsUnknownProviderAndSkipsUnsupportedResourcesAndInvalidLines() {
        val raw = """
            +++
            title = "兼容"
            +++
            fuo://missing_provider/songs/unknown
            fuo://netease/albums/album-id
            this is not a resource
            fuo://netease/songs/unknown
            fuo://netease/songs/unknown
        """.trimIndent()

        val preview = LocalPlaylistFileCodec.decode("兼容.fuo", raw)

        assertEquals(2, preview.tracks.size)
        assertEquals("missing_provider", preview.tracks.first().providerId)
        assertEquals("unknown", preview.tracks.first().identifier)
        assertEquals(2, preview.skippedLineCount)
    }

    @Test
    fun encodesNormalizedUrisMetadataAndDurationInInputOrder() {
        val playlist = LocalPlaylist(
            id = "favorites.fuo",
            fileName = "favorites.fuo",
            title = "我的收藏",
            description = "带有 - 分隔符",
            tracks = listOf(
                LocalPlaylistTrack(
                    uri = "not-used",
                    providerId = "netease",
                    identifier = "1",
                    title = "第一首 - 现场",
                    artists = "歌手",
                    album = "专辑",
                    durationMs = 90_000,
                ),
                LocalPlaylistTrack(
                    uri = "not-used",
                    providerId = "qqmusic",
                    identifier = "2",
                    title = "第二首",
                    durationMs = 3_723_000,
                ),
            ),
        )

        val encoded = LocalPlaylistFileCodec.encode(playlist)

        assertTrue(encoded.startsWith("+++\ntitle = \"我的收藏\"\ndescription = \"带有 - 分隔符\"\n+++\n"))
        assertTrue(encoded.indexOf("fuo://netease/songs/1") < encoded.indexOf("fuo://qqmusic/songs/2"))
        assertTrue(encoded.contains("01:30"))
        assertTrue(encoded.contains("62:03"))
        val roundTrip = LocalPlaylistFileCodec.decode("favorites.fuo", encoded)
        assertEquals(playlist.title, roundTrip.title)
        assertEquals(playlist.description, roundTrip.description)
        assertEquals(
            listOf("fuo://netease/songs/1", "fuo://qqmusic/songs/2"),
            roundTrip.tracks.map { it.uri },
        )
        assertEquals(playlist.tracks[0].title, roundTrip.tracks[0].title)
        assertEquals(playlist.tracks[0].durationMs, roundTrip.tracks[0].durationMs)
        assertEquals(playlist.tracks[1].durationMs, roundTrip.tracks[1].durationMs)
    }

    @Test
    fun quotesInteriorDoubleQuotesInSongMetadataForRoundTrip() {
        val playlist = LocalPlaylist(
            id = "quoted.fuo",
            fileName = "quoted.fuo",
            title = "带引号",
            tracks = listOf(
                LocalPlaylistTrack(
                    uri = "not-used",
                    providerId = "netease",
                    identifier = "1",
                    title = "12\" Remix",
                    artists = "Artist \"A",
                    album = "Album \"B",
                    durationMs = 60_000,
                ),
            ),
        )

        val roundTrip = LocalPlaylistFileCodec.decode(
            playlist.fileName,
            LocalPlaylistFileCodec.encode(playlist),
        )

        assertEquals(playlist.tracks.single().title, roundTrip.tracks.single().title)
        assertEquals(playlist.tracks.single().artists, roundTrip.tracks.single().artists)
        assertEquals(playlist.tracks.single().album, roundTrip.tracks.single().album)
    }
}
