package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.qqmusic.QQMusicArtistDetailProvider

class QQMusicArtistDetailProviderTest {
    @Test
    fun normalizesArtistMidAndCoverHost() {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine { addHandler { respond("{}") } }
            },
        )
        val provider = QQMusicArtistDetailProvider(client, InMemoryProviderCredentialStore())
        val item = ProviderMediaItem(
            id = "artist:qqmusic:101",
            title = "周杰伦",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
            type = ProviderMediaItemType.Artist,
            coverUrl = "https://y.qq.com/music/photo_new/T001R300x300M0000025NhlN2yWrP4.jpg",
        )

        val normalized = provider.normalizeArtist(item)

        assertTrue(normalized.id.endsWith("0025NhlN2yWrP4"))
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T001R300x300M0000025NhlN2yWrP4.jpg",
            normalized.coverUrl,
        )
        client.close()
    }

    @Test
    fun loadsArtistTracksWithSingerMid() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
                        val data = request.url.parameters["data"].orEmpty()
                        assertTrue(data.contains("singerSongList"))
                        assertTrue(data.contains("musichall.song_list_server"))
                        assertTrue(data.contains("\"singerMid\":\"0025NhlN2yWrP4\""))
                        respond(
                            """
                            {
                              "singerSongList":{
                                "data":{
                                  "totalNum":1,
                                  "songList":[
                                    {
                                      "songInfo":{
                                        "mid":"song-mid",
                                        "name":"测试歌曲",
                                        "singer":[{"id":4558,"mid":"0025NhlN2yWrP4","name":"周杰伦"}],
                                        "album":{"id":1,"mid":"album-mid","name":"测试专辑"},
                                        "interval":180
                                      }
                                    }
                                  ]
                                }
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                }
            },
        )
        val provider = QQMusicArtistDetailProvider(client, InMemoryProviderCredentialStore())
        val item = ProviderMediaItem(
            id = "artist:qqmusic:0025NhlN2yWrP4",
            title = "周杰伦",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
            type = ProviderMediaItemType.Artist,
        )

        val page = provider.loadTracksPage(item, offset = 0, limit = 20)

        assertEquals("测试歌曲", page.tracks.single().title)
        assertEquals("周杰伦", page.tracks.single().artists)
        assertEquals(180_000, page.tracks.single().durationMs)
        assertTrue(page.tracks.single().artistItemId.orEmpty().endsWith("0025NhlN2yWrP4"))
        assertEquals(1, page.total)
        assertFalse(page.hasMore)
        client.close()
    }
}
