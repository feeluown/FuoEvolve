package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.qqmusic.QQMusicCompositeProvider

class QQMusicPlaylistPermissionTest {
    @Test
    fun mineAndFavoritePlaylistsExposePerPlaylistPermissions() = runTest {
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
                                      "fav_pid":"liked-tid",
                                      "encrypt_uin":"encrypted-user"
                                    },
                                    "mymusic":[
                                      {"id":"liked-tid","title":"我喜欢","num0":88}
                                    ],
                                    "mydiss":{
                                      "list":[
                                        {"dissid":"created-tid","title":"自建歌单","songnum":12}
                                      ]
                                    }
                                  }
                                }
                                """.trimIndent(),
                            )
                            "/cgi-bin/musicu.fcg" -> {
                                val data = request.url.parameters["data"].orEmpty()
                                assertTrue(data.contains("CgiGetPlaylistFavInfo"))
                                respond(
                                    """
                                    {
                                      "favoritePlaylists":{
                                        "code":0,
                                        "data":{
                                          "v_list":[
                                            {"tid":"other-tid","title":"收藏的他人歌单","songnum":31}
                                          ],
                                          "total":1
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                )
                            }
                            else -> error("unexpected QQ Music request: ${request.url}")
                        }
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(ProviderRuntimeDependencies(http, credentials))
        provider.loginWithCookies("""{"uin":"123456","qqmusic_key":"test-key"}""")
        val features = provider.features

        val mine = provider.loadFeature(
            features.single { it.id == "qqmusic_user_playlists" },
            0,
            20,
        )
        val favorites = provider.loadFeature(
            features.single { it.id == "qqmusic_favorite_playlists" },
            0,
            20,
        )

        val liked = mine.playlists.first { it.title == "我喜欢" }
        val created = mine.playlists.first { it.title == "自建歌单" }
        val subscribed = favorites.playlists.single()
        assertTrue(liked.isOwnedByCurrentUser == true)
        assertFalse(liked.canDelete == true)
        assertTrue(created.isOwnedByCurrentUser == true)
        assertTrue(created.canAddTracks == true)
        assertTrue(created.canDelete == true)
        assertTrue(subscribed.isSubscribed == true)
        assertFalse(subscribed.isOwnedByCurrentUser == true)
        assertFalse(subscribed.canAddTracks == true)
        assertFalse(subscribed.canRemoveTracks == true)
        assertFalse(subscribed.canDelete == true)
        http.close()
    }
}
