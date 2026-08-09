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
import org.feeluown.mobile.provider.qqmusic.QQMusicContentProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicUserLibrary

class QQMusicUserLibraryTest {
    @Test
    fun loginShowsUsernameAndMineLoadsCreatedPlaylists() = runTest {
        var userLibraryRequests = 0
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/rsc/fcgi-bin/fcg_user_created_diss", request.url.encodedPath)
                        assertEquals("123456", request.url.parameters["hostuin"])
                        userLibraryRequests += 1
                        respond(
                            """
                            {
                              "code":0,
                              "data":{
                                "hostname":"测试用户",
                                "disslist":[
                                  {
                                    "dirid":201,
                                    "tid":"liked-tid",
                                    "diss_name":"我喜欢",
                                    "diss_cover":"https://example.test/liked.jpg",
                                    "song_cnt":88,
                                    "listen_num":123
                                  },
                                  {
                                    "dirid":202,
                                    "tid":"created-tid",
                                    "diss_name":"自建歌单",
                                    "diss_cover":"https://example.test/created.jpg",
                                    "song_cnt":12,
                                    "listen_num":45
                                  }
                                ]
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        val delegate = KotlinProviderRepository(http, credentials)
        val content = QQMusicContentProvider(http, credentials)
        val userLibrary = QQMusicUserLibrary(http, credentials)
        val repository = QQMusicContentRepository(delegate, content, userLibrary)
        repository.updateEnabledProviders(setOf("qqmusic"))

        val auth = repository.loginWithCookies(
            "qqmusic",
            """{"uin":"123456","qqmusic_key":"test-key"}""",
        )
        assertTrue(auth.isLoggedIn)
        assertEquals("测试用户", auth.userName)

        val feature = repository.features().first { it.id == "qqmusic_user_playlists" }
        val section = repository.loadFeaturePage(feature, 0, 20)

        assertEquals(listOf("我喜欢", "自建歌单"), section.playlists.map { it.title })
        assertEquals("playlist:qqmusic:liked-tid", section.playlists[0].id)
        assertEquals("playlist:qqmusic:created-tid", section.playlists[1].id)
        assertEquals(88, section.playlists[0].trackCount)
        assertEquals(2, userLibraryRequests)
        http.close()
    }

    @Test
    fun fallsBackToProfileHomepageForMissingFavoritePlaylist() = runTest {
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/rsc/fcgi-bin/fcg_user_created_diss" -> respond(
                                """
                                {
                                  "code":0,
                                  "data":{
                                    "hostname":"测试用户",
                                    "disslist":[
                                      {"dirid":202,"tid":"created-tid","diss_name":"自建歌单"}
                                    ]
                                  }
                                }
                                """.trimIndent(),
                            )
                            "/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" -> respond(
                                """
                                {
                                  "code":0,
                                  "data":{
                                    "creator":{"nick":"测试用户"},
                                    "mymusic":[{"id":"liked-fallback","num0":66}]
                                  }
                                }
                                """.trimIndent(),
                            )
                            else -> error("unexpected request: ${request.url}")
                        }
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        credentials.write(
            "qqmusic",
            org.feeluown.mobile.provider.core.ProviderCredentials(
                cookies = mapOf("uin" to "123456", "qqmusic_key" to "test-key"),
            ),
        )
        val library = QQMusicUserLibrary(http, credentials)
        val feature = ProviderFeature(
            id = "qqmusic_user_playlists",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
            title = "我的歌单",
            category = ProviderFeatureCategory.MinePlaylists,
            contentType = ProviderContentType.Playlists,
            requiresLogin = true,
        )

        val section = library.loadPlaylists(feature, 0, 20)

        assertEquals(listOf("我喜欢", "自建歌单"), section.playlists.map { it.title })
        assertEquals("playlist:qqmusic:liked-fallback", section.playlists.first().id)
        assertEquals(66, section.playlists.first().trackCount)
        http.close()
    }

    @Test
    fun normalizesQqUinBeforeLoadingUserLibrary() = runTest {
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("123456", request.url.parameters["hostuin"])
                        respond(
                            """
                            {
                              "code":0,
                              "data":{
                                "hostname":"QQ 用户",
                                "disslist":[{"dirid":201,"tid":"liked","diss_name":"我喜欢"}]
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        credentials.write(
            "qqmusic",
            org.feeluown.mobile.provider.core.ProviderCredentials(
                cookies = mapOf(
                    "login_type" to "1",
                    "uin" to "o12abc3456",
                    "wxuin" to "not-a-number",
                    "qqmusic_key" to "test-key",
                ),
            ),
        )

        val userName = QQMusicUserLibrary(http, credentials).userName()

        assertEquals("QQ 用户", userName)
        http.close()
    }

    @Test
    fun wechatLoginPrefersNormalizedWxuin() = runTest {
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("987654", request.url.parameters["hostuin"])
                        respond(
                            """
                            {
                              "code":0,
                              "data":{
                                "hostname":"微信用户",
                                "disslist":[{"dirid":201,"tid":"liked","diss_name":"我喜欢"}]
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        credentials.write(
            "qqmusic",
            org.feeluown.mobile.provider.core.ProviderCredentials(
                cookies = mapOf(
                    "login_type" to "2",
                    "uin" to "123456",
                    "wxuin" to "o987xyz654",
                    "qqmusic_key" to "test-key",
                ),
            ),
        )

        val userName = QQMusicUserLibrary(http, credentials).userName()

        assertEquals("微信用户", userName)
        http.close()
    }
}
