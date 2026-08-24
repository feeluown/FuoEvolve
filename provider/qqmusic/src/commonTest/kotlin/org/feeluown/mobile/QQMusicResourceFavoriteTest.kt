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
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.qqmusic.QQMusicCompositeProvider

class QQMusicResourceFavoriteTest {
    @Test
    fun resourceFavoritesUseLibraryStateAndWriteEndpoints() = runTest {
        val requestedOperations = mutableListOf<String>()
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" -> respond(
                                """
                                {
                                  "code":0,
                                  "data":{
                                    "creator":{
                                      "nick":"测试用户",
                                      "encrypt_uin":"encrypted-user"
                                    },
                                    "mymusic":[],
                                    "mydiss":{"list":[]}
                                  }
                                }
                                """.trimIndent(),
                            )
                            "/cgi-bin/musicu.fcg" -> {
                                val data = request.url.parameters["data"].orEmpty()
                                when {
                                    data.contains("CgiGetPlaylistFavInfo") -> respond(
                                        """
                                        {"favoritePlaylists":{"code":0,"data":{"total":1,"hasmore":0,"v_list":[{"tid":7001,"title":"收藏歌单"}]}}}
                                        """.trimIndent(),
                                    )
                                    data.contains("GetFollowSingerList") -> respond(
                                        """
                                        {"followedArtists":{"code":0,"data":{"Total":1,"HasMore":false,"List":[{"MID":"artist-mid","Name":"关注歌手"}]}}}
                                        """.trimIndent(),
                                    )
                                    data.contains("CgiGetAlbumFavInfo") -> respond(
                                        """
                                        {"favoriteAlbums":{"code":0,"data":{"total":1,"hasmore":0,"v_list":[{"id":9001,"mid":"album-mid","name":"收藏专辑"}]}}}
                                        """.trimIndent(),
                                    )
                                    data.contains("FavPlaylist") || data.contains("CancelFavPlaylist") -> {
                                        requestedOperations += if (data.contains("CancelFavPlaylist")) "playlist-unfavorite" else "playlist-favorite"
                                        respond("""{"favoritePlaylistWrite":{"code":0,"data":{"result":0}}}""")
                                    }
                                    data.contains("FavAlbum") || data.contains("CancelFavAlbum") -> {
                                        assertTrue(data.contains("\"v_albumMid\":[\"album-mid\"]"))
                                        assertFalse(data.contains("\"v_albumId\""))
                                        requestedOperations += if (data.contains("CancelFavAlbum")) "album-unfavorite" else "album-favorite"
                                        respond("""{"favoriteAlbumWrite":{"code":0,"data":{"result":0}}}""")
                                    }
                                    else -> error("unexpected QQ Music RPC: $data")
                                }
                            }
                            "/rsc/fcgi-bin/fcg_order_singer_del.fcg" -> {
                                assertEquals("artist-mid", request.url.parameters["singermid"])
                                requestedOperations += "artist-unfavorite"
                                respond("""{"code":0}""")
                            }
                            else -> error("unexpected QQ Music request: ${request.url}")
                        }
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        credentials.write(
            "qqmusic",
            ProviderCredentials(
                cookies = mapOf(
                    "uin" to "123456",
                    "encryptUin" to "encrypted-user",
                    "qqmusic_key" to "test-key",
                ),
            ),
        )
        val provider = QQMusicCompositeProvider(ProviderRuntimeDependencies(http, credentials))

        val playlist = provider.resourceState("playlist", "playlist:qqmusic:7001")
        val artist = provider.resourceState("artist", "artist:qqmusic:artist-mid")
        val album = provider.resourceState("album", "album:qqmusic:album-mid")

        assertTrue(playlist.isFavorite)
        assertTrue(artist.isFavorite)
        assertTrue(album.isFavorite)
        assertTrue(playlist.canUnfavorite)
        assertTrue(artist.canUnfavorite)
        assertTrue(album.canUnfavorite)

        assertTrue(provider.setResourceFavorite("playlist", "playlist:qqmusic:7001", false).success)
        assertTrue(provider.setResourceFavorite("artist", "artist:qqmusic:artist-mid", false).success)
        assertTrue(provider.setResourceFavorite("album", "album:qqmusic:album-mid", false).success)

        assertEquals(
            setOf("playlist-unfavorite", "artist-unfavorite", "album-unfavorite"),
            requestedOperations.toSet(),
        )
        assertTrue(provider.capabilities.canFavoritePlaylist)
        assertTrue(provider.capabilities.canUnfavoriteArtist)
        assertTrue(provider.capabilities.canFavoriteAlbum)
        http.close()
    }

    @Test
    fun syntheticToplistDoesNotExposeFavoriteCapability() = runTest {
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        error("synthetic toplist should not issue favorite requests: ${request.url}")
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        credentials.write(
            "qqmusic",
            ProviderCredentials(cookies = mapOf("uin" to "123456", "qqmusic_key" to "test-key")),
        )
        val provider = QQMusicCompositeProvider(ProviderRuntimeDependencies(http, credentials))

        val state = provider.resourceState("playlist", "playlist:qqmusic:toplist:4:2026-08-24")
        val mutation = provider.setResourceFavorite(
            "playlist",
            "playlist:qqmusic:toplist:4:2026-08-24",
            true,
        )

        assertFalse(state.isFavorite)
        assertFalse(state.canFavorite)
        assertFalse(state.canUnfavorite)
        assertFalse(mutation.success)
        http.close()
    }
}
