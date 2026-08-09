package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.feeluown.mobile.provider.bilibili.BilibiliContentProvider
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

class BilibiliContentRepositoryTest {
    @Test
    fun groupsHistoryWithWatchLaterAndLoadsHistoryAsPlaylistDetail() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/x/web-interface/nav" -> respond(loggedInNav())
                            "/x/v2/history/toview/web" -> respond(
                                """{"code":0,"data":{"count":1,"list":[{"bvid":"BVlater","title":"稍后视频","duration":90,"owner":{"name":"稍后UP"},"pic":"https://example.test/later.jpg"}]}}""",
                            )
                            "/x/web-interface/history/cursor" -> {
                                assertEquals("archive", request.url.parameters["type"])
                                respond(
                                    """{"code":0,"data":{"cursor":{"max":123,"view_at":456,"business":"archive","ps":20},"list":[{"title":"历史视频","cover":"https://example.test/history.jpg","duration":100,"author_name":"历史UP","tag_name":"音乐","history":{"bvid":"BVhistory","page":1,"part":"正片","business":"archive"}}]}}""",
                                )
                            }
                            else -> error("unexpected request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        val delegate = KotlinProviderRepository(client, credentials)
        delegate.updateEnabledProviders(setOf("bilibili"))
        val repository = BilibiliContentRepository(
            delegate = delegate,
            bilibili = BilibiliContentProvider(client, credentials),
        )
        repository.loginWithCookies("bilibili", """{"SESSDATA":"session","bili_jct":"csrf"}""")

        val features = repository.features()
        assertFalse(features.any { it.id == "bilibili_history" })
        val viewingFeature = features.first { it.id == "bilibili_watch_later" }
        assertEquals("观看记录", viewingFeature.title)

        val section = repository.loadFeature(viewingFeature)
        assertEquals(listOf("稍后再看", "历史记录"), section.playlists.map { it.title })

        val historyPlaylist = section.playlists.last()
        val historyDetail = repository.playlistDetail(historyPlaylist)
        assertEquals("历史记录", historyDetail.playlist.title)
        assertEquals("BVhistory", historyDetail.tracks.single().providerId?.substringAfterLast(':'))

        client.close()
    }

    private fun loggedInNav(): String =
        """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"tester","wbi_img":{"img_url":"https://example.test/wbi/abcdefghijklmnopqrstuvwxyz.png","sub_url":"https://example.test/wbi/0123456789abcdefghijklmnopqrstuvwxyz.png"}}}"""
}
