package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.bilibili.BilibiliProvider
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

class BilibiliProviderTest {
    @Test
    fun resolveSelectsBilibiliAudioQualityAndPreservesPlayableParts() = runTest {
        val playUrlRequests = mutableListOf<Pair<String, String?>>()
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/x/web-interface/view" -> respond(
                                """{"code":0,"data":{"bvid":"BVdemo","cid":123,"title":"示例歌曲","duration":265,"pages":[{"cid":123,"page":1,"part":"第一段","duration":265},{"cid":456,"page":2,"part":"第二段","duration":10}]}}""",
                            )
                            "/x/web-interface/nav" -> respond(
                                """{"code":0,"data":{"wbi_img":{"img_url":"https://example.test/wbi/abcdefghijklmnopqrstuvwxyz.png","sub_url":"https://example.test/wbi/0123456789abcdefghijklmnopqrstuvwxyz.png"}}}""",
                            )
                            "/x/player/playurl" -> {
                                assertEquals("16", request.url.parameters["fnval"])
                                assertNull(request.url.parameters["qn"])
                                playUrlRequests += request.url.parameters["cid"].orEmpty() to request.url.parameters["bvid"]
                                respond(
                                    """{"code":0,"data":{"dash":{"audio":[{"baseUrl":"https://example.test/lq.m4s","bandwidth":96084,"length":265000},{"base_url":"https://example.test/sq.m4s","bandwidth":192000,"length":265000},{"baseUrl":"https://example.test/hq.m4s","bandwidth":320000,"length":265000}],"flac":{"audio":{"base_url":"https://example.test/flac.m4s","bandwidth":500000,"length":265000}}}}}""",
                                )
                            }
                            else -> error("unexpected Bilibili request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = BilibiliProvider(client, InMemoryProviderCredentialStore())
        val track = MusicTrack(
            id = "bilibili:BVdemo",
            title = "示例歌曲",
            artists = "歌手",
            album = "专辑",
            source = "bilibili",
            sourceType = TrackSourceType.Provider,
            providerId = "bilibili:BVdemo",
            durationMs = 265_000,
        )

        val low = provider.resolve(track, AudioQualityPolicy.Low.policy)
        val standard = provider.resolve(track, AudioQualityPolicy.Standard.policy)
        val high = provider.resolve(track, AudioQualityPolicy.High.policy)
        val highest = provider.resolve(track, AudioQualityPolicy.Highest.policy)

        assertEquals("https://example.test/lq.m4s", low?.url)
        assertEquals("LQ", low?.audioQuality)
        assertEquals("https://example.test/sq.m4s", standard?.url)
        assertEquals("SQ", standard?.audioQuality)
        assertEquals("https://example.test/hq.m4s", high?.url)
        assertEquals("HQ", high?.audioQuality)
        assertEquals("https://example.test/flac.m4s", highest?.url)
        assertEquals("SHQ", highest?.audioQuality)
        assertEquals("https://www.bilibili.com/", highest?.headers?.get("Referer"))
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            highest?.headers?.get("User-Agent"),
        )
        assertEquals(
            listOf("bilibili:paged_BVdemo__1", "bilibili:paged_BVdemo__2"),
            highest?.parts?.map { it.id },
        )
        assertEquals(0, highest?.currentPartIndex)
        assertEquals(listOf("123", "123", "123", "123"), playUrlRequests.map { it.first })

