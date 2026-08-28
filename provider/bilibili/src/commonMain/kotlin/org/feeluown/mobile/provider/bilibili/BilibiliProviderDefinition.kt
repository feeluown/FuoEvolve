package org.feeluown.mobile.provider.bilibili

import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderInfo

internal object BilibiliProviderDefinition {
    const val ID = "bilibili"
    const val NAME = "哔哩哔哩"
    const val BASE = "https://api.bilibili.com"
    const val COLLECTION_PREFIX = "collection_"
    const val MAX_FAVORITE_PAGE_SIZE = 20
    const val AUDIO_LOW_MAX_BANDWIDTH = 120_000L
    const val AUDIO_STANDARD_MAX_BANDWIDTH = 256_000L
    const val MEDIA_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    val mixinKeyTable = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    val info = ProviderInfo(
        providerId = ID,
        providerName = NAME,
        loginConfig = org.feeluown.mobile.ProviderLoginConfig(
            "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F",
            listOf(listOf("SESSDATA", "bili_jct")),
        ),
        supportedLoginModes = setOf(org.feeluown.mobile.ProviderLoginMode.WebView),
    )

    val capabilities = ProviderCapabilities(
        providerId = ID,
        providerName = NAME,
        canAddSongToPlaylist = true,
        canRemoveSongFromPlaylist = true,
    )

    val features = listOf(
        ProviderFeature("bilibili_popular_videos", ID, NAME, "热门视频", ProviderFeatureCategory.Music, ProviderContentType.Songs, false),
        ProviderFeature("bilibili_user_playlists", ID, NAME, "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
        ProviderFeature("bilibili_favorite_playlists", ID, NAME, "收藏歌单", ProviderFeatureCategory.MineFavoritePlaylists, ProviderContentType.Playlists, true),
    )
}
