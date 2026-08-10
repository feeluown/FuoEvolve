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
import org.feeluown.mobile.provider.ytmusic.YtMusicContentProvider
import org.feeluown.mobile.provider.ytmusic.YtMusicProvider

class YtMusicContentProviderTest {
    @Test
    fun searchMapsAllSupportedContentTypes() = runTest {
        val fixture = fixture { request ->
            if (request.url.contains("/youtubei/v1/search")) SEARCH_RESPONSE else "{}"
        }

        val result = fixture.provider.search("demo")

        assertEquals(1, result.tracks.size)
        assertEquals("Song", result.tracks.single().title)
        assertEquals("Artist", result.tracks.single().artists)
        assertEquals("Album", result.tracks.single().album)
        assertEquals(210_000L, result.tracks.single().durationMs)
        assertEquals(1, result.artists.size)
        assertEquals("Artist Result", result.artists.single().title)
        assertEquals(1, result.albums.size)
        assertEquals("Album Result", result.albums.single().title)
        assertEquals(1, result.playlists.size)
        assertEquals("Playlist Result", result.playlists.single().title)
        assertEquals(1, result.videos.size)
        assertEquals("Video Result", result.videos.single().title)
        fixture.close()
    }

    @Test
    fun lyricsUsesAnonymousAndroidMusicTimedLyrics() = runTest {
        val fixture = fixture { request ->
            when {
                request.url.contains("/youtubei/v1/next") -> LYRICS_WATCH_RESPONSE
                request.url.contains("/youtubei/v1/browse") -> TIMED_LYRICS_RESPONSE
                else -> "{}"
            }
        }
        val track = testTrack("song1")

        val lyrics = fixture.provider.lyrics(track)

        assertEquals("[00:01.23]Hello\n[00:02.50]World", lyrics)
        val lyricBrowse = fixture.requests.first { it.url.contains("/youtubei/v1/browse") }
        assertTrue(lyricBrowse.body.contains("\"clientName\":\"ANDROID_MUSIC\""), lyricBrowse.body)
        assertTrue(lyricBrowse.headers["Authorization"].isNullOrBlank())
        assertTrue(lyricBrowse.headers["Cookie"].isNullOrBlank())
        fixture.close()
    }

    @Test
    fun albumDetailMapsHeaderAndTracks() = runTest {
        val fixture = fixture { request ->
            if (request.url.contains("/youtubei/v1/browse")) ALBUM_RESPONSE else "{}"
        }
        val album = ProviderMediaItem(
            id = "album:ytmusic:MPREalbum",
            title = "Album",
            providerId = "ytmusic",
            providerName = "YouTube Music",
            type = ProviderMediaItemType.Album,
        )

        val detail = fixture.provider.mediaItemDetail(album, tracksOffset = 0, albumsOffset = 0, limit = 20)

        assertEquals("Album Real", detail.item.title)
        assertEquals("https://img/album", detail.item.coverUrl)
        assertEquals("Album description", detail.item.description)
        assertEquals(2, detail.item.trackCount)
        assertEquals(listOf("First Song", "Second Song"), detail.tracks.map { it.title })
        fixture.close()
    }

    @Test
    fun similarTracksUsesRadioQueueAndSkipsCurrentSong() = runTest {
        val fixture = fixture { request ->
            if (request.url.contains("/youtubei/v1/next")) RADIO_RESPONSE else "{}"
        }

        val tracks = fixture.provider.similarTracks(testTrack("origin"))

        assertEquals(1, tracks.size)
        assertEquals("ytmusic:next1", tracks.single().id)
        assertEquals("Next Song", tracks.single().title)
        assertEquals("Radio Artist", tracks.single().artists)
        assertEquals(185_000L, tracks.single().durationMs)
        val request = fixture.requests.single { it.url.contains("/youtubei/v1/next") }
        assertTrue(request.body.contains("\"params\":\"wAEB\""), request.body)
        fixture.close()
    }

    @Test
    fun oauthAuthStateLoadsChannelNameAndDoesNotAdvertiseMissingPlaylistMutation() = runTest {
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
        val fixture = fixture(store) { request ->
            if (request.url.contains("googleapis.com/youtube/v3/channels")) {
                """{"items":[{"snippet":{"title":"YT User"}}]}"""
            } else {
                "{}"
            }
        }

        val state = fixture.provider.authState()

        assertTrue(state.isLoggedIn)
        assertEquals("YT User", state.userName)
        assertFalse(fixture.provider.capabilities.canAddSongToPlaylist)
        val channels = fixture.requests.single { it.url.contains("googleapis.com/youtube/v3/channels") }
        assertEquals("Bearer ya29.access", channels.headers["Authorization"])
        fixture.close()
    }

