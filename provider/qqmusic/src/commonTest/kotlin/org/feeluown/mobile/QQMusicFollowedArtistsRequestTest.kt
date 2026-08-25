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
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.qqmusic.QQMusicCompositeProvider

class QQMusicFollowedArtistsRequestTest {
    @Test
    fun followedArtistsUseCurrentWebMusicuContext() = runTest {
        var relationRequest = ""
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
                                relationRequest = request.url.parameters["data"].orEmpty()
                                respond(
                                    """
                                    {
                                      "followedArtists":{
                                        "code":0,
                                        "data":{
                                          "Total":1,
                                          "HasMore":false,
                                          "List":[{
                                            "MID":"artist-mid",
                                            "Name":"关注歌手",
                                            "AvatarUrl":"https://example.test/artist.jpg"
                                          }]
                                        }
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
            ProviderCredentials(cookies = mapOf("uin" to "123456", "qqmusic_key" to "test-key")),
        )
        val provider = QQMusicCompositeProvider(ProviderRuntimeDependencies(http, credentials))
        val feature = provider.features.first { it.id == "qqmusic_followed_artists" }

        val section = provider.loadFeature(feature, 0, 20)

        assertEquals(listOf("关注歌手"), section.mediaItems.map { it.title })
        assertTrue(relationRequest.contains("\"HostUin\":\"encrypted-user\""), relationRequest)
        assertTrue(relationRequest.contains("\"ct\":24"), relationRequest)
        assertTrue(relationRequest.contains("\"cv\":4747474"), relationRequest)
        assertTrue(relationRequest.contains("\"uin\":\"123456\""), relationRequest)
        assertTrue(relationRequest.contains("\"g_tk_new_20200303\""), relationRequest)
        assertTrue(relationRequest.contains("\"platform\":\"yqq.json\""), relationRequest)
        assertTrue(relationRequest.contains("\"needNewCode\":1"), relationRequest)
        http.close()
    }
}