        client.close()
    }

    @Test
    fun videoPlaybackUsesSeparateDashVideoAndAudioStreams() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/x/web-interface/view" -> respond(
                                """{"code":0,"data":{"bvid":"BVdemo","cid":123,"title":"示例视频","pages":[{"cid":123,"page":1,"part":"正片"}]}}""",
                            )
                            "/x/web-interface/nav" -> respond(
                                """{"code":0,"data":{"wbi_img":{"img_url":"https://example.test/wbi/abcdefghijklmnopqrstuvwxyz.png","sub_url":"https://example.test/wbi/0123456789abcdefghijklmnopqrstuvwxyz.png"}}}""",
                            )
                            "/x/player/playurl" -> {
                                assertEquals("16", request.url.parameters["fnval"])
                                assertEquals("123", request.url.parameters["cid"])
                                respond(
                                    """{"code":0,"data":{"dash":{"video":[{"baseUrl":"https://example.test/video-lq.m4s","bandwidth":500000},{"base_url":"https://example.test/video-hq.m4s","bandwidth":2000000}],"audio":[{"baseUrl":"https://example.test/audio-lq.m4s","bandwidth":96000},{"base_url":"https://example.test/audio-hq.m4s","bandwidth":320000}]}}}""",
                                )
                            }
                            else -> error("unexpected Bilibili request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = BilibiliProvider(client, InMemoryProviderCredentialStore())
        val video = ProviderVideo(
            id = "video:bilibili:BVdemo",
            title = "示例视频",
            providerId = "bilibili",
            providerName = "哔哩哔哩",
        )

        val payload = provider.videoPlaybackPayload(video)

        assertEquals("", payload.url)
        assertEquals("https://example.test/video-hq.m4s", payload.videoUrl)
        assertEquals("https://example.test/audio-hq.m4s", payload.audioUrl)
        assertEquals("video", payload.quality)
        client.close()
    }

    @Test
    fun invalidBilibiliPartDoesNotFallBackToFirstPart() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/x/web-interface/view", request.url.encodedPath)
                        respond(
                            """{"code":0,"data":{"bvid":"BVdemo","cid":123,"title":"示例视频","pages":[{"cid":123,"page":1,"part":"正片"}]}}""",
                        )
                    }
                }
            },
        )
        val provider = BilibiliProvider(client, InMemoryProviderCredentialStore())

        assertNull(provider.trackDetail("paged_BVdemo__2"))

        client.close()
    }

    @Test
    fun relatedBilibiliRequestUsesBvidAndFavoriteMutationUsesAvidAndCsrf() = runTest {
        val mutationBodies = mutableListOf<String>()
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/x/web-interface/archive/related" -> {
                                assertEquals("BVdemo", request.url.parameters["bvid"])
                                assertNull(request.url.parameters["aid"])
                                respond("""{"code":0,"data":[{"bvid":"BVrelated","title":"相关视频","author":"歌手"}]}""")
                            }
                            "/x/web-interface/view" -> respond(
                                """{"code":0,"data":{"bvid":"BVdemo","aid":12345,"cid":123,"title":"示例视频","pages":[{"cid":123,"page":1,"part":"正片"}]}}""",
                            )
                            "/x/v3/fav/resource/deal" -> {
                                assertEquals("POST", request.method.value)
                                mutationBodies += (request.body as TextContent).text
                                respond("""{"code":0,"message":"OK"}""")
                            }
                            else -> error("unexpected Bilibili request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = BilibiliProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"SESSDATA":"session","bili_jct":"csrf"}""")
        val track = MusicTrack(
            id = "bilibili:BVdemo",
            title = "示例视频",
            artists = "歌手",
            album = "",
            source = "bilibili",
            sourceType = TrackSourceType.Provider,
            providerId = "bilibili:BVdemo",
        )
        val playlist = ProviderPlaylist("playlist:bilibili:10001", "我的收藏", "bilibili", "哔哩哔哩")

        assertEquals("BVrelated", provider.similarTracks(track).single().providerId?.substringAfterLast(':'))
        assertTrue(provider.addTrackToPlaylist(playlist, track).success)
        assertTrue(provider.removeTrackFromPlaylist(playlist, track).success)
        assertTrue(mutationBodies[0].contains("rid=12345"), mutationBodies[0])
        assertTrue(mutationBodies[0].contains("add_media_ids=10001"), mutationBodies[0])
        assertTrue(mutationBodies[1].contains("del_media_ids=10001"), mutationBodies[1])
        assertTrue(mutationBodies.all { it.contains("type=2") && it.contains("csrf=csrf") })

        client.close()
    }
}