    private data class CapturedRequest(
        val method: HttpMethod,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class Fixture(
        val provider: YtMusicContentProvider,
        val http: ProviderHttpClient,
        val requests: MutableList<CapturedRequest>,
    ) {
        fun close() = http.close()
    }

    private fun fixture(
        store: InMemoryProviderCredentialStore = InMemoryProviderCredentialStore(),
        responder: (CapturedRequest) -> String,
    ): Fixture {
        val requests = mutableListOf<CapturedRequest>()
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val captured = capture(request)
                        requests += captured
                        respond(responder(captured))
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val base = YtMusicProvider(providerHttp, store)
        return Fixture(
            provider = YtMusicContentProvider(base, providerHttp, store),
            http = providerHttp,
            requests = requests,
        )
    }

    private fun capture(request: io.ktor.client.request.HttpRequestData): CapturedRequest = CapturedRequest(
        method = request.method,
        url = request.url.toString(),
        headers = request.headers.entries().associate { it.key to it.value.joinToString(",") },
        body = (request.body as? TextContent)?.text.orEmpty(),
    )

    private fun testTrack(videoId: String): MusicTrack = MusicTrack(
        id = "ytmusic:$videoId",
        title = "Track",
        artists = "Artist",
        album = "",
        source = "ytmusic",
        sourceType = TrackSourceType.Provider,
        providerId = "ytmusic:$videoId",
        providerName = "YouTube Music",
    )

    private companion object {
        val SEARCH_RESPONSE = """
            {
              "items": [
                {
                  "musicResponsiveListItemRenderer": {
                    "flexColumns": [
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                        {"text":"歌曲"},{"text":" • "},
                        {"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}},
                        {"text":" • "},
                        {"text":"Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}},
                        {"text":" • "},{"text":"3:30"}
                      ]}}}
                    ],
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/song"}]}}},
                    "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{
                      "videoId":"song1",
                      "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}
                    }}}}}}}
                  }
                },
                {
                  "musicResponsiveListItemRenderer": {
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist-result"}},
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist Result"}]}}}],
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/artist"}]}}}
                  }
                },
                {
                  "musicResponsiveListItemRenderer": {
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum-result"}},
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album Result"}]}}}],
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/album-result"}]}}}
                  }
                },
                {
                  "musicResponsiveListItemRenderer": {
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLplaylist"}},
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Playlist Result"}]}}}],
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/playlist"}]}}}
                  }
                },
                {
                  "musicResponsiveListItemRenderer": {
                    "flexColumns": [
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Video Result"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                        {"text":"视频"},{"text":" • "},
                        {"text":"Video Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCvideoartist"}}},
                        {"text":" • "},{"text":"4:00"}
                      ]}}}
                    ],
                    "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{
                      "videoId":"video1",
                      "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}
                    }}}}}}}
                  }
                }
              ]
            }
        """.trimIndent()

        val LYRICS_WATCH_RESPONSE = """
            {
              "tabs": [
                {"tabRenderer":{"endpoint":{"browseEndpoint":{
                  "browseId":"MPLYlyrics",
                  "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_TRACK_LYRICS"}}
                }}}}
              ]
            }
        """.trimIndent()

        val TIMED_LYRICS_RESPONSE = """
            {
              "contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{
                "timedLyricsData":[
                  {"lyricLine":"Hello","cueRange":{"startTimeMilliseconds":"1230"}},
                  {"lyricLine":"World","cueRange":{"startTimeMilliseconds":"2500"}}
                ]
              }}}}}}}}
            }
        """.trimIndent()

        val ALBUM_RESPONSE = """
            {
              "header":{"musicResponsiveHeaderRenderer":{
                "title":{"runs":[{"text":"Album Real"}]},
                "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/album"}]}}}
              }},
              "about":{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Album description"}]}}},
              "contents":[
                ${albumSong("song-a", "First Song")},
                ${albumSong("song-b", "Second Song")}
              ]
            }
        """.trimIndent()

        val RADIO_RESPONSE = """
            {
              "contents":[
                ${panelSong("origin", "Current Song", "3:00")},
                ${panelSong("next1", "Next Song", "3:05")}
              ]
            }
        """.trimIndent()

        fun albumSong(videoId: String, title: String): String = """
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}},
                  {"text":" • "},{"text":"Album Real","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}},
                  {"text":" • "},{"text":"3:01"}
                ]}}}
              ],
              "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{
                "videoId":"$videoId",
                "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}
              }}}}}}}
            }}
        """.trimIndent()

        fun panelSong(videoId: String, title: String, duration: String): String = """
            {"playlistPanelVideoRenderer":{
              "videoId":"$videoId",
              "title":{"runs":[{"text":"$title"}]},
              "longBylineText":{"runs":[{"text":"Radio Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCradio"}}}]},
              "lengthText":{"runs":[{"text":"$duration"}]},
              "thumbnail":{"thumbnails":[{"url":"https://img/$videoId"}]}
            }}
        """.trimIndent()
    }
}
