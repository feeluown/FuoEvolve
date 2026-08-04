package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
}
