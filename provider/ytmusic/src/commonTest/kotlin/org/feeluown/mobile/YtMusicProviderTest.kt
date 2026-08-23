package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRetryPolicy
import org.feeluown.mobile.provider.ytmusic.YtMusicProvider

class YtMusicProviderTest {
    @Test
    fun searchFallsBackWhenLandingPageDoesNotExposeApiKey() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        if (request.method == HttpMethod.Get) {
                            respond("<html>YouTube Music is not available in your area</html>")
                        } else {
                            respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, InMemoryProviderCredentialStore())

        assertEquals(emptyList(), provider.search("test").tracks)

        val apiRequest = requests.first { it.url.contains("/youtubei/v1/search") }
        assertTrue(apiRequest.url.contains("alt=json"))
        assertTrue(apiRequest.url.contains("key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"))
        assertFalse(apiRequest.body.contains("\"gl\""), apiRequest.body)
        assertTrue(apiRequest.body.contains("\"hl\":\"zh_CN\""), apiRequest.body)
        assertTrue(apiRequest.body.contains("\"user\":{}"), apiRequest.body)
        providerHttp.close()
    }

    @Test
    fun browserAuthRefreshesSapisidHash() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(
                authorization = "SAPISIDHASH stale_token",
                cookieHeader = "SID=abc; __Secure-3PAPISID=sapisid-secret",
            ),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        if (request.method == HttpMethod.Get) {
                            respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                        } else {
                            respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)

        provider.search("browser")

        val apiRequest = requests.first { it.url.contains("/youtubei/v1/search") }
        assertTrue(apiRequest.url.contains("key=AIzaSyTestKey"))
        assertEquals("visitor-token", apiRequest.headers["X-Goog-Visitor-Id"])
        val authorization = apiRequest.headers["Authorization"].orEmpty()
        assertTrue(authorization.startsWith("SAPISIDHASH "), authorization)
        assertFalse(authorization.contains("stale_token"), authorization)
        providerHttp.close()
    }

    @Test
    fun sapisidHashMatchesYtmusicapiAlgorithm() {
        assertEquals(
            "SAPISIDHASH 1234567890_79e414afaea32d1087783097cc075f75a96dc46c",
            YtMusicProvider.sapisidHashAuthorization(
                sapisid = "sapisid",
                origin = "https://music.youtube.com",
                nowMillis = 1_234_567_890_000L,
            ),
        )
    }

    @Test
    fun oauthUserPlaylistsUsesYouTubeDataApi() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(
                oauthAccessToken = "ya29.access",
                oauthRefreshToken = "1//refresh",
                oauthExpiresAtMillis = Long.MAX_VALUE / 2,
                oauthScope = "https://www.googleapis.com/auth/youtube",
                oauthClientId = "cid",
                oauthClientSecret = "secret",
            ),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        when {
                            request.url.toString().contains("googleapis.com/youtube/v3/playlists") -> respond(
                                """{"items":[{"id":"PLabc","snippet":{"title":"Mine","thumbnails":{"high":{"url":"https://img"}}},"contentDetails":{"itemCount":3}}]}""",
                            )
                            request.method == HttpMethod.Get -> respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                            else -> respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)
        val feature = YtMusicProvider.FEATURES.first { it.id == "ytmusic_user_playlists" }

        val section = provider.loadFeature(feature, offset = 0, limit = 20)

        val apiRequest = requests.first { it.url.contains("googleapis.com/youtube/v3/playlists") }
        assertTrue(apiRequest.url.contains("mine=true"), apiRequest.url)
        assertEquals("Bearer ya29.access", apiRequest.headers["Authorization"])
        assertTrue(requests.none { it.url.contains("/youtubei/v1/browse") })
        assertEquals(1, section.playlists.size)
        assertTrue(section.playlists.first().id.contains("VLPLabc"), section.playlists.first().id)
        assertEquals("Mine", section.playlists.first().title)
        providerHttp.close()
    }

    @Test
    fun publicChartsAvoidsOAuthAndSendsFormData() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(
                oauthAccessToken = "ya29.access",
                oauthRefreshToken = "1//refresh",
                oauthExpiresAtMillis = Long.MAX_VALUE / 2,
                oauthScope = "https://www.googleapis.com/auth/youtube",
                oauthClientId = "cid",
                oauthClientSecret = "secret",
            ),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        if (request.method == HttpMethod.Get) {
                            respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                        } else {
                            respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)
        val feature = YtMusicProvider.FEATURES.first { it.id == "ytmusic_toplists" }

        provider.loadFeature(feature, offset = 0, limit = 20)

        val browse = requests.first { it.url.contains("/youtubei/v1/browse") }
        assertTrue(browse.url.contains("key=AIzaSyTestKey"), browse.url)
        assertTrue(browse.headers["Authorization"].isNullOrBlank(), "charts must not send OAuth Bearer")
        assertTrue(browse.body.contains("\"browseId\":\"FEmusic_charts\""), browse.body)
        assertTrue(browse.body.contains("\"formData\":{\"selectedValues\":[\"ZZ\"]}"), browse.body)
        providerHttp.close()
    }

    @Test
    fun oauthPlaylistDetailUsesYouTubeDataApiItems() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(
                oauthAccessToken = "ya29.access",
                oauthRefreshToken = "1//refresh",
                oauthExpiresAtMillis = Long.MAX_VALUE / 2,
                oauthScope = "https://www.googleapis.com/auth/youtube",
                oauthClientId = "cid",
                oauthClientSecret = "secret",
            ),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        when {
                            request.url.toString().contains("googleapis.com/youtube/v3/playlistItems") -> respond(
                                """{"items":[{"contentDetails":{"videoId":"vid1"},"snippet":{"title":"Song","videoOwnerChannelTitle":"Artist","thumbnails":{"default":{"url":"https://img"}}}}]}""",
                            )
                            request.method == HttpMethod.Get -> respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                            else -> respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)
        val playlist = ProviderPlaylist(
            id = "playlist:ytmusic:VLPLabc",
            title = "Mine",
            providerId = "ytmusic",
            providerName = "YouTube Music",
        )

        val detail = provider.playlistDetail(playlist, offset = 0, limit = 20)

        val apiRequest = requests.first { it.url.contains("googleapis.com/youtube/v3/playlistItems") }
        assertTrue(apiRequest.url.contains("playlistId=PLabc"), apiRequest.url)
        assertEquals("Bearer ya29.access", apiRequest.headers["Authorization"])
        assertEquals(1, detail.tracks.size)
        assertEquals("Song", detail.tracks.first().title)
        providerHttp.close()
    }

    @Test
    fun browserAuthUserPlaylistsUsesLikedPlaylistsBrowseId() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(
                authorization = "SAPISIDHASH stale_token",
                cookieHeader = "SID=abc; __Secure-3PAPISID=sapisid-secret",
            ),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        if (request.method == HttpMethod.Get) {
                            respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                        } else {
                            respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)
        val feature = YtMusicProvider.FEATURES.first { it.id == "ytmusic_user_playlists" }

        provider.loadFeature(feature, offset = 0, limit = 20)

        val browse = requests.first { it.url.contains("/youtubei/v1/browse") }
        assertTrue(browse.body.contains("\"browseId\":\"FEmusic_liked_playlists\""), browse.body)
        assertFalse(browse.body.contains("\"browseId\":\"FEmusic_liked\""), browse.body)
        assertTrue(browse.headers["Authorization"].orEmpty().startsWith("SAPISIDHASH "))
        assertTrue(requests.none { it.url.contains("googleapis.com/youtube/v3") })
        providerHttp.close()
    }

    @Test
    fun oauthRefreshesWhenAccessTokenIsExpiring() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(
                oauthAccessToken = "stale-access",
                oauthRefreshToken = "1//refresh",
                oauthExpiresAtMillis = 1_000L,
                oauthScope = "https://www.googleapis.com/auth/youtube",
                oauthClientId = "cid",
                oauthClientSecret = "secret",
            ),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        when {
                            request.url.toString().contains("oauth2.googleapis.com/token") -> respond(
                                """{"access_token":"fresh-access","token_type":"Bearer","expires_in":3600,"scope":"https://www.googleapis.com/auth/youtube"}""",
                            )
                            request.url.toString().contains("googleapis.com/youtube/v3/playlists") -> respond("""{"items":[]}""")
                            request.method == HttpMethod.Get -> respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                            else -> respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)
        val feature = YtMusicProvider.FEATURES.first { it.id == "ytmusic_user_playlists" }

        provider.loadFeature(feature, offset = 0, limit = 20)

        assertTrue(requests.any { it.url.contains("oauth2.googleapis.com/token") })
        val apiRequest = requests.first { it.url.contains("googleapis.com/youtube/v3/playlists") }
        assertEquals("Bearer fresh-access", apiRequest.headers["Authorization"])
        assertEquals("fresh-access", store.read("ytmusic")?.oauthAccessToken)
        assertEquals("1//refresh", store.read("ytmusic")?.oauthRefreshToken)
        providerHttp.close()
    }

    @Test
    fun playerOmitsOAuthEvenWhenLoggedIn() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(
                oauthAccessToken = "ya29.access",
                oauthRefreshToken = "1//refresh",
                oauthExpiresAtMillis = Long.MAX_VALUE / 2,
                oauthScope = "https://www.googleapis.com/auth/youtube",
                oauthClientId = "cid",
                oauthClientSecret = "secret",
            ),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        if (request.method == HttpMethod.Get) {
                            respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token","jsUrl":"/s/player/abc/player_ias.vflset/en_US/base.js"});</html>""",
                            )
                        } else {
                            respond(
                                """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"vid1","title":"T","author":"A","lengthSeconds":"10"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/webm","bitrate":128000,"url":"https://cdn.example/a.webm","approxDurationMs":"10000"}]}}""",
                            )
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)

        val track = provider.trackDetail("vid1")
        val payload = provider.resolve(track!!, AudioQualityPolicy.High.policy)

        val playerReq = requests.first { it.url.contains("/youtubei/v1/player") }
        assertTrue(playerReq.url.contains("key=AIzaSyTestKey"), playerReq.url)
        assertTrue(playerReq.headers["Authorization"].isNullOrBlank(), "player must not send OAuth Bearer")
        assertTrue(playerReq.body.contains("\"signatureTimestamp\""), playerReq.body)
        assertTrue(playerReq.body.contains("playbackContext"), playerReq.body)
        assertEquals("T", track.title)
        assertEquals("https://cdn.example/a.webm", payload?.url)
        providerHttp.close()
    }

    @Test
    fun resolveUsesAndroidVrPlayerLikeYtDlp() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        val url = request.url.toString()
                        val body = (request.body as? TextContent)?.text.orEmpty()
                        when {
                            request.method == HttpMethod.Get && url.contains("music.youtube.com") && !url.contains("/s/player") -> respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                            request.method == HttpMethod.Get && (url.contains("/s/player") || url.contains("youtube.com/watch")) -> respond(
                                """{"VISITOR_DATA":"visitor-token"} var signatureTimestamp=20668;""",
                            )
                            body.contains("ANDROID_VR") -> respond(
                                """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/webm; codecs=\"opus\"","bitrate":140000,"url":"https://cdn.example/a.webm","approxDurationMs":"10000"},{"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,"url":"https://cdn.example/a.m4a","approxDurationMs":"10000","audioQuality":"AUDIO_QUALITY_MEDIUM"}]}}""",
                            )
                            else -> respond("""{"playabilityStatus":{"status":"UNPLAYABLE"}}""")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)
        val track = MusicTrack(
            id = "ytmusic:vid1",
            title = "T",
            artists = "A",
            album = "",
            source = "ytmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "ytmusic:vid1",
            providerName = "YouTube Music",
        )

        val payload = provider.resolve(track, AudioQualityPolicy.High.policy)

        // Prefer m4a like FeelUOwn ytdl format m4a/bestaudio/best
        assertEquals("https://cdn.example/a.m4a", payload?.url)
        assertTrue(payload?.headers.isNullOrEmpty(), "yt-dlp/FeelUOwn path uses empty playback headers")
        val vr = requests.first { it.body.contains("ANDROID_VR") }
        assertTrue(vr.url.contains("www.youtube.com/youtubei/v1/player"), vr.url)
        assertEquals(YtMusicProvider.ANDROID_VR_USER_AGENT, vr.headers["User-Agent"])
        assertEquals(YtMusicProvider.ANDROID_VR_CLIENT_NAME, vr.headers["X-Youtube-Client-Name"])
        providerHttp.close()
    }

    @Test
    fun resolveFallsBackToWebRemixWhenAndroidVrUnplayable() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        val url = request.url.toString()
                        val body = (request.body as? TextContent)?.text.orEmpty()
                        when {
                            request.method == HttpMethod.Get && url.contains("music.youtube.com") && !url.contains("/s/player") -> respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                            request.method == HttpMethod.Get && (url.contains("/s/player") || url.contains("youtube.com/watch")) -> respond(
                                """var signatureTimestamp=20668;""",
                            )
                            body.contains("ANDROID_VR") -> respond(
                                """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""",
                            )
                            body.contains("signatureTimestamp") && body.contains("WEB_REMIX").not() && url.contains("music.youtube.com") -> respond(
                                // innerTube wraps WEB_REMIX context around payload
                                """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","bitrate":128000,"url":"https://cdn.example/web.m4a","approxDurationMs":"10000"}]}}""",
                            )
                            url.contains("music.youtube.com/youtubei/v1/player") -> respond(
                                """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","bitrate":128000,"url":"https://cdn.example/web.m4a","approxDurationMs":"10000"}]}}""",
                            )
                            else -> respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)
        val track = MusicTrack(
            id = "ytmusic:vid1",
            title = "T",
            artists = "A",
            album = "",
            source = "ytmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "ytmusic:vid1",
            providerName = "YouTube Music",
        )

        val payload = provider.resolve(track, AudioQualityPolicy.High.policy)

        assertEquals("https://cdn.example/web.m4a", payload?.url)
        assertTrue(requests.any { it.body.contains("ANDROID_VR") })
        assertTrue(requests.any { it.url.contains("music.youtube.com/youtubei/v1/player") })
        providerHttp.close()
    }

    @Test
    fun resolveFallsBackToAndroidWhenVisitorMissing() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        val url = request.url.toString()
                        val body = (request.body as? TextContent)?.text.orEmpty()
                        when {
                            // music landing stub: no visitor
                            request.method == HttpMethod.Get && url.contains("music.youtube.com") -> respond(
                                """<html>YouTube Music is not available in your area</html>""",
                            )
                            // youtube watch also fails to expose visitor (consent stub)
                            request.method == HttpMethod.Get && url.contains("youtube.com/watch") -> respond(
                                """<html><title>Before you continue</title></html>""",
                            )
                            body.contains("ANDROID_VR") -> respond(
                                """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""",
                            )
                            body.contains("\"clientName\":\"ANDROID\"") -> respond(
                                """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","bitrate":130000,"url":"https://cdn.example/android.m4a","approxDurationMs":"10000"}]}}""",
                            )
                            else -> respond("""{"playabilityStatus":{"status":"UNPLAYABLE"}}""")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)
        val track = MusicTrack(
            id = "ytmusic:vid1",
            title = "T",
            artists = "A",
            album = "",
            source = "ytmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "ytmusic:vid1",
            providerName = "YouTube Music",
        )

        val payload = provider.resolve(track, AudioQualityPolicy.High.policy)

        assertEquals("https://cdn.example/android.m4a", payload?.url)
        assertTrue(requests.none { it.body.contains("ANDROID_VR") }, "skip ANDROID_VR without visitor")
        assertTrue(requests.any { it.body.contains("\"clientName\":\"ANDROID\"") })
        assertTrue(payload?.headers.isNullOrEmpty())
        providerHttp.close()
    }

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
}
