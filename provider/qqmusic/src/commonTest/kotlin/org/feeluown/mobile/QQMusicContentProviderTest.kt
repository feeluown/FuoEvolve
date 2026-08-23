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
import org.feeluown.mobile.provider.qqmusic.QQMUSIC_ARTIST_SQUARE
import org.feeluown.mobile.provider.qqmusic.QQMUSIC_MV_SQUARE
import org.feeluown.mobile.provider.qqmusic.QQMUSIC_NEW_ALBUMS
import org.feeluown.mobile.provider.qqmusic.QQMUSIC_PLAYLIST_SQUARE
import org.feeluown.mobile.provider.qqmusic.QQMUSIC_TOPLISTS
import org.feeluown.mobile.provider.qqmusic.QQMusicContentProvider

class QQMusicContentProviderTest {
    @Test
    fun exposesFirstBatchDiscoveryFeatures() {
        val provider = QQMusicContentProvider(
            ProviderHttpClient(
                HttpClient(MockEngine) {
                    engine { addHandler { respond("{}") } }
                },
            ),
            InMemoryProviderCredentialStore(),
        )
        val ids = provider.features.map { it.id }.toSet()

        assertTrue(QQMUSIC_TOPLISTS in ids)
        assertTrue(QQMUSIC_PLAYLIST_SQUARE in ids)
        assertTrue(QQMUSIC_ARTIST_SQUARE in ids)
        assertTrue(QQMUSIC_NEW_ALBUMS in ids)
        assertTrue(QQMUSIC_MV_SQUARE in ids)
    }

