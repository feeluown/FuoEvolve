package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.qqmusic.QQMusicContentProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicUserLibrary

class QQMusicUserLibraryTest {
    @Test
    fun loginShowsUsernameAndMineLoadsProfileHomepagePlaylists() = runTest {
        var profileRequests = 0
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("c6.y.qq.com", request.url.host)
                        assertEquals("/rsc/fcgi-bin/fcg_get_profile_homepage.fcg", request.url.encodedPath)
                        assertEquals("123456", request.url.parameters["userid"])
                        assertEquals("123456", request.url.parameters["loginUin"])
                        profileRequests += 1
                        respond(
                            """
                            {
                              "code":0,
                              "data":{
                                "creator":{"nick":"测试用户","fav_pid":"liked-tid"},
                                "mymusic":[
                                  {"id":"liked-tid","title":"我喜欢","num0":88,"picurl":"https://example.test/liked.jpg"}
                                ],
                                "mydiss":{
                                  "list":[
                                    {"dissid":"created-tid","title":"自建歌单","songnum":12,"logo":"https://example.test/created.jpg"}
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
        assertEquals(2, profileRequests)
        http.close()
    }

    @Test
    fun existingWechatCookieUsesMusicIdWithoutRelogin() = runTest {
        var requestCount = 0
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/rsc/fcgi-bin/fcg_get_profile_homepage.fcg", request.url.encodedPath)
                        assertEquals("1152921505375946065", request.url.parameters["userid"])
                        assertEquals(null, request.url.parameters["hostuin"])
                        requestCount += 1
                        respond(
                            """
                            {
                              "code":0,
                              "data":{
                                "creator":{"nickname":"微信用户"},
                                "mymusic":[{"id":"wx-liked","title":"我喜欢","num0":66}],
                                "mydiss":{"list":[{"dissid":"wx-created","title":"微信歌单"}]}
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
            ProviderCredentials(
                cookies = mapOf(
                    "login_type" to "2",
                    "uin" to "oK-i7e4sN3x",
                    "wxuin" to "oEncryptedWxUin9x",
                    "str_musicid" to "1152921505375946065",
                    "wxopenid" to "openid",
                    "qm_keyst" to "test-key",
                ),
            ),
        )
        val library = QQMusicUserLibrary(http, credentials)
        val feature = mineFeature()

        assertEquals("微信用户", library.userName())
        val section = library.loadPlaylists(feature, 0, 20)

        assertEquals(listOf("我喜欢", "微信歌单"), section.playlists.map { it.title })
        assertEquals(2, requestCount)
        http.close()
    }

    @Test
    fun encryptedUinIsNeverCollapsedIntoFakeNumericHostuin() = runTest {
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/rsc/fcgi-bin/fcg_get_profile_homepage.fcg", request.url.encodedPath)
                        assertEquals("987654321", request.url.parameters["userid"])
                        respond(
                            """
                            {
                              "code":0,
                              "data":{
                                "creator":{"nick":"QQ 用户"},
                                "mymusic":[],
                                "mydiss":{"list":[]}
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
            ProviderCredentials(
                cookies = mapOf(
                    "uin" to "oK-i7e4sN3x",
                    "musicid" to "987654321",
                    "qqmusic_key" to "test-key",
                ),
            ),
        )

        val section = QQMusicUserLibrary(http, credentials).loadPlaylists(mineFeature(), 0, 20)

        assertTrue(section.playlists.isEmpty())
        assertEquals(null, section.errorMessage)
        http.close()
    }

    @Test
    fun numericQqFallsBackToLegacyCreatedListOnlyWhenProfileHasNoPlaylistPayload() = runTest {
        var createdRequests = 0
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" -> respond(
                                """
                                {
                                  "code":0,
                                  "data":{"creator":{"nick":"测试用户"}}
                                }
                                """.trimIndent(),
                            )
                            "/rsc/fcgi-bin/fcg_user_created_diss" -> {
                                assertEquals("123456", request.url.parameters["hostuin"])
                                createdRequests += 1
                                respond(
                                    """
                                    {
                                      "code":0,
                                      "data":{
                                        "hostname":"测试用户",
                                        "disslist":[
                                          {"dirid":201,"tid":"liked","diss_name":"我喜欢"},
                                          {"dirid":202,"tid":"created","diss_name":"自建歌单"}
                                        ]
                                      }
                                    }
                                    """.trimIndent(),
                                )
                            }
                            else -> error("unexpected request: ${request.url}")
                        }
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        credentials.write(
            "qqmusic",
            ProviderCredentials(cookies = mapOf("uin" to "o123456", "qqmusic_key" to "test-key")),
        )
        val library = QQMusicUserLibrary(http, credentials)

        val section = library.loadPlaylists(mineFeature(), 0, 20)

        assertEquals(listOf("我喜欢", "自建歌单"), section.playlists.map { it.title })
        assertEquals(1, createdRequests)
        http.close()
    }

    private fun mineFeature() = ProviderFeature(
        id = "qqmusic_user_playlists",
        providerId = "qqmusic",
        providerName = "QQ 音乐",
        title = "我的歌单",
        category = ProviderFeatureCategory.MinePlaylists,
        contentType = ProviderContentType.Playlists,
        requiresLogin = true,
    )
}
