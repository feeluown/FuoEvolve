package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.bilibili.BilibiliContentProvider
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

class BilibiliContentProviderTest {
    @Test
    fun exposesPlannedContentFeaturesWithoutLive() {
        val provider = BilibiliContentProvider(
            ProviderHttpClient(HttpClient(MockEngine) { engine { addHandler { respond("{}") } } }),
            InMemoryProviderCredentialStore(),
        )

        val ids = provider.features.map { it.id }.toSet()
        assertTrue("bilibili_recommended_videos" in ids)
        assertTrue("bilibili_weekly_must_watch" in ids)
        assertTrue("bilibili_watch_later" in ids)
        assertTrue("bilibili_history" in ids)
        assertTrue("bilibili_dynamic_videos" in ids)
        assertTrue("bilibili_followed_creators" in ids)
        assertTrue("bilibili_collected_media" in ids)
        assertFalse(ids.any { "live" in it })
    }

    @Test
    fun loadsWeeklyMustWatchAndWeeklyDetail() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/x/web-interface/popular/series/list" -> respond(
                                """{"code":0,"data":{"list":[{"number":301,"subject":"本周热门","name":"2026第31期"},{"number":300,"subject":"上周热门","name":"2026第30期"}]}}""",
                            )
                            "/x/web-interface/popular/series/one" -> {
                                when (request.url.parameters["number"]) {
                                    "301" -> respond(
                                        """{"code":0,"data":{"config":{"number":301,"subject":"本周热门","cover":"//example.test/weekly-cover.jpg"},"list":[{"bvid":"BVweekly1","title":"每周视频","duration":123,"owner":{"name":"UP主"},"pic":"//example.test/weekly.jpg"}]}}""",
                                    )
                                    "300" -> respond(
                                        """{"code":0,"data":{"config":{"number":300,"subject":"上周热门"},"list":[{"bvid":"BVweekly2","title":"上周视频","duration":90,"owner":{"name":"上周UP"},"pic":"//example.test/weekly-2.jpg"}]}}""",
                                    )
                                    else -> error("unexpected weekly number: ${request.url.parameters["number"]}")
                                }
                            }
                            else -> error("unexpected request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = BilibiliContentProvider(client, InMemoryProviderCredentialStore())
        val feature = provider.features.first { it.id == "bilibili_weekly_must_watch" }

        val section = provider.loadFeature(feature, 0, 1)
        assertEquals("本周热门", section.playlists.single().title)
        assertEquals(null, section.playlists.single().coverUrl)
        assertEquals("BVweekly1", section.tracks.single().providerId?.substringAfterLast(':'))
        assertTrue(section.hasMore)

        val detail = provider.playlistDetail(section.playlists.single(), 0, 20)
        assertEquals("https://example.test/weekly-cover.jpg", detail.playlist.coverUrl)
        assertEquals("BVweekly1", detail.tracks.single().providerId?.substringAfterLast(':'))
        assertEquals("UP主", detail.tracks.single().artists)
        assertEquals(123_000, detail.tracks.single().durationMs)

        val secondWeek = provider.loadFeature(feature.copy(id = "${feature.id}|number=300"), 0, 1)
        assertEquals("BVweekly2", secondWeek.tracks.single().providerId?.substringAfterLast(':'))
        client.close()
    }

    @Test
    fun loadsWatchLaterHistoryAndDynamicVideoOnlyContent() = runTest {
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
                            "/x/polymer/web-dynamic/v1/feed/all" -> {
                                assertEquals("video", request.url.parameters["type"])
                                respond(
                                    """{"code":0,"data":{"has_more":false,"offset":"next","items":[{"modules":{"module_author":{"name":"动态UP"},"module_dynamic":{"major":{"archive":{"bvid":"BVdynamic","title":"动态视频","cover":"https://example.test/dynamic.jpg","duration_text":"03:21"}}}}},{"modules":{"module_dynamic":{"major":{"live_rcmd":{"content":"ignored"}}}}}]}}""",
                                )
                            }
                            else -> error("unexpected request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = BilibiliContentProvider(client, credentials)
        provider.loginWithCookies("""{"SESSDATA":"session","bili_jct":"csrf"}""")

        val laterFeature = provider.features.first { it.id == "bilibili_watch_later" }
        val laterSection = provider.loadFeature(laterFeature, 0, 20)
        assertEquals("稍后再看", laterSection.playlists.single().title)
        assertEquals(1, laterSection.playlists.single().trackCount)
        assertEquals("BVlater", provider.playlistDetail(laterSection.playlists.single(), 0, 20).tracks.single().providerId?.substringAfterLast(':'))

        val historyFeature = provider.features.first { it.id == "bilibili_history" }
        val history = provider.loadFeature(historyFeature, 0, 20)
        assertEquals("BVhistory", history.tracks.single().providerId?.substringAfterLast(':'))

        val dynamicFeature = provider.features.first { it.id == "bilibili_dynamic_videos" }
        val dynamic = provider.loadFeature(dynamicFeature, 0, 20)
        assertEquals(listOf("BVdynamic"), dynamic.tracks.map { it.providerId?.substringAfterLast(':') })
        assertEquals(201_000, dynamic.tracks.single().durationMs)
        client.close()
    }

    @Test
    fun loadsFollowedCreatorsCreatorUploadsAndCollectedSeasons() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/x/web-interface/nav" -> respond(loggedInNav())
                            "/x/relation/followings" -> respond(
                                """{"code":0,"data":{"total":1,"list":[{"mid":100,"uname":"测试UP","face":"//example.test/up.jpg","sign":"UP签名"}]}}""",
                            )
                            "/x/space/wbi/acc/info" -> respond(
                                """{"code":0,"data":{"mid":100,"name":"测试UP","face":"https://example.test/up.jpg","sign":"UP签名"}}""",
                            )
                            "/x/space/wbi/arc/search" -> respond(
                                """{"code":0,"data":{"page":{"count":1},"list":{"vlist":[{"bvid":"BVupload","title":"UP投稿","author":"测试UP","pic":"https://example.test/upload.jpg","length":"04:00"}]}}}""",
                            )
                            "/x/space/bangumi/follow/list" -> {
                                assertEquals("1", request.url.parameters["type"])
                                respond(
                                    """{"code":0,"data":{"total":1,"list":[{"season_id":999,"media_id":888,"title":"收藏番剧","cover":"https://example.test/season.jpg","evaluate":"简介"}]}}""",
                                )
                            }
                            "/pgc/view/web/season" -> {
                                assertEquals("999", request.url.parameters["season_id"])
                                respond(
                                    """{"code":0,"result":{"season_id":999,"title":"收藏番剧","cover":"https://example.test/season.jpg","evaluate":"简介","episodes":[{"id":1,"bvid":"BVepisode","title":"1","long_title":"第一话","duration":1500000,"cover":"https://example.test/ep.jpg"}]}}""",
                                )
                            }
                            else -> error("unexpected request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = BilibiliContentProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"SESSDATA":"session","bili_jct":"csrf"}""")

        val creatorsFeature = provider.features.first { it.id == "bilibili_followed_creators" }
        val creator = provider.loadFeature(creatorsFeature, 0, 20).mediaItems.single()
        assertEquals("测试UP", creator.title)
        val creatorDetail = provider.mediaItemDetail(creator, 0, 0, 20)
        assertEquals("BVupload", creatorDetail.tracks.single().providerId?.substringAfterLast(':'))
        assertEquals(240_000, creatorDetail.tracks.single().durationMs)

        val mediaFeature = provider.features.first { it.id == "bilibili_collected_media" }
        val mediaSection = provider.loadFeature(mediaFeature, 0, 20)
        assertEquals("收藏番剧", mediaSection.mediaItems.single().title)
        assertTrue(ProviderFeatureFilterCodec.filters(mediaSection.feature.id).isNotEmpty())
        val seasonDetail = provider.mediaItemDetail(mediaSection.mediaItems.single(), 0, 0, 20)
        assertEquals("第一话", seasonDetail.tracks.single().title)
        assertEquals("BVepisode", seasonDetail.tracks.single().providerId?.substringAfterLast(':'))
        assertEquals(1_500_000, seasonDetail.tracks.single().durationMs)
        client.close()
    }

    private fun loggedInNav(): String =
        """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"tester","wbi_img":{"img_url":"https://example.test/wbi/abcdefghijklmnopqrstuvwxyz.png","sub_url":"https://example.test/wbi/0123456789abcdefghijklmnopqrstuvwxyz.png"}}}"""
}
