package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.netease.NeteaseProvider

class NeteaseResourceFavoriteTest {
    @Test
    fun resourceFavoritesExposeStateAndMutationsForPlaylistArtistAndAlbum() = runTest {
        val requestedPaths = mutableListOf<String>()
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val path = request.url.encodedPath
                        requestedPaths += path
                        when (path) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/api/user/playlist/" -> respond(
                                """
                                {
                                  "code":200,
                                  "playlist":[
                                    {"id":1,"name":"我的歌单","subscribed":false},
                                    {"id":2,"name":"收藏歌单","subscribed":true}
                                  ]
                                }
                                """.trimIndent(),
                            )
                            "/weapi/artist/sublist" -> respond(
                                """{"code":200,"data":[{"id":9,"name":"收藏歌手"}],"hasMore":false}""",
                            )
                            "/weapi/album/sublist" -> respond(
                                """{"code":200,"data":[{"id":10,"name":"收藏专辑"}],"hasMore":false}""",
                            )
                            "/weapi/playlist/unsubscribe",
                            "/weapi/artist/unsub",
                            "/weapi/album/unsub",
                            -> respond("""{"code":200}""")
                            else -> error("unexpected NetEase request: $path")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(http, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie","__csrf":"csrf"}""")

        val ownPlaylist = provider.resourceState("playlist", "playlist:netease:1")
        val favoritePlaylist = provider.resourceState("playlist", "playlist:netease:2")
        val favoriteArtist = provider.resourceState("artist", "artist:netease:9")
        val favoriteAlbum = provider.resourceState("album", "album:netease:10")

        assertFalse(ownPlaylist.canFavorite)
        assertFalse(ownPlaylist.canUnfavorite)
        assertTrue(favoritePlaylist.isFavorite)
        assertTrue(favoritePlaylist.canUnfavorite)
        assertTrue(favoriteArtist.isFavorite)
        assertTrue(favoriteArtist.canUnfavorite)
        assertTrue(favoriteAlbum.isFavorite)
        assertTrue(favoriteAlbum.canUnfavorite)

        assertTrue(provider.setResourceFavorite("playlist", "playlist:netease:2", false).success)
        assertTrue(provider.setResourceFavorite("artist", "artist:netease:9", false).success)
        assertTrue(provider.setResourceFavorite("album", "album:netease:10", false).success)

        assertTrue("/weapi/playlist/unsubscribe" in requestedPaths)
        assertTrue("/weapi/artist/unsub" in requestedPaths)
        assertTrue("/weapi/album/unsub" in requestedPaths)
        assertTrue(provider.capabilities.canFavoritePlaylist)
        assertTrue(provider.capabilities.canUnfavoriteArtist)
        assertTrue(provider.capabilities.canFavoriteAlbum)
        http.close()
    }
}