    @Test
    fun searchesPlaylistsArtistsAlbumsAndMvs() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
                        val data = request.url.parameters["data"].orEmpty()
                        assertTrue(data.contains("singerSearch"))
                        assertTrue(data.contains("albumSearch"))
                        assertTrue(data.contains("playlistSearch"))
                        assertTrue(data.contains("mvSearch"))
                        respond(
                            """
                            {
                              "singerSearch":{"data":{"body":{"singer":{"list":[{"singerID":101,"singerMID":"singer-mid","singerName":"测试歌手"}]}}}},
                              "albumSearch":{"data":{"body":{"album":{"list":[{"albumID":201,"albumMID":"album-mid","albumName":"测试专辑"}]}}}},
                              "playlistSearch":{"data":{"body":{"songlist":{"list":[{"dissid":"301","dissname":"测试歌单","imgurl":"https://example.test/playlist.jpg"}]}}}},
                              "mvSearch":{"data":{"body":{"mv":{"list":[{"vid":"mv-401","name":"测试 MV","singer":[{"name":"测试歌手"}]}]}}}}
                            }
                            """.trimIndent(),
                        )
                    }
                }
            },
        )
        val provider = QQMusicContentProvider(client, InMemoryProviderCredentialStore())

        val result = provider.searchExtras("测试")

        assertEquals("测试歌单", result.playlists.single().title)
        assertEquals("测试歌手", result.artists.single().title)
        assertEquals("测试专辑", result.albums.single().title)
        assertEquals("测试 MV", result.videos.single().title)
        client.close()
    }

    @Test
    fun loadsToplistsAndVirtualToplistDetail() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
                        val data = request.url.parameters["data"].orEmpty()
                        when {
                            data.contains("\"method\":\"GetAll\"") -> respond(
                                """
                                {"topList":{"data":{"group":[{"groupName":"巅峰榜","toplist":[{"topId":4,"title":"热歌榜","period":"2026-08-09","headPicUrl":"https://example.test/top.jpg","listenNum":1234,"song":[{"title":"预览歌曲"}]}]}]}}}
                                """.trimIndent(),
                            )
                            data.contains("\"method\":\"GetDetail\"") -> {
                                assertTrue(data.contains("\"topId\":4"))
                                assertTrue(data.contains("2026-08-09"))
                                respond(
                                    """
                                    {"detail":{"data":{"data":{"topId":4,"title":"热歌榜","period":"2026-08-09","totalNum":1,"headPicUrl":"https://example.test/top.jpg","song":[{"rank":1}]},"songInfoList":[{"mid":"song-mid","name":"榜单歌曲","singer":[{"id":11,"name":"榜单歌手"}],"album":{"id":22,"mid":"album-mid","name":"榜单专辑"},"interval":180}]}}}
                                    """.trimIndent(),
                                )
                            }
                            else -> error("unexpected musicu payload: $data")
                        }
                    }
                }
            },
        )
        val provider = QQMusicContentProvider(client, InMemoryProviderCredentialStore())
        val feature = provider.features.first { it.id == QQMUSIC_TOPLISTS }

        val section = provider.loadFeature(feature, 0, 20)
        val playlist = section.playlists.single()
        assertEquals("热歌榜", playlist.title)
        assertTrue(playlist.id.contains("toplist:4:2026-08-09"))

        val detail = provider.playlistDetail(playlist, 0, 20)
        assertEquals("榜单歌曲", detail.tracks.single().title)
        assertEquals("榜单歌手", detail.tracks.single().artists)
        assertEquals(180_000, detail.tracks.single().durationMs)
        assertFalse(detail.tracksHasMore)
        client.close()
    }

    @Test
    fun loadsPlaylistArtistAlbumAndMvDiscovery() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/splcloud/fcgi-bin/fcg_get_diss_tag_conf.fcg" -> respond(
                                """{"data":{"categories":[{"categoryGroupName":"语种","items":[{"categoryId":1,"categoryName":"华语","usable":1}]}]}}""",
                            )
                            "/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg" -> {
                                assertEquals("10000000", request.url.parameters["categoryId"])
                                assertEquals("5", request.url.parameters["sortId"])
                                respond(
                                    """{"data":{"sum":1,"list":[{"dissid":"playlist-1","dissname":"热门歌单","imgurl":"https://example.test/list.jpg","listennum":99,"song_count":20}]}}""",
                                )
                            }
                            "/cgi-bin/musicu.fcg" -> {
                                val data = request.url.parameters["data"].orEmpty()
                                when {
                                    data.contains("get_singer_list") -> respond(
                                        """{"singerList":{"data":{"total":1,"singerlist":[{"singer_id":101,"singer_mid":"singer-mid","singer_name":"广场歌手"}]}}}""",
                                    )
                                    data.contains("get_new_album_info") -> {
                                        assertTrue(data.contains("\"sin\":0"))
                                        respond(
                                            """{"new_album":{"data":{"total":1,"albums":[{"album_id":201,"album_mid":"album-mid","album_name":"新碟"}]}}}""",
                                        )
                                    }
                                    data.contains("GetAllocMvInfo") -> {
                                        assertTrue(data.contains("\"size\":20"))
                                        respond(
                                            """{"mv_list":{"data":{"total":1,"list":[{"vid":"mv-1","name":"广场 MV","singer":[{"name":"MV 歌手"}],"duration":200}]}}}""",
                                        )
                                    }
                                    else -> error("unexpected musicu payload: $data")
                                }
                            }
                            else -> error("unexpected request: ${request.url}")
                        }
                    }
                }
            },
        )
        val provider = QQMusicContentProvider(client, InMemoryProviderCredentialStore())

        val playlistSection = provider.loadFeature(
            provider.features.first { it.id == QQMUSIC_PLAYLIST_SQUARE },
            0,
            20,
        )
        assertEquals("热门歌单", playlistSection.playlists.single().title)
        assertTrue(ProviderFeatureFilterCodec.filters(playlistSection.feature.id).isNotEmpty())

        val artistSection = provider.loadFeature(
            provider.features.first { it.id == QQMUSIC_ARTIST_SQUARE },
            0,
            20,
        )
        assertEquals("广场歌手", artistSection.mediaItems.single().title)
        assertTrue(ProviderFeatureFilterCodec.filters(artistSection.feature.id).isNotEmpty())

        val albumSection = provider.loadFeature(
            provider.features.first { it.id == QQMUSIC_NEW_ALBUMS },
            0,
            20,
        )
        assertEquals("新碟", albumSection.mediaItems.single().title)

        val mvSection = provider.loadFeature(
            provider.features.first { it.id == QQMUSIC_MV_SQUARE },
            0,
            20,
        )
        assertEquals("广场 MV", mvSection.videos.single().title)
        assertEquals(200_000, mvSection.videos.single().durationMs)
        assertTrue(ProviderFeatureFilterCodec.filters(mvSection.feature.id).isNotEmpty())
        client.close()
    }
}
