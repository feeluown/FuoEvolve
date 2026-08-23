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
import org.feeluown.mobile.provider.netease.NeteaseProvider

class NeteasePlaylistPermissionTest {
    @Test
    fun operationTargetsExcludeSubscribedPlaylists() = runTest {
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/api/user/playlist/" -> respond(
                                """
                                {
                                  "code":200,
                                  "playlist":[
                                    {"id":1,"name":"我的歌单","subscribed":false,"specialType":0},
                                    {"id":2,"name":"收藏的歌单","subscribed":true,"specialType":0}
                                  ]
                                }
                                """.trimIndent(),
                            )
                            else -> error("unexpected NetEase request: ${request.url}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(http, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie","__csrf":"csrf"}""")
        val track = MusicTrack(
            id = "track:netease:456",
            title = "测试歌曲",
            artists = "测试歌手",
            album = "测试专辑",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "track:netease:456",
            providerName = "网易云音乐",
        )

        val targets = provider.playlistOperationTargets(track)

        assertEquals(listOf("我的歌单"), targets.map { it.title })
        assertTrue(targets.single().isOwnedByCurrentUser == true)
        assertFalse(targets.single().isSubscribed == true)
        assertTrue(targets.single().canAddTracks == true)
        assertTrue(targets.single().canDelete == true)
        http.close()
    }
}
