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

    /** QQ Music's public OAuth client id used by its own y.qq.com web login. */
    private const val OAUTH_CLIENT_ID = "100497308"

    /** Redirect that carries the single-use authorization code the platform hosts intercept. */
    private const val OAUTH_REDIRECT_URI =
        "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/"

    /**
     * QQ's OAuth authorize page (verified working end to end).
     *
     * After the user approves, QQ redirects to
     * `https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=...&code=XXXX&state=state`; the
     * platform web-login hosts intercept that navigation and hand the single-use `code` to
     * [exchangeAuthorizationCode], which turns it into a renewable session. Without the interception
     * the page would land on y.qq.com and only set cookies, i.e. a login that can never be renewed.
     */
    val LOGIN_URL: String = buildString {
        append("https://graph.qq.com/oauth2.0/authorize")
        append("?response_type=code")
        append("&client_id=")
        append(OAUTH_CLIENT_ID)
        append("&redirect_uri=")
        append(encodeUrlComponent(OAUTH_REDIRECT_URI))
        append("&scope=get_user_info,get_app_friends")
        append("&state=state")
        append("&from_ptlogin=1")
        append("&src=1")
    }

    val info = ProviderInfo(
        providerId = ID,
        providerName = NAME,
        loginConfig = org.feeluown.mobile.ProviderLoginConfig(
            LOGIN_URL,
            // Fallback for manually captured cookies; the authorization code above is the real path.
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
