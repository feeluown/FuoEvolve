package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.bilibili.BilibiliProvider
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.netease.NeteaseProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicProvider

class ProviderPlaylistFeatureTest {
    @Test
    fun bilibiliLoadsCreatedAndCollectedFavoriteFolders() = runTest {
        val requestedPaths = mutableListOf<String>()
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val path = request.url.encodedPath
                        requestedPaths += path
                        respond(
                            when (path) {
                                "/x/web-interface/nav" ->
                                    """{"code":0,"data":{"isLogin":true,"mid":12345,"uname":"tester"}}"""
                                "/x/v3/fav/folder/created/list-all" ->
                                    """{"code":0,"data":{"count":1,"list":[{"id":10001,"mid":12345,"title":"我的收藏","media_count":2}]}}"""
                                "/x/v3/fav/folder/collected/list" ->
                                    """{"code":0,"data":{"count":1,"list":[{"id":20001,"mid":67890,"title":"收藏合集","cover":"//example.test/cover.jpg","media_count":1}]}}"""
                                "/x/v3/fav/resource/list" ->
                                    """{"code":0,"data":{"info":{"id":10001,"mid":12345,"title":"我的收藏","media_count":2},"medias":[{"bvid":"BV1demo","title":"示例视频","cover":"//example.test/video.jpg","duration":120,"upper":{"name":"UP主"}}],"has_more":true}}"""
                                else -> error("unexpected Bilibili request: $path")
                            },
                        )
                    }
                }
            },
        )
        val provider = BilibiliProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"SESSDATA":"session","bili_jct":"csrf"}""")

        val created = provider.loadFeature(provider.features.single { it.id == "bilibili_user_playlists" }, 0, 50)
        val collected = provider.loadFeature(provider.features.single { it.id == "bilibili_favorite_playlists" }, 0, 50)
        val detail = provider.playlistDetail(created.playlists.single(), 0, 50)

        assertEquals("我的收藏", created.playlists.single().title)
        assertEquals("收藏合集", collected.playlists.single().title)
        assertEquals("bilibili:BV1demo", detail.tracks.single().providerId)
        assertEquals(120_000L, detail.tracks.single().durationMs)
        assertTrue(detail.tracksHasMore)
        assertTrue("/x/v3/fav/folder/created/list-all" in requestedPaths)
        assertTrue("/x/v3/fav/folder/collected/list" in requestedPaths)
        assertTrue("/x/v3/fav/resource/list" in requestedPaths)

        client.close()
    }

    @Test
    fun neteaseUsesWeApiUserIdForPlaylistFeatures() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> {
                                assertEquals("POST", request.method.value)
                                respond("""{"code":200,"data":{"userId":12345}}""")
                            }
                            "/weapi/share/userprofile/info" -> {
                                assertEquals("POST", request.method.value)
                                respond("""{"code":200,"nickname":"tester"}""")
                            }
                            "/api/user/playlist/" -> {
                                assertEquals("GET", request.method.value)
                                assertEquals("12345", request.url.parameters["uid"])
                                respond("""{"code":200,"playlist":[{"id":1,"name":"我的歌单","creator":{"nickname":"歌单作者"},"subscribed":false},{"id":2,"name":"收藏的歌单","subscribed":true}]}""")
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")
        val state = provider.authState()
        val section = provider.loadFeature(provider.features.single { it.id == "netease_user_playlists" }, 0, 50)
        val favoriteSection = provider.loadFeature(provider.features.single { it.id == "netease_favorite_playlists" }, 0, 50)

        assertTrue(state.isLoggedIn)
        assertEquals("tester", state.userName)
        assertFalse(section.isLoginRequired)
        assertEquals("我的歌单", section.playlists.single().title)
        assertEquals("歌单作者", section.playlists.single().description)
        assertEquals(null, section.errorMessage)
        assertEquals("收藏的歌单", favoriteSection.playlists.single().title)
        assertEquals(null, favoriteSection.errorMessage)

        client.close()
    }

    @Test
    fun neteaseLoadsRecommendedPlaylistsFromCurrentEndpoint() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/api/personalized/playlist" -> {
                                assertEquals("GET", request.method.value)
                                assertEquals("50", request.url.parameters["limit"])
                                respond("""{"code":200,"result":[{"id":42,"name":"推荐歌单","trackCount":2}]}""")
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")

        val section = provider.loadFeature(provider.features.single { it.id == "netease_daily_playlists" }, 0, 50)

        assertFalse(section.isLoginRequired)
        assertEquals("推荐歌单", section.playlists.single().title)
        assertEquals(2, section.playlists.single().trackCount)

        client.close()
    }

    @Test
    fun qqmusicLoadsRecommendedPlaylistsFromRpc() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
                        assertEquals("POST", request.method.value)
                        respond(
                            """{"code":0,"recomPlaylist":{"code":0,"data":{"v_hot":[{"content_id":987,"title":"QQ 推荐歌单","cover":"https://example.test/cover.jpg","listen_num":1234,"rcmdtemplate":"编辑推荐"}]}}}""",
                        )
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"qqmusic_key":"key","wxuin":"o12345","qm_keyst":"keyst"}""")

        val section = provider.loadFeature(
            provider.features.single { it.id == "qqmusic_daily_playlists" },
            0,
            50,
        )

        assertFalse(section.isLoginRequired)
        assertEquals("QQ 推荐歌单", section.playlists.single().title)
        assertEquals(987L, section.playlists.single().id.substringAfterLast(':').toLong())
        assertEquals(1234L, section.playlists.single().playCount)

        client.close()
    }

    @Test
    fun qqmusicLoadsUserPlaylistsFromProfileEndpoint() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/rsc/fcgi-bin/fcg_get_profile_homepage.fcg", request.url.encodedPath)
                        assertEquals("12345", request.url.parameters["userid"])
                        assertEquals("205360838", request.url.parameters["cid"])
                        respond(
                            """{"code":0,"data":{"creator":{"fav_pid":99},"mydiss":{"list":[{"dissid":123,"title":"我的歌单","logo":"https://example.test/playlist.jpg","songnum":2}]}}}""",
                        )
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"qqmusic_key":"key","wxuin":"o12345","qm_keyst":"keyst"}""")

        val section = provider.loadFeature(
            provider.features.single { it.id == "qqmusic_user_playlists" },
            0,
            50,
        )

        assertFalse(section.isLoginRequired)
        assertEquals(listOf("我喜欢", "我的歌单"), section.playlists.map { it.title })
        assertEquals(2, section.playlists[1].trackCount)
        assertEquals("https://example.test/playlist.jpg", section.playlists[1].coverUrl)

        client.close()
    }

    @Test
    fun neteaseLoadsPlaylistTracksFromResultEnvelope() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/api/playlist/detail", request.url.encodedPath)
                        respond(
                            """{"code":200,"result":{"id":123,"name":"示例歌单","trackCount":1,"tracks":[{"id":456,"name":"示例歌曲","ar":[{"id":7,"name":"示例歌手"}],"al":{"id":8,"name":"示例专辑","picUrl":"https://example.test/cover.jpg"},"dt":180000}]}}""",
                        )
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val playlist = ProviderPlaylist(
            id = "playlist:netease:123",
            title = "示例歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )

        val detail = provider.playlistDetail(playlist, 0, 50)

        assertEquals("示例歌单", detail.playlist.title)
        assertEquals("netease:456", detail.tracks.single().id)
        assertEquals("示例歌曲", detail.tracks.single().title)

        client.close()
    }

    @Test
    fun neteaseRejectedCookiesAreNotReportedAsLoggedIn() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond("""{"code":301,"message":"需要登录"}""")
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"expired-cookie"}""")
        val state = provider.authState()
        val section = provider.loadFeature(provider.features.single { it.id == "netease_user_playlists" }, 0, 50)

        assertFalse(state.isLoggedIn)
        assertTrue(section.isLoginRequired)

        client.close()
    }
}
