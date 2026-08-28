package org.feeluown.mobile.provider.qqmusic

import kotlin.random.Random
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderInfo

internal object QQMusicProviderDefinition {
    const val ID = "qqmusic"
    const val NAME = "QQ 音乐"
    const val SEARCH_BASE = "https://c.y.qq.com"
    const val VKEY_BASE = "https://u.y.qq.com"
    const val QQ_AUDIO_BASE = "http://isure.stream.qqmusic.qq.com"
    const val PLAYLIST_MUTATION_SYNC_ATTEMPTS = 6
    const val PLAYLIST_MUTATION_SYNC_DELAY_MS = 200L

    val defaultGuid = Random.nextLong(100_000_000, 1_000_000_000).toString()

    val info = ProviderInfo(
        providerId = ID,
        providerName = NAME,
        loginConfig = org.feeluown.mobile.ProviderLoginConfig(
            "https://y.qq.com",
            listOf(listOf("qqmusic_key", "wxuin", "qm_keyst"), listOf("qqmusic_key", "uin", "qm_keyst")),
        ),
        supportedLoginModes = setOf(org.feeluown.mobile.ProviderLoginMode.WebView),
    )

    val capabilities = ProviderCapabilities(
        providerId = ID,
        providerName = NAME,
        canAddSongToPlaylist = true,
        canRemoveSongFromPlaylist = true,
        canCreatePlaylist = true,
        canDeletePlaylist = true,
    )

    val features = listOf(
        ProviderFeature("qqmusic_daily_songs", ID, NAME, "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
        ProviderFeature("qqmusic_radio", ID, NAME, "私人 FM", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
        ProviderFeature("qqmusic_daily_playlists", ID, NAME, "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, true),
        ProviderFeature("qqmusic_user_playlists", ID, NAME, "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
    )
}
