package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.netease.NeteaseProvider

class NeteaseDiscoveryFeatureTest {
    @Test
    fun exposesDiscoveryFeaturesUsingExistingContentTypes() = runTest {
        val client = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { respond("{\"code\":200}") } }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val features = provider.features.associateBy { it.id }

        assertEquals("每日推荐歌单", features["netease_daily_playlists"]?.title)
        assertEquals(ProviderContentType.Songs, features["netease_recommended_new_songs"]?.contentType)
        assertEquals(ProviderFeatureCategory.Recommend, features["netease_recommended_new_songs"]?.category)
        assertFalse(assertNotNull(features["netease_recommended_new_songs"]).requiresLogin)
        assertEquals(ProviderContentType.Videos, features["netease_recommended_mvs"]?.contentType)
        assertEquals(ProviderFeatureCategory.Recommend, features["netease_recommended_mvs"]?.category)
        assertFalse(assertNotNull(features["netease_recommended_mvs"]).requiresLogin)

        assertEquals(ProviderContentType.Songs, features["netease_new_songs"]?.contentType)
        assertEquals(ProviderContentType.Albums, features["netease_new_albums"]?.contentType)
        assertEquals(ProviderContentType.Artists, features["netease_top_artists"]?.contentType)
        assertEquals(ProviderContentType.Playlists, features["netease_highquality_playlists"]?.contentType)
        assertEquals(ProviderContentType.Videos, features["netease_top_mvs"]?.contentType)
        listOf(
            "netease_new_songs",
            "netease_new_albums",
            "netease_top_artists",
            "netease_highquality_playlists",
            "netease_top_mvs",
        ).forEach { id ->
            val feature = assertNotNull(features[id])
            assertEquals(ProviderFeatureCategory.Music, feature.category)
            assertFalse(feature.requiresLogin)
        }

        client.close()
    }

    @Test
    fun mapsDiscoveryResponsesIntoExistingModels() = runTest {
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val json = when (request.url.encodedPath) {
                        "/weapi/personalized/newsong" -> """
                            {"code":200,"result":[{"id":7,"name":"推荐新歌","song":{"id":7,"name":"推荐新歌","artists":[{"id":17,"name":"推荐歌手"}],"album":{"id":27,"name":"推荐专辑","picUrl":"recommended-song-cover"}}}]}
                        """.trimIndent()
                        "/weapi/v1/discovery/new/songs" -> """
                            {"code":200,"data":[{"id":1,"name":"新歌","artists":[{"id":11,"name":"歌手"}],"album":{"id":21,"name":"专辑","picUrl":"song-cover"}}]}
                        """.trimIndent()
                        "/weapi/album/new" -> """
                            {"code":200,"albums":[{"id":2,"name":"新碟","picUrl":"album-cover"}],"total":1}
                        """.trimIndent()
                        "/weapi/artist/top" -> """
                            {"code":200,"artists":[{"id":3,"name":"热门歌手","picUrl":"artist-cover"}],"more":false}
                        """.trimIndent()
                        "/weapi/playlist/highquality/list" -> """
                            {"code":200,"playlists":[{"id":4,"name":"精品歌单","coverImgUrl":"playlist-cover","trackCount":20}],"more":false}
                        """.trimIndent()
                        "/weapi/personalized/mv" -> """
                            {"code":200,"result":[{"id":5,"name":"推荐 MV","artistName":"MV 歌手","picUrl":"mv-cover","duration":123000}]}
                        """.trimIndent()
                        "/weapi/mv/toplist" -> """
                            {"code":200,"data":[{"id":6,"name":"排行 MV","artists":[{"name":"排行歌手"}],"cover":"top-mv-cover","duration":234000}],"hasMore":false}
                        """.trimIndent()
                        else -> error("unexpected NetEase request: ${request.url}")
                    }
                    respond(json)
                }
            }
        }
        val client = ProviderHttpClient(httpClient = httpClient)
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val features = provider.features.associateBy { it.id }

        val recommendedSongs = provider.loadFeature(assertNotNull(features["netease_recommended_new_songs"]), 0, 20)
        assertEquals("推荐新歌", recommendedSongs.tracks.single().title)
        assertEquals("推荐歌手", recommendedSongs.tracks.single().artists)

        val songs = provider.loadFeature(assertNotNull(features["netease_new_songs"]), 0, 20)
        assertEquals("新歌", songs.tracks.single().title)
        assertEquals("歌手", songs.tracks.single().artists)

        val albums = provider.loadFeature(assertNotNull(features["netease_new_albums"]), 0, 20)
        assertEquals("新碟", albums.mediaItems.single().title)
        assertEquals(ProviderMediaItemType.Album, albums.mediaItems.single().type)

        val artists = provider.loadFeature(assertNotNull(features["netease_top_artists"]), 0, 20)
        assertEquals("热门歌手", artists.mediaItems.single().title)
        assertEquals(ProviderMediaItemType.Artist, artists.mediaItems.single().type)

        val playlists = provider.loadFeature(assertNotNull(features["netease_highquality_playlists"]), 0, 20)
        assertEquals("精品歌单", playlists.playlists.single().title)

        val recommendedMvs = provider.loadFeature(assertNotNull(features["netease_recommended_mvs"]), 0, 20)
        assertEquals("推荐 MV", recommendedMvs.videos.single().title)
        assertEquals("MV 歌手", recommendedMvs.videos.single().artists)

        val topMvs = provider.loadFeature(assertNotNull(features["netease_top_mvs"]), 0, 20)
        assertEquals("排行 MV", topMvs.videos.single().title)
        assertEquals("排行歌手", topMvs.videos.single().artists)

        client.close()
    }

    @Test
    fun includesMvResultsInNeteaseSearch() = runTest {
        var requestIndex = 0
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestIndex += 1
                    val json = when (requestIndex) {
                        1 -> "{\"code\":200,\"result\":{\"songs\":[]}}"
                        2 -> "{\"code\":200,\"result\":{\"playlists\":[]}}"
                        3 -> "{\"code\":200,\"result\":{\"artists\":[]}}"
                        4 -> "{\"code\":200,\"result\":{\"albums\":[]}}"
                        5 -> """
                            {"code":200,"result":{"mvs":[{"id":99,"name":"搜索 MV","artistName":"搜索歌手","cover":"search-cover","duration":345000}]}}
                        """.trimIndent()
                        else -> error("unexpected search request #$requestIndex")
                    }
                    respond(json)
                }
            }
        }
        val client = ProviderHttpClient(httpClient = httpClient)
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())

        val result = provider.search("test")

        assertEquals(5, requestIndex)
        assertTrue(result.tracks.isEmpty())
        assertEquals("搜索 MV", result.videos.single().title)
        assertEquals("搜索歌手", result.videos.single().artists)
        client.close()
    }
}
