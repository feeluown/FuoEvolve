package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ShareResourcesTest {
    @Test
    fun parsesNeteaseShareText() {
        val resource = assertNotNull(
            parseSharedResource(
                "分享Fly By Midnight/Rachel Grae的单曲《Different Lives》: https://music.163.com/song?id=2151445143 (来自@网易云音乐)",
            ),
        )

        assertEquals(ShareResourceType.Song, resource.type)
        assertEquals("netease", resource.providerId)
        assertEquals("2151445143", resource.identifier)
    }

    @Test
    fun parsesNeteaseResourceTypes() {
        assertResource("https://music.163.com/#/playlist?id=8416733444", ShareResourceType.Playlist, "netease", "8416733444")
        assertResource("https://music.163.com/#/artist?id=6452", ShareResourceType.Artist, "netease", "6452")
        assertResource("https://y.music.163.com/m/album?id=12345", ShareResourceType.Album, "netease", "12345")
    }

    @Test
    fun parsesBilibiliVideoAndPage() {
        assertResource(
            "郭德纲于谦 相声《有钱人的生活》无唱助眠 https://www.bilibili.com/video/BV1gUu36kEd8?p=1&unique_k=2333",
            ShareResourceType.Song,
            "bilibili",
            "paged_BV1gUu36kEd8__1",
        )
        assertResource(
            "https://m.bilibili.com/video/BV1gUu36kEd8",
            ShareResourceType.Song,
            "bilibili",
            "BV1gUu36kEd8",
        )
    }

    @Test
    fun parsesQqMusicLinks() {
        assertResource("https://y.qq.com/n/ryqq/songDetail/0039MnYb0qxYhV", ShareResourceType.Song, "qqmusic", "0039MnYb0qxYhV")
        assertResource("https://y.qq.com/n/ryqq/playlist/9237907994", ShareResourceType.Playlist, "qqmusic", "9237907994")
        assertResource("https://y.qq.com/n/ryqq/singer/0025NhlN2yWrP4", ShareResourceType.Artist, "qqmusic", "0025NhlN2yWrP4")
        assertResource("https://y.qq.com/n/ryqq/albumDetail/003fNnQZ1tZ6vK", ShareResourceType.Album, "qqmusic", "003fNnQZ1tZ6vK")
        assertResource("https://i.y.qq.com/n2/m/share/details/taoge.html?id=9237907994", ShareResourceType.Playlist, "qqmusic", "9237907994")
    }

    @Test
    fun parsesYouTubeMusicAndYouTubeLinks() {
        assertResource("https://music.youtube.com/watch?v=dQw4w9WgXcQ", ShareResourceType.Song, "ytmusic", "dQw4w9WgXcQ")
        assertResource("https://youtu.be/dQw4w9WgXcQ?t=42", ShareResourceType.Song, "ytmusic", "dQw4w9WgXcQ")
        assertResource("https://music.youtube.com/playlist?list=PL123456", ShareResourceType.Playlist, "ytmusic", "PL123456")
        assertResource("https://music.youtube.com/channel/UC123456", ShareResourceType.Artist, "ytmusic", "UC123456")
        assertResource("https://music.youtube.com/browse/MPREb_123456", ShareResourceType.Album, "ytmusic", "MPREb_123456")
    }

    @Test
    fun keepsFuoLinksCompatible() {
        assertResource("fuo://netease/songs/12345", ShareResourceType.Song, "netease", "12345")
        assertResource(
            "https://feeluown.github.io/FuoEvolve/r/qqmusic/playlists/67890?d=abc",
            ShareResourceType.Playlist,
            "qqmusic",
            "67890",
        )
    }

    @Test
    fun derivesSearchQueryForUnsupportedShareText() {
        assertEquals(
            "Different Lives Fly By Midnight/Rachel Grae",
            sharedSearchQuery("分享Fly By Midnight/Rachel Grae的单曲《Different Lives》: https://163cn.tv/bcHb0TZS (来自@网易云音乐)"),
        )
        assertEquals(
            "Some Song - Some Artist",
            sharedSearchQuery("Some Song - Some Artist https://open.spotify.com/track/123 Spotify"),
        )
    }

    private fun assertResource(
        text: String,
        type: ShareResourceType,
        providerId: String,
        identifier: String,
    ) {
        val resource = assertNotNull(parseSharedResource(text))
        assertEquals(type, resource.type)
        assertEquals(providerId, resource.providerId)
        assertEquals(identifier, resource.identifier)
    }
}
