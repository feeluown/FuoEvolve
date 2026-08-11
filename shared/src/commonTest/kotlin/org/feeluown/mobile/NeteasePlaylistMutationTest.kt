package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.netease.NeteaseProvider

class NeteasePlaylistMutationTest {
    @Test
    fun createPlaylistWaitsUntilUserPlaylistListContainsNewPlaylist() = runTest {
        var playlistRequests = 0
        val requestedPaths = mutableListOf<String>()
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val path = request.url.encodedPath
                        requestedPaths += path
                        when (path) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/api/playlist/create" -> respond("""{"code":200,"playlist":{"id":777,"name":"新歌单"}}""")
                            "/api/user/playlist/" -> {
                                playlistRequests += 1
                                val playlists = if (playlistRequests >= 3) {
                                    """[{"id":1,"name":"已有歌单","subscribed":false},{"id":777,"name":"新歌单","subscribed":false}]"""
                                } else {
                                    """[{"id":1,"name":"已有歌单","subscribed":false}]"""
                                }
                                respond("""{"code":200,"playlist":$playlists}""")
                            }
                            else -> error("unexpected NetEase request: $path")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie","__csrf":"csrf"}""")

        val result = provider.createPlaylist("新歌单")

        assertTrue(result.success)
        assertEquals(3, playlistRequests)
        assertTrue("/api/playlist/create" in requestedPaths)
        client.close()
    }

    @Test
    fun deletePlaylistUsesWeApiRemoveAndWaitsUntilPlaylistDisappears() = runTest {
        var playlistRequests = 0
        val requestedPaths = mutableListOf<String>()
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val path = request.url.encodedPath
                        requestedPaths += path
                        when (path) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/weapi/playlist/remove" -> respond("""{"code":200}""")
                            "/api/user/playlist/" -> {
                                playlistRequests += 1
                                val playlists = if (playlistRequests >= 3) {
                                    "[]"
                                } else {
                                    """[{"id":321,"name":"待删除歌单","subscribed":false}]"""
                                }
                                respond("""{"code":200,"playlist":$playlists}""")
                            }
                            else -> error("unexpected NetEase request: $path")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie","__csrf":"csrf"}""")
        val section = provider.loadFeature(
            provider.features.single { it.id == "netease_user_playlists" },
            0,
            50,
        )

        val result = provider.deletePlaylist(section.playlists.single())

        assertTrue(result.success)
        assertEquals(3, playlistRequests)
        assertTrue("/weapi/playlist/remove" in requestedPaths)
        assertTrue("/api/playlist/delete" !in requestedPaths)
        client.close()
    }
}
