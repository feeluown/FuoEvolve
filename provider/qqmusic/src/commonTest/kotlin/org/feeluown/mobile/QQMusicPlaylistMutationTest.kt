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
import org.feeluown.mobile.provider.qqmusic.QQMusicProvider

class QQMusicPlaylistMutationTest {
    @Test
    fun createPlaylistUsesPlaylistBaseWriteAndWaitsUntilMineContainsNewPlaylist() = runTest {
        var profileRequests = 0
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" -> {
                                profileRequests += 1
                                val created = if (profileRequests >= 3) {
                                    """[{"tid":"777","title":"新歌单","songnum":0}]"""
                                } else {
                                    "[]"
                                }
                                respond(
                                    """{"code":0,"data":{"creator":{"nick":"tester"},"mymusic":[],"mydiss":{"list":$created}}}""",
                                )
                            }
                            "/cgi-bin/musicu.fcg" -> {
                                val data = request.url.parameters["data"].orEmpty()
                                assertTrue(data.contains("music.musicasset.PlaylistBaseWrite"))
                                assertTrue(data.contains("AddPlaylist"))
                                assertTrue(data.contains("新歌单"))
                                respond(
                                    """{"req_0":{"code":0,"data":{"retCode":0,"result":{"dirId":301,"tid":777,"dirName":"新歌单"}}}}""",
                                )
                            }
                            else -> error("unexpected QQ Music request: ${request.url}")
                        }
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore().apply {
            write(
                "qqmusic",
                ProviderCredentials(cookies = mapOf("uin" to "123456", "qqmusic_key" to "test-key")),
            )
        }
        val provider = QQMusicProvider(http, credentials)

        val result = provider.createPlaylist("新歌单")

        assertTrue(result.success)
        assertEquals(3, profileRequests)
        assertTrue(provider.capabilities.canCreatePlaylist)
        http.close()
    }

    @Test
    fun deletePlaylistResolvesDisstidToDirIdAndWaitsUntilMineRemovesPlaylist() = runTest {
        var profileRequests = 0
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" -> {
                                assertEquals("777", request.url.parameters["disstid"])
                                respond(
                                    """{"code":0,"cdlist":[{"disstid":"777","dirid":301,"dissname":"待删除歌单","songlist":[]}]}""",
                                )
                            }
                            "/cgi-bin/musicu.fcg" -> {
                                val data = request.url.parameters["data"].orEmpty()
                                assertTrue(data.contains("music.musicasset.PlaylistBaseWrite"))
                                assertTrue(data.contains("DelPlaylist"))
                                assertTrue(data.contains("\"dirId\":301"))
                                respond(
                                    """{"req_0":{"code":0,"data":{"retCode":0,"result":{"dirId":301}}}}""",
                                )
                            }
                            "/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" -> {
                                profileRequests += 1
                                val created = if (profileRequests < 2) {
                                    """[{"tid":"777","title":"待删除歌单","songnum":0}]"""
                                } else {
                                    "[]"
                                }
                                respond(
                                    """{"code":0,"data":{"creator":{"nick":"tester"},"mymusic":[],"mydiss":{"list":$created}}}""",
                                )
                            }
                            else -> error("unexpected QQ Music request: ${request.url}")
                        }
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore().apply {
            write(
                "qqmusic",
                ProviderCredentials(cookies = mapOf("uin" to "123456", "qqmusic_key" to "test-key")),
            )
        }
        val provider = QQMusicProvider(http, credentials)
        val playlist = ProviderPlaylist(
            id = "playlist:qqmusic:777",
            title = "待删除歌单",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
            isOwnedByCurrentUser = true,
            isSubscribed = false,
            canDelete = true,
        )

        val result = provider.deletePlaylist(playlist)

        assertTrue(result.success)
        assertEquals(2, profileRequests)
        assertTrue(provider.capabilities.canDeletePlaylist)
        http.close()
    }
}
