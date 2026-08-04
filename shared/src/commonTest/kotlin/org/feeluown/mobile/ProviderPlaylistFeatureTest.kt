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
    fun neteaseUsesAccountUserIdForPlaylistFeatures() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/account" ->
                                respond("""{"code":200,"profile":{"userId":12345,"nickname":"tester"}}""")
                            "/api/user/playlist" -> {
                                assertEquals("12345", request.url.parameters["uid"])
                                respond("""{"code":200,"playlist":[{"id":1,"name":"我的歌单","subscribed":false}]}""")
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val state = provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")
        val section = provider.loadFeature(provider.features.single { it.id == "netease_user_playlists" }, 0, 50)

        assertTrue(state.isLoggedIn)
        assertFalse(section.isLoginRequired)
        assertEquals("我的歌单", section.playlists.single().title)
        assertEquals(null, section.errorMessage)

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
