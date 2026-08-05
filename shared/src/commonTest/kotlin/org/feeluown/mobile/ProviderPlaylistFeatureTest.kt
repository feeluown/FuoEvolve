package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        val operationTargets = provider.playlistOperationTargets(detail.tracks.single())

        assertEquals("我的收藏", created.playlists.single().title)
        assertEquals("收藏合集", collected.playlists.single().title)
        assertEquals("我的收藏", operationTargets.single().title)
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
    fun neteaseLoadsRecommendedPlaylistsFromWeApiResource() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/api/discovery/recommend/resource" -> {
                                assertEquals("POST", request.method.value)
                                respond("""{"code":200,"recommend":[{"id":42,"name":"推荐歌单","trackCount":2}]}""")
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
    fun neteaseLoadsDailySongsFromWeApi() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> {
                                assertEquals("POST", request.method.value)
                                respond("""{"code":200,"data":{"userId":12345}}""")
                            }
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/weapi/v3/discovery/recommend/songs" -> {
                                assertEquals("POST", request.method.value)
                                respond(
                                    """{"code":200,"data":{"dailySongs":[{"id":123,"name":"每日推荐歌曲","ar":[{"id":7,"name":"推荐歌手"}],"al":{"id":8,"name":"推荐专辑","picUrl":"https://example.test/cover.jpg"},"dt":180000}]}}""",
                                )
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")

        val section = provider.loadFeature(
            provider.features.single { it.id == "netease_daily_songs" },
            0,
            50,
        )

        assertFalse(section.isLoginRequired)
        assertEquals("netease:123", section.tracks.single().id)
        assertEquals("每日推荐歌曲", section.tracks.single().title)
        assertEquals("推荐歌手", section.tracks.single().artists)
        assertFalse(section.hasMore)

        client.close()
    }

    @Test
    fun neteaseLoadsPrivateFmTracksAndResolvesPlaybackUrl() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/api/radio/get" -> {
                                assertEquals("GET", request.method.value)
                                respond(
                                    """{"code":200,"data":[{"id":456,"name":"私人 FM 歌曲","artists":[{"id":9,"name":"FM 歌手"}],"album":{"id":10,"name":"FM 专辑","picUrl":"https://example.test/fm-cover.jpg"},"duration":240000}]}""",
                                )
                            }
                            "/weapi/song/enhance/player/url" -> {
                                assertEquals("POST", request.method.value)
                                respond(
                                    """{"code":200,"data":[{"id":456,"url":"https://example.test/fm.mp3","br":320000,"type":"mp3","time":240000,"freeTrialInfo":null}]}""",
                                )
                            }
                            "/api/song/lyric" -> {
                                assertEquals("-1", request.url.parameters["lv"])
                                respond("""{"code":200,"lrc":{"lyric":"[00:00.00]FM"}}""")
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")

        val section = provider.loadFeature(
            provider.features.single { it.id == "netease_radio" },
            0,
            50,
        )
        val payload = provider.resolve(section.tracks.single(), AudioQualityPolicy.High.policy)

        assertFalse(section.isLoginRequired)
        assertEquals("私人 FM 歌曲", section.tracks.single().title)
        assertEquals("https://example.test/fm.mp3", payload?.url)

        client.close()
    }

    @Test
    fun neteaseLoadsSimilarTracksFromDiscoveryEndpoint() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/api/discovery/simiSong", request.url.encodedPath)
                        assertEquals("GET", request.method.value)
                        assertEquals("456", request.url.parameters["songid"])
                        assertEquals("0", request.url.parameters["offset"])
                        assertEquals("true", request.url.parameters["total"])
                        assertEquals("10", request.url.parameters["limit"])
                        respond(
                            """{"code":200,"songs":[{"id":789,"name":"相似歌曲","ar":[{"id":7,"name":"相似歌手"}],"al":{"id":8,"name":"相似专辑"}}]}""",
                        )
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())

        val tracks = provider.similarTracks(neteaseTrack(456))

        assertEquals("netease:789", tracks.single().id)
        assertEquals("相似歌曲", tracks.single().title)
        client.close()
    }

    @Test
    fun neteaseLoadsHotCommentsFromResourceCommentsEndpoint() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/weapi/v1/resource/comments/R_SO_4_456", request.url.encodedPath)
                        assertEquals("POST", request.method.value)
                        respond(
                            """{"code":200,"hotComments":[{"commentId":11,"user":{"nickname":"评论用户"},"content":"很好听","likedCount":8,"time":30000}]}""",
                        )
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())

        val comments = provider.hotComments(neteaseTrack(456))

        assertEquals("11", comments.single().id)
        assertEquals("评论用户", comments.single().userName)
        assertEquals(30, comments.single().timeSeconds)
        client.close()
    }

    @Test
    fun neteaseSurfacesBusinessErrorInsteadOfReturningEmptySimilarTracks() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { respond("""{"code":404,"message":"接口未找到！"}""") }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())

        val failure = assertFailsWith<IllegalStateException> {
            provider.similarTracks(neteaseTrack(456))
        }

        assertTrue(failure.message.orEmpty().contains("code=404"))
        client.close()
    }

    @Test
    fun neteaseLoadsFavoriteSongsFromTheFavoritePlaylist() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/api/user/playlist/" -> respond("""{"code":200,"playlist":[{"id":12345,"name":"我喜欢的音乐","subscribed":false,"trackCount":1}]}""")
                            "/weapi/v3/playlist/detail" -> respond("""{"code":200,"playlist":{"id":12345,"name":"我喜欢的音乐","trackCount":1,"trackIds":[{"id":456}]}}""")
                            "/weapi/v3/song/detail" -> respond("""{"code":200,"songs":[{"id":456,"name":"喜欢的歌曲","ar":[{"id":7,"name":"喜欢的歌手"}],"al":{"id":8,"name":"喜欢的专辑"}}]}""")
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")

        val section = provider.loadFeature(provider.features.single { it.id == "netease_favorite_songs" }, 0, 50)

        assertEquals("喜欢的歌曲", section.tracks.single().title)
        assertFalse(section.hasMore)
        client.close()
    }

    @Test
    fun neteaseLoadsFavoriteArtistsAndAlbumsThroughWeApi() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/weapi/artist/sublist" -> {
                                assertEquals("POST", request.method.value)
                                respond("""{"code":200,"data":[{"id":7,"name":"收藏歌手","picUrl":"https://example.test/artist.jpg"}]}""")
                            }
                            "/weapi/album/sublist" -> {
                                assertEquals("POST", request.method.value)
                                respond("""{"code":200,"data":[{"id":8,"name":"收藏专辑","picUrl":"https://example.test/album.jpg"}]}""")
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")

        val artists = provider.loadFeature(provider.features.single { it.id == "netease_favorite_artists" }, 0, 50)
        val albums = provider.loadFeature(provider.features.single { it.id == "netease_favorite_albums" }, 0, 50)

        assertEquals("收藏歌手", artists.mediaItems.single().title)
        assertEquals("收藏专辑", albums.mediaItems.single().title)
        client.close()
    }

    @Test
    fun neteaseUsesPlaylistMutationAndCreateContracts() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/api/playlist/create" -> {
                                assertEquals("POST", request.method.value)
                                val body = (request.body as TextContent).text
                                assertTrue(body.contains("uid=12345"), body)
                                assertTrue(body.contains("name=%E6%96%B0%E6%AD%8C%E5%8D%95"), body)
                                respond("""{"code":200,"playlist":{"id":900,"name":"新歌单"}}""")
                            }
                            "/api/playlist/manipulate/tracks" -> {
                                assertEquals("POST", request.method.value)
                                val body = (request.body as TextContent).text
                                assertTrue(body.contains("pid=900"), body)
                                assertTrue(body.contains("trackIds=%5B%22456%22%5D"), body)
                                assertTrue(body.contains("tracks=456"), body)
                                assertTrue(body.contains("op=add") || body.contains("op=del"), body)
                                respond("""{"code":200}""")
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")
        val playlist = ProviderPlaylist("playlist:netease:900", "目标歌单", "netease", "网易云音乐")

        val created = provider.createPlaylist("新歌单")
        val added = provider.addTrackToPlaylist(playlist, neteaseTrack(456))
        val removed = provider.removeTrackFromPlaylist(playlist, neteaseTrack(456))

        assertTrue(created.success)
        assertTrue(added.success)
        assertTrue(removed.success)
        client.close()
    }

    @Test
    fun neteaseLoadsMvDetailAndPlaybackUrl() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/song/detail" -> respond("""{"code":200,"songs":[{"id":456,"name":"歌曲","mvid":186025,"ar":[{"id":7,"name":"歌手"}],"al":{"id":8,"name":"专辑"}}]}""")
                            "/api/mv/detail" -> {
                                assertEquals("186025", request.url.parameters["id"])
                                respond("""{"code":200,"data":{"id":186025,"name":"歌曲 MV","cover":"https://example.test/mv.jpg","duration":123000,"artists":[{"id":7,"name":"歌手"}],"brs":{"480":"https://example.test/480.mp4","720":"https://example.test/720.mp4"}}}""")
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())

        val video = assertNotNull(provider.trackVideo(neteaseTrack(456)))
        val payload = provider.videoPlaybackPayload(video)

        assertEquals("video:netease:186025", video.id)
        assertEquals("歌曲 MV", video.title)
        assertEquals("https://example.test/720.mp4", payload.url)
        assertEquals("https://example.test/720.mp4", payload.videoUrl)
        assertEquals("720", payload.quality)
        client.close()
    }

    @Test
    fun qqmusicSearchUsesCurrentRpcEnvelopeAndCommonParams() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
                        assertEquals("GET", request.method.value)
                        assertTrue(request.url.parameters["_"].orEmpty().isNotBlank())
                        assertTrue(request.url.parameters["sign"].orEmpty().startsWith("zza"))
                        val data = request.url.parameters["data"].orEmpty()
                        assertTrue(data.contains("DoSearchForQQMusicDesktop"), data)
                        assertTrue(data.contains("\"query\":\"晴天\""), data)
                        assertTrue(data.contains("\"g_tk\":193496974"), data)
                        respond(
                            """{"code":0,"search":{"code":0,"data":{"body":{"song":{"list":[{"id":97773,"mid":"0039MnYb0qxYhV","name":"晴天","singer":[{"id":4558,"mid":"0025NhlN2yWrP4","name":"周杰伦"}],"album":{"id":8220,"mid":"000MkMni19ClKG","name":"叶惠美"},"interval":269}]}}}}}""",
                        )
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"qqmusic_key":"key","wxuin":"o12345","qm_keyst":"keyst"}""")

        val result = provider.search("晴天")

        assertEquals("qqmusic:0039MnYb0qxYhV", result.tracks.single().id)
        assertEquals("晴天", result.tracks.single().title)
        assertEquals("周杰伦", result.tracks.single().artists)
        client.close()
    }

    @Test
    fun qqmusicLoadsRecommendedPlaylistsFromRpc() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
                        assertEquals("GET", request.method.value)
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
                            """{"code":0,"data":{"creator":{},"mymusic":[{"id":99}],"mydiss":{"list":[{"dissid":123,"title":"我的歌单","logo":"https://example.test/playlist.jpg","songnum":2}]}}}""",
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
    fun qqmusicPlaylistMutationUsesDirectoryIdFromPlaylistDetail() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" -> {
                                assertEquals("123", request.url.parameters["disstid"])
                                respond("""{"code":0,"cdlist":[{"disstid":"123","dirid":202}]}""")
                            }
                            "/v8/fcg-bin/fcg_play_single_song.fcg" -> respond(
                                """{"code":0,"data":[{"id":97773,"songmid":"qq-mid","name":"歌曲","singer":[]}]}""",
                            )
                            "/cgi-bin/musicu.fcg" -> {
                                assertEquals("GET", request.method.value)
                                assertTrue(request.url.parameters["data"].orEmpty().contains("\"dirId\":202"))
                                respond("""{"code":0,"req_0":{"code":0}}""")
                            }
                            else -> error("unexpected QQ Music request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"qqmusic_key":"key","wxuin":"o12345","qm_keyst":"keyst"}""")
        val playlist = ProviderPlaylist("playlist:qqmusic:123", "目标歌单", "qqmusic", "QQ 音乐")
        val track = MusicTrack(
            id = "qqmusic:qq-mid",
            title = "歌曲",
            artists = "歌手",
            album = "专辑",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "qqmusic:qq-mid",
        )

        assertTrue(provider.addTrackToPlaylist(playlist, track).success)
        assertTrue(provider.removeTrackFromPlaylist(playlist, track).success)

        client.close()
    }

    @Test
    fun qqmusicLoadsDailySongsFromRecommendFeed() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/cgi-bin/musicu.fcg" -> respond(
                                """{"code":0,"req_0":{"code":0,"data":{"v_shelf":[{"extra_info":{},"v_niche":[{"v_card":[{"id":"7251579717","title":"每日30首","cover":"https://example.test/daily.jpg","jumptype":10014,"extra_info":{"moduleID":"recforyou@0@0"}}]}]}]}}}""",
                            )
                            "/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" -> {
                                assertEquals("7251579717", request.url.parameters["disstid"])
                                respond(
                                    """{"code":0,"cdlist":[{"disstid":"7251579717","dissname":"每日30首","songnum":1,"songlist":[{"songmid":"daily-mid","songname":"每日歌曲","singer":[{"mid":"artist-mid","name":"歌手"}],"albumname":"专辑","albummid":"album-mid","interval":180}]}]}""",
                                )
                            }
                            else -> error("unexpected QQ Music request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"qqmusic_key":"key","wxuin":"o12345","qm_keyst":"keyst"}""")

        val section = provider.loadFeature(
            provider.features.single { it.id == "qqmusic_daily_songs" },
            0,
            50,
        )

        assertFalse(section.isLoginRequired)
        assertEquals("qqmusic:daily-mid", section.tracks.single().id)
        assertEquals("每日歌曲", section.tracks.single().title)
        assertFalse(section.hasMore)

        client.close()
    }

    @Test
    fun qqmusicLoadsPrivateFmTracksFromRpc() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
                        assertEquals("GET", request.method.value)
                        respond(
                            """{"code":0,"songlist":{"code":0,"data":{"tracks":[{"mid":"radio-mid","name":"私人歌曲","singer":[{"mid":"artist-mid","name":"私人歌手"}],"album":{"mid":"album-mid","name":"私人专辑"},"interval":240}]}}}""",
                        )
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"qqmusic_key":"key","wxuin":"o12345","qm_keyst":"keyst"}""")

        val section = provider.loadFeature(
            provider.features.single { it.id == "qqmusic_radio" },
            0,
            50,
        )

        assertFalse(section.isLoginRequired)
        assertEquals("qqmusic:radio-mid", section.tracks.single().id)
        assertEquals("私人歌曲", section.tracks.single().title)
        assertFalse(section.hasMore)

        client.close()
    }

    @Test
    fun qqmusicDeduplicatesPlaylistTracksBeforeRendering() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg", request.url.encodedPath)
                        respond(
                            """{"code":0,"cdlist":[{"disstid":"123","dissname":"重复歌曲歌单","songnum":3,"songlist":[{"songmid":"same-mid","songname":"同一首","singer":[],"interval":180},{"songmid":"same-mid","songname":"同一首","singer":[],"interval":180},{"songmid":"other-mid","songname":"另一首","singer":[],"interval":200}]}]}""",
                        )
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        val playlist = ProviderPlaylist(
            id = "playlist:qqmusic:123",
            title = "重复歌曲歌单",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
        )

        val detail = provider.playlistDetail(playlist, 0, 50)

        assertEquals(listOf("qqmusic:same-mid", "qqmusic:other-mid"), detail.tracks.map { it.id })
        assertEquals(3, detail.tracksNextOffset)
        assertFalse(detail.tracksHasMore)

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
    fun neteasePlaylistUsesBatchSongDetailForTrackIds() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/playlist/detail" -> respond(
                                """{"code":200,"playlist":{"id":123,"name":"缺少歌曲详情的歌单","trackCount":1,"trackIds":[{"id":456}]}}""",
                            )
                            "/api/song/detail" -> {
                                assertEquals("[456]", request.url.parameters["ids"])
                                respond(
                                    """{"code":200,"songs":[{"id":456,"name":"补全歌曲","ar":[{"id":7,"name":"补全歌手"}],"al":{"id":8,"name":"补全专辑","picUrl":"https://example.test/cover.jpg"},"dt":180000}]}""",
                                )
                            }
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val playlist = ProviderPlaylist(
            id = "playlist:netease:123",
            title = "缺少歌曲详情的歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )

        val detail = provider.playlistDetail(playlist, 0, 50)

        assertEquals("补全歌手", detail.tracks.single().artists)
        assertEquals("补全专辑", detail.tracks.single().album)
        assertEquals("https://example.test/cover.jpg", detail.tracks.single().coverUrl)

        client.close()
    }

    @Test
    fun neteasePlaylistPrefersWeApiTrackIdsAndBatchDetails() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/weapi/v3/playlist/detail" -> respond(
                                """{"code":200,"playlist":{"id":123,"name":"用户歌单","trackCount":2,"trackIds":[{"id":456},{"id":789}],"tracks":[{"id":456,"name":"首屏歌曲"}]}}""",
                            )
                            "/weapi/v3/song/detail" -> respond(
                                """{"code":200,"songs":[{"id":789,"name":"第二首歌曲","ar":[{"id":7,"name":"歌手"}],"al":{"id":8,"name":"专辑"}}]}""",
                            )
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val playlist = ProviderPlaylist(
            id = "playlist:netease:123",
            title = "用户歌单",
            providerId = "netease",
            providerName = "网易云音乐",
        )

        val detail = provider.playlistDetail(playlist, offset = 1, limit = 1)

        assertEquals("netease:789", detail.tracks.single().id)
        assertEquals("第二首歌曲", detail.tracks.single().title)
        assertEquals(2, detail.tracksNextOffset)
        assertFalse(detail.tracksHasMore)

        client.close()
    }

    @Test
    fun neteaseTreatsFreeTrialMediaAsUnavailableForSmartReplacement() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        assertEquals("/weapi/song/enhance/player/url", request.url.encodedPath)
                        respond(
                            """{"code":200,"data":[{"id":456,"url":"https://example.test/preview.mp3","freeTrialInfo":{"start":0,"end":30},"time":180000}]}""",
                        )
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val track = MusicTrack(
            id = "netease:456",
            title = "VIP 歌曲",
            artists = "歌手",
            album = "专辑",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:456",
        )

        val payload = provider.resolve(track, AudioQualityPolicy.High.policy)

        assertEquals(null, payload)
        client.close()
    }

    @Test
    fun neteaseLyricFailureDoesNotBlockPlayableMedia() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/weapi/song/enhance/player/url" -> respond(
                                """{"code":200,"data":[{"id":456,"url":"https://example.test/song.mp3","freeTrialInfo":null,"time":180000,"type":"mp3"}]}""",
                            )
                            else -> error("lyric request should be best effort: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val track = MusicTrack(
            id = "netease:456",
            title = "可播放歌曲",
            artists = "歌手",
            album = "专辑",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:456",
        )

        val payload = provider.resolve(track, AudioQualityPolicy.High.policy)

        assertEquals("https://example.test/song.mp3", payload?.url)
        assertEquals(null, payload?.lyrics)
        client.close()
    }

    @Test
    fun qqmusicDoesNotUseTestFileAsPlayableMedia() = runTest {
        val requestedData = mutableListOf<String>()
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/v8/fcg-bin/fcg_play_single_song.fcg" -> respond(
                                """{"code":0,"data":[{"songmid":"qq-mid","file":{"media_mid":"media-mid"}}]}""",
                            )
                            "/cgi-bin/musicu.fcg" -> {
                                assertEquals("GET", request.method.value)
                                val sign = request.url.parameters["sign"].orEmpty()
                                assertTrue(sign.matches(Regex("zza[a-z0-9]{10,16}[0-9a-f]{32}")), sign)
                                val data = request.url.parameters["data"].orEmpty()
                                requestedData += data
                                assertTrue(data.contains("\"ct\":19"), data)
                                respond(
                                    """{"req_0":{"code":0,"data":{"midurlinfo":[{"purl":""}],"sip":["https://example.test/"] ,"testfilewifi":"preview.mp3"}}}""",
                                )
                            }
                            else -> error("unexpected QQ Music request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        val track = MusicTrack(
            id = "qqmusic:qq-mid",
            title = "试听歌曲",
            artists = "歌手",
            album = "专辑",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "qqmusic:qq-mid",
        )

        val payload = provider.resolve(track, AudioQualityPolicy.High.policy)

        assertEquals(null, payload)
        assertTrue(requestedData.any { it.contains("M800media-mid.mp3") })
        client.close()
    }

    @Test
    fun qqmusicUsesAvailableQualityAndBuildsPlayableUrlFromPurl() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/v8/fcg-bin/fcg_play_single_song.fcg" -> respond(
                                """{"code":0,"data":[{"songmid":"qq-mid","file":{"media_mid":"media-mid","size_128mp3":123}}]}""",
                            )
                            "/cgi-bin/musicu.fcg" -> {
                                assertEquals("GET", request.method.value)
                                assertTrue(request.url.parameters["data"].orEmpty().contains("M500media-mid.mp3"))
                                respond(
                                    """{"req_0":{"code":0,"data":{"midurlinfo":[{"purl":"audio/path.mp3"}]}}}""",
                                )
                            }
                            "/lyric/fcgi-bin/fcg_query_lyric_new.fcg" -> respond(
                                """{"code":0,"lyric":""}""",
                            )
                            else -> error("unexpected QQ Music request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        val track = MusicTrack(
            id = "qqmusic:qq-mid",
            title = "普通歌曲",
            artists = "歌手",
            album = "专辑",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "qqmusic:qq-mid",
        )

        val payload = provider.resolve(track, AudioQualityPolicy.High.policy)

        assertEquals("http://isure.stream.qqmusic.qq.com/audio/path.mp3", payload?.url)
        assertEquals("LQ", payload?.audioQuality)
        client.close()
    }

    @Test
    fun neteaseCloudSongsResolvesPrivateCloudEntries() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/api/user/level" -> respond("""{"code":200,"data":{"userId":12345}}""")
                            "/weapi/share/userprofile/info" -> respond("""{"code":200,"nickname":"tester"}""")
                            "/weapi/v1/cloud/get" -> respond(
                                """{"code":200,"count":2,"data":[{"id":"100","s_id":"99","simpleSong":{"id":100,"name":"普通云盘歌曲","ar":[{"id":7,"name":"普通歌手"}],"al":{"id":8,"name":"普通专辑"}}},{"id":"200","s_id":"200","name":"私有云盘歌曲"}]}""",
                            )
                            "/weapi/v1/cloud/get/byids" -> respond(
                                """{"code":200,"data":[{"id":200,"name":"私有云盘歌曲","ar":[{"id":9,"name":"私有歌手"}],"al":{"id":10,"name":"私有专辑"}}]}""",
                            )
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        provider.loginWithCookies("""{"MUSIC_U":"music-cookie"}""")

        val section = provider.loadFeature(
            provider.features.single { it.id == "netease_cloud_songs" },
            0,
            50,
        )

        assertEquals(listOf("普通云盘歌曲", "私有云盘歌曲"), section.tracks.map { it.title })
        assertEquals(listOf("普通歌手", "私有歌手"), section.tracks.map { it.artists })
        assertEquals(listOf("普通专辑", "私有专辑"), section.tracks.map { it.album })

        client.close()
    }

    @Test
    fun neteaseArtistAndAlbumDetailsExposeTracksAndAlbums() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/weapi/v1/artist/songs" -> respond(
                                """{"code":200,"data":{"total":1,"more":false,"songs":[{"id":101,"name":"歌手歌曲","ar":[{"id":7,"name":"歌手"}],"al":{"id":8,"name":"专辑"}}]}}""",
                            )
                            "/api/artist/albums/7" -> respond(
                                """{"code":200,"artist":{"albumSize":1},"more":false,"hotAlbums":[{"id":8,"name":"专辑","picUrl":"https://example.test/album.jpg"}]}""",
                            )
                            "/api/album/8" -> respond(
                                """{"code":200,"album":{"id":8,"name":"专辑","size":1,"songs":[{"id":101,"name":"专辑歌曲","ar":[{"id":7,"name":"歌手"}],"al":{"id":8,"name":"专辑"}}]}}""",
                            )
                            else -> error("unexpected NetEase request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = NeteaseProvider(client, InMemoryProviderCredentialStore())
        val artist = ProviderMediaItem(
            id = "artist:netease:7",
            title = "歌手",
            providerId = "netease",
            providerName = "网易云音乐",
            type = ProviderMediaItemType.Artist,
        )
        val album = ProviderMediaItem(
            id = "album:netease:8",
            title = "专辑",
            providerId = "netease",
            providerName = "网易云音乐",
            type = ProviderMediaItemType.Album,
        )

        val artistDetail = provider.mediaItemDetail(artist, 0, 0, 50)
        val albumDetail = provider.mediaItemDetail(album, 0, 0, 50)

        assertEquals("歌手歌曲", artistDetail.tracks.single().title)
        assertEquals("歌手", artistDetail.tracks.single().artists)
        assertEquals("专辑", artistDetail.tracks.single().album)
        assertEquals("album:netease:8", artistDetail.albums.single().id)
        assertEquals("专辑歌曲", albumDetail.tracks.single().title)
        assertEquals("歌手", albumDetail.tracks.single().artists)

        client.close()
    }

    @Test
    fun qqmusicArtistAndAlbumDetailsExposeTracksAndAlbums() = runTest {
        val client = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.encodedPath) {
                            "/cgi-bin/musicu.fcg" -> respond(
                                """{"code":0,"req_0":{"code":0,"data":{"total":1,"songlist":[{"songInfo":{"mid":"song-mid","name":"歌手歌曲","singer":[{"id":42,"mid":"artist-mid","name":"歌手"}],"album":{"id":8,"mid":"album-mid","name":"专辑"},"interval":180}}]}}}""",
                            )
                            "/v8/fcg-bin/fcg_v8_singer_album.fcg" -> respond(
                                """{"code":0,"data":{"total":1,"list":[{"albumID":8,"albumMID":"album-mid","albumName":"专辑","albumPic":"https://example.test/album.jpg"}]}}""",
                            )
                            "/v8/fcg-bin/fcg_v8_album_detail_cp.fcg" -> respond(
                                """{"code":0,"data":{"getAlbumInfo":{"Falbum_id":8,"Falbum_mid":"album-mid","Falbum_name":"专辑"},"getAlbumDesc":{"Falbum_desc":"专辑简介"},"getSongInfo":[{"id":101,"mid":"song-mid","name":"专辑歌曲","singer":[{"id":42,"mid":"artist-mid","name":"歌手"}],"album":{"id":8,"mid":"album-mid","name":"专辑"},"interval":180}]}}""",
                            )
                            else -> error("unexpected QQ Music request: ${request.url.encodedPath}")
                        }
                    }
                }
            },
        )
        val provider = QQMusicProvider(client, InMemoryProviderCredentialStore())
        val artist = ProviderMediaItem(
            id = "artist:qqmusic:42",
            title = "歌手",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
            type = ProviderMediaItemType.Artist,
        )
        val album = ProviderMediaItem(
            id = "album:qqmusic:8",
            title = "专辑",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
            type = ProviderMediaItemType.Album,
        )

        val artistDetail = provider.mediaItemDetail(artist, 0, 0, 50)
        val albumDetail = provider.mediaItemDetail(album, 0, 0, 50)

        assertEquals("歌手歌曲", artistDetail.tracks.single().title)
        assertEquals("歌手", artistDetail.tracks.single().artists)
        assertEquals("专辑", artistDetail.tracks.single().album)
        assertEquals("artist:qqmusic:42", artistDetail.tracks.single().artistItemId)
        assertEquals("album:qqmusic:8", artistDetail.tracks.single().albumItemId)
        assertEquals("album:qqmusic:8", artistDetail.albums.single().id)
        assertEquals("专辑歌曲", albumDetail.tracks.single().title)
        assertEquals("歌手", albumDetail.tracks.single().artists)

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

    private fun neteaseTrack(identifier: Int): MusicTrack = MusicTrack(
        id = "netease:$identifier",
        title = "测试歌曲",
        artists = "测试歌手",
        album = "测试专辑",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        providerId = "netease:$identifier",
    )
}
