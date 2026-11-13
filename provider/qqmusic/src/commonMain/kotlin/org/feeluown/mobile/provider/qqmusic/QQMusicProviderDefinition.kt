package org.feeluown.mobile.provider.qqmusic

import kotlin.random.Random
import org.feeluown.mobile.ProviderAuthorizationCodeRedirect
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderLoginChoice
import org.feeluown.mobile.ProviderLoginConfig

internal object QQMusicProviderDefinition {
    const val ID = "qqmusic"
    const val NAME = "QQ 音乐"
    const val SEARCH_BASE = "https://c.y.qq.com"
    const val VKEY_BASE = "https://u.y.qq.com"
    const val QQ_AUDIO_BASE = "http://isure.stream.qqmusic.qq.com"
    const val PLAYLIST_MUTATION_SYNC_ATTEMPTS = 6
    const val PLAYLIST_MUTATION_SYNC_DELAY_MS = 200L

    val defaultGuid = Random.nextLong(100_000_000, 1_000_000_000).toString()

    /** The same redirect for a WeChat account login (`login_type=2`), captured from y.qq.com. */
    private const val WECHAT_REDIRECT_URI =
        "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/"

    /**
     * QQ-account web login entry: QQ Music's own site.
     *
     * QQ's standalone OAuth authorize page renders fine in a desktop browser but comes up blank in
     * Android WebView (graph.qq.com serves its login through a page/iframe combination the WebView
     * does not render), which made the login screen unusable there. Loading y.qq.com instead keeps the
     * pre-existing, known-good rendering and still ends at
     * `https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=...&code=XXXX&state=state`, the
     * redirect the platform hosts intercept to obtain renewable credentials. The site's own login
     * dialog covers QQ accounts (scan or password) and WeChat accounts.
     */
    val WEB_LOGIN_URL: String = "https://y.qq.com/"


    /**
     * WeChat's open-platform QR authorize page (renders QQ Music's 微信登录 QR), captured from
     * y.qq.com's own `wx_redirect` login (`login_type=2`).
     *
     * Same interception contract as [WEB_LOGIN_URL]: WeChat redirects back to
     * `https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=...&code=XXXX&state=state` and the
     * hosts hand the code to [exchangeAuthorizationCode] with `loginType = 2`. The trailing
     * `#wechat_redirect` fragment is part of the protocol and must stay.
     */
    val WECHAT_LOGIN_URL: String = buildString {
        append("https://open.weixin.qq.com/connect/qrconnect")
        append("?appid=")
        append(QQMusicAuthorization.WECHAT_APP_ID)
        append("&scope=snsapi_login")
        append("&redirect_uri=")
        append(encodeUrlComponent(WECHAT_REDIRECT_URI))
        append("&state=state")
        append("#wechat_redirect")
    }

    val info = ProviderInfo(
        providerId = ID,
        providerName = NAME,
        loginConfig = ProviderLoginConfig(
            // Default stays the QQ-account entry so a platform build that only knows `loginUrl` works.
            WEB_LOGIN_URL,
            // Fallback for manually captured cookies; the authorization code path is the real one.
            listOf(listOf("qqmusic_key", "wxuin", "qm_keyst"), listOf("qqmusic_key", "uin", "qm_keyst")),
            loginChoices = listOf(
                ProviderLoginChoice(id = "qq", label = "QQ 账号", loginUrl = WEB_LOGIN_URL),
                ProviderLoginChoice(id = "wechat", label = "微信账号", loginUrl = WECHAT_LOGIN_URL),
            ),
            // Both ecosystems end on the same redirect; `login_type` tells the exchange which one it is.
            authorizationCodeRedirect = ProviderAuthorizationCodeRedirect(
                host = "y.qq.com",
                path = "/portal/wx_redirect.html",
                codeParameter = "code",
                loginTypeParameter = "login_type",
            ),
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
