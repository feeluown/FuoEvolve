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
import org.feeluown.mobile.provider.qqmusic.QQMusicArtistDetailProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicContentProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicUserLibrary

class ProviderPlaylistPermissionTest {
    @Test
    fun neteaseOperationTargetsExcludeSubscribedPlaylists() = runTest {
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

    @Test
    fun qqMineAndFavoritePlaylistsExposePerPlaylistPermissions() = runTest {
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
        val delegate = KotlinProviderRepository(http, credentials)
        val repository = QQMusicContentRepository(
            delegate = delegate,
            qqmusic = QQMusicContentProvider(http, credentials),
            userLibrary = QQMusicUserLibrary(http, credentials),
            artistDetails = QQMusicArtistDetailProvider(http, credentials),
        )
        repository.updateEnabledProviders(setOf("qqmusic"))
        repository.loginWithCookies(
            "qqmusic",
            """{"uin":"123456","qqmusic_key":"test-key"}""",
        )
        val features = repository.features()

        val mine = repository.loadFeaturePage(
            features.single { it.id == "qqmusic_user_playlists" },
            0,
            20,
        )
        val favorites = repository.loadFeaturePage(
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
