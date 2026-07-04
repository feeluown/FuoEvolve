package org.feeluown.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IosFuoCoreBridge : ProviderMusicRepository {
    private var enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS
    private var wifiAudioQualityPolicy: AudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY
    private var cellularAudioQualityPolicy: AudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY

    override suspend fun initialize() = Unit

    override suspend fun availableProviders(): List<ProviderInfo> = AVAILABLE_PROVIDERS

    override suspend fun updateEnabledProviders(providerIds: Set<String>) {
        enabledProviderIds = providerIds
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
            .intersect(AVAILABLE_PROVIDERS.map { it.providerId }.toSet())
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
    }

    override suspend fun providers(): List<ProviderInfo> {
        return AVAILABLE_PROVIDERS.filter { it.providerId in enabledProviderIds }
    }

    override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> = withContext(Dispatchers.Default) {
        pythonUnavailable()
    }

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload = withContext(Dispatchers.Default) {
        pythonUnavailable()
    }

    override suspend fun authState(providerId: String): ProviderAuthState {
        val providerName = providerName(providerId)
        return ProviderAuthState(
            providerId = providerId,
            providerName = providerName,
            isLoggedIn = false,
        )
    }

    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState = withContext(Dispatchers.Default) {
        pythonUnavailable()
    }

    override suspend fun loginWithHeaders(
        providerId: String,
        authorization: String,
        cookie: String,
    ): ProviderAuthState = withContext(Dispatchers.Default) {
        pythonUnavailable()
    }

    override suspend fun logout(providerId: String): ProviderAuthState = authState(providerId)

    override suspend fun updateAudioQualityPolicies(wifiPolicy: AudioQualityPolicy, cellularPolicy: AudioQualityPolicy) {
        wifiAudioQualityPolicy = wifiPolicy
        cellularAudioQualityPolicy = cellularPolicy
    }

    override suspend fun features(): List<ProviderFeature> {
        return enabledProviderIds.flatMap { providerId ->
            providerFeatures(providerId)
        }
    }

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection {
        return ProviderContentSection(
            feature = feature,
            errorMessage = "iOS Python 音源运行时尚未接入",
        )
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = withContext(Dispatchers.Default) {
        pythonUnavailable()
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> = withContext(Dispatchers.Default) {
        pythonUnavailable()
    }

    private fun providerName(providerId: String): String {
        return AVAILABLE_PROVIDERS.firstOrNull { it.providerId == providerId }?.providerName ?: providerId
    }

    private fun providerFeatures(providerId: String): List<ProviderFeature> {
        val providerName = providerName(providerId)
        return when (providerId) {
            "netease" -> listOf(
                feature(providerId, providerName, "netease_daily_songs", "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
                feature(providerId, providerName, "netease_daily_playlists", "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, true),
                feature(providerId, providerName, "netease_radio", "私人 FM", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
                feature(providerId, providerName, "netease_toplists", "排行榜", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
                feature(providerId, providerName, "netease_user_playlists", "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
                feature(providerId, providerName, "netease_favorite_songs", "收藏歌曲", ProviderFeatureCategory.Mine, ProviderContentType.Songs, true),
                feature(providerId, providerName, "netease_favorite_playlists", "收藏歌单", ProviderFeatureCategory.MineFavoritePlaylists, ProviderContentType.Playlists, true),
            )
            "qqmusic" -> listOf(
                feature(providerId, providerName, "qqmusic_daily_songs", "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
                feature(providerId, providerName, "qqmusic_daily_playlists", "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, true),
                feature(providerId, providerName, "qqmusic_radio", "私人 FM", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
                feature(providerId, providerName, "qqmusic_user_playlists", "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
            )
            "bilibili" -> listOf(
                feature(providerId, providerName, "bilibili_user_playlists", "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
                feature(providerId, providerName, "bilibili_favorite_playlists", "收藏歌单", ProviderFeatureCategory.MineFavoritePlaylists, ProviderContentType.Playlists, true),
            )
            "ytmusic" -> listOf(
                feature(providerId, providerName, "ytmusic_daily_songs", "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, false),
                feature(providerId, providerName, "ytmusic_daily_playlists", "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, false),
                feature(providerId, providerName, "ytmusic_toplists", "排行榜", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
            )
            else -> emptyList()
        }
    }

    private fun feature(
        providerId: String,
        providerName: String,
        id: String,
        title: String,
        category: ProviderFeatureCategory,
        contentType: ProviderContentType,
        requiresLogin: Boolean,
    ) = ProviderFeature(
        id = id,
        providerId = providerId,
        providerName = providerName,
        title = title,
        category = category,
        contentType = contentType,
        requiresLogin = requiresLogin,
    )

    private fun pythonUnavailable(): Nothing {
        throw IllegalStateException("iOS Python 音源运行时尚未接入")
    }

    private companion object {
        private val AVAILABLE_PROVIDERS = listOf(
            ProviderInfo(
                providerId = "netease",
                providerName = "网易云音乐",
                loginConfig = ProviderLoginConfig(
                    loginUrl = "https://music.163.com",
                    cookieKeyGroups = listOf(listOf("MUSIC_U")),
                ),
            ),
            ProviderInfo(
                providerId = "qqmusic",
                providerName = "QQ 音乐",
                loginConfig = ProviderLoginConfig(
                    loginUrl = "https://y.qq.com",
                    cookieKeyGroups = listOf(
                        listOf("qqmusic_key", "wxuin", "qm_keyst"),
                        listOf("qqmusic_key", "uin", "qm_keyst"),
                    ),
                ),
            ),
            ProviderInfo(
                providerId = "bilibili",
                providerName = "哔哩哔哩",
                loginConfig = ProviderLoginConfig(
                    loginUrl = "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F",
                    cookieKeyGroups = listOf(listOf("SESSDATA", "bili_jct")),
                ),
            ),
            ProviderInfo(
                providerId = "ytmusic",
                providerName = "YouTube Music",
                supportedLoginModes = setOf(ProviderLoginMode.Headers),
            ),
        )
    }
}
