package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRetryPolicy
import org.feeluown.mobile.provider.ytmusic.YtMusicJsChallengeSolver
import org.feeluown.mobile.provider.ytmusic.YtMusicPlaybackExtractor

class YtMusicPlaybackProviderTest {
    @Test
    fun jsChallengeSolverExecutesSignatureAndNTransforms() = runTest {
        val solver = YtMusicJsChallengeSolver()
        val javascript = """
            var AB=function(a){a=a.split("");a.reverse();return a.join("")};
            var NZ=function(a){a=a.split("");a.reverse();return a.join("")};
            c&&(c=AB(decodeURIComponent(c)));
            x.get("n"))&&(b=NZ(b),x.set("n",b));
            var config={signatureTimestamp:20999};
        """.trimIndent()

        assertEquals(20999, solver.signatureTimestamp(javascript))
        assertEquals("dcba", solver.solveSignature(javascript, "abcd"))
        assertEquals("4321", solver.solveN(javascript, "1234"))
    }

    @Test
    fun resolveUsesVisionOsDirectAudioAndProbesCdn() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val providerHttp = http(requests) { request ->
            val url = request.url.toString()
            val body = (request.body as? TextContent)?.text.orEmpty()
            when {
                request.method == HttpMethod.Get && url.contains("youtube.com/watch") -> respond(WATCH_HTML)
                body.contains("\"clientName\":\"VISIONOS\"") -> respond(
                    """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":129000,"url":"https://cdn.example/vision.m4a","approxDurationMs":"10000","audioQuality":"AUDIO_QUALITY_MEDIUM"}]}}""",
                )
                url == "https://cdn.example/vision.m4a" -> respond(
                    "x",
                    status = HttpStatusCode.PartialContent,
                )
                else -> respond("""{"playabilityStatus":{"status":"UNPLAYABLE"}}""")
            }
        }
        val extractor = YtMusicPlaybackExtractor(
            providerHttp,
            InMemoryProviderCredentialStore(),
            poTokenProvider = { null },
        )

        val payload = extractor.resolve(track(), AudioQualityPolicy.High.policy)

        assertEquals("https://cdn.example/vision.m4a", payload?.url)
        assertTrue(payload?.headers.isNullOrEmpty())
        val player = requests.first { it.body.contains("VISIONOS") }
        assertEquals("101", player.headers["X-Youtube-Client-Name"])
        assertTrue(requests.any { it.url == "https://cdn.example/vision.m4a" && it.headers["Range"] == "bytes=0-0" })
        providerHttp.close()
    }

    @Test
    fun resolveFallsBackWhenFirstSignedUrlReturns403() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val providerHttp = http(requests) { request ->
            val url = request.url.toString()
            val body = (request.body as? TextContent)?.text.orEmpty()
            when {
                request.method == HttpMethod.Get && url.contains("youtube.com/watch") -> respond(WATCH_HTML)
                body.contains("\"clientName\":\"VISIONOS\"") -> respond(
                    playerWithAudio("https://cdn.example/blocked.m4a"),
                )
                url == "https://cdn.example/blocked.m4a" -> respond(
                    "forbidden",
                    status = HttpStatusCode.Forbidden,
                )
                body.contains("\"clientName\":\"TVHTML5\"") && body.contains("\"clientVersion\":\"5.20260707\"") -> respond(
                    playerWithAudio("https://cdn.example/tv.m4a"),
                )
                url == "https://cdn.example/tv.m4a" -> respond(
                    "x",
                    status = HttpStatusCode.PartialContent,
                )
                else -> respond("""{"playabilityStatus":{"status":"UNPLAYABLE"}}""")
            }
        }
        val extractor = YtMusicPlaybackExtractor(
            providerHttp,
            InMemoryProviderCredentialStore(),
            poTokenProvider = { null },
        )

        val payload = extractor.resolve(track(), AudioQualityPolicy.High.policy)

        assertEquals("https://cdn.example/tv.m4a", payload?.url)
        assertTrue(requests.any { it.url == "https://cdn.example/blocked.m4a" })
        assertTrue(requests.any { it.body.contains("\"clientVersion\":\"5.20260707\"") })
        providerHttp.close()
    }

    @Test
    fun resolveUsesHlsWhenAdaptiveFormatsAreSabrOnly() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val providerHttp = http(requests) { request ->
            val url = request.url.toString()
            val body = (request.body as? TextContent)?.text.orEmpty()
            when {
                request.method == HttpMethod.Get && url.contains("youtube.com/watch") -> respond(WATCH_HTML)
                body.contains("\"clientName\":\"VISIONOS\"") -> respond(
                    """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","bitrate":128000}],"hlsManifestUrl":"https://manifest.example/audio.m3u8"}}""",
                )
                url == "https://manifest.example/audio.m3u8" -> respond("#EXTM3U\n#EXT-X-VERSION:3")
                else -> respond("""{"playabilityStatus":{"status":"UNPLAYABLE"}}""")
            }
        }
        val extractor = YtMusicPlaybackExtractor(
            providerHttp,
            InMemoryProviderCredentialStore(),
            poTokenProvider = { null },
        )

        val payload = extractor.resolve(track(), AudioQualityPolicy.High.policy)

        assertNotNull(payload)
        assertEquals("https://manifest.example/audio.m3u8", payload.url)
        assertEquals("HLS", payload.audioQuality)
        providerHttp.close()
    }

    private fun http(
        requests: MutableList<CapturedRequest>,
        handler: suspend (io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData,
    ): ProviderHttpClient = ProviderHttpClient(
        httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += capture(request)
                    handler(request)
                }
            }
        },
        retryPolicy = ProviderRetryPolicy(maxRetries = 0),
    )

    private fun track() = MusicTrack(
        id = "ytmusic:vid1",
        title = "T",
        artists = "A",
        album = "",
        source = "ytmusic",
        sourceType = TrackSourceType.Provider,
        providerId = "ytmusic:vid1",
        providerName = "YouTube Music",
    )

    private fun playerWithAudio(url: String): String =
        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","bitrate":128000,"url":"$url","approxDurationMs":"10000"}]}}"""

    private data class CapturedRequest(
        val method: HttpMethod,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun capture(request: io.ktor.client.request.HttpRequestData): CapturedRequest = CapturedRequest(
        method = request.method,
        url = request.url.toString(),
        headers = request.headers.entries().associate { it.key to it.value.joinToString(",") },
        body = (request.body as? TextContent)?.text.orEmpty(),
    )

    private companion object {
        const val WATCH_HTML = """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","VISITOR_DATA":"visitor-token"});</html>"""
    }
}
