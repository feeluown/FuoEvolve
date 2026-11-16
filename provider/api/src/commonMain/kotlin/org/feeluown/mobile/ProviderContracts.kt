package org.feeluown.mobile

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderLoginMode {
    /**
     * A provider-issued login page that hands back an authorization code captured from its redirect.
     */
    WebView,

    /**
     * A provider-issued login session that the user approves elsewhere — typically by opening the
     * provider's own authorize page in a browser, where the phone can pick QQ or WeChat. The app only
     * polls the session, so no web view and no second device are required.
     */
    Scan,
    Cookie,
    Headers,
    OAuth,
}

/**
 * A provider login session that is approved outside the app.
 *
 * The provider creates the session up front and hands back the URL of its own authorize page. The app
 * opens that URL in a browser, keeps polling, and turns the approved session into renewable
 * credentials once the user approves there.
 */
data class ProviderScanLogin(
    /** Opaque provider session identifier; [authorizeUrl] is only meaningful together with it. */
    val sessionId: String,
    /** Page the user must open and approve; already encodes [sessionId]. */
    val authorizeUrl: String,
    /** How long the session stays usable, counted from this call; polling stops afterwards. */
    val expiresInMillis: Long,
    /** How often the app should poll, in milliseconds. */
    val pollIntervalMillis: Long = 2_000L,
)

/**
 * Result of one poll of a [ProviderScanLogin].
 */
sealed interface ProviderScanLoginResult {
    /** Nobody has authorized the session yet. Keep polling. */
    data object Pending : ProviderScanLoginResult

    /** The account was recognized and is waiting for the user to confirm. Keep polling. */
    data object Scanned : ProviderScanLoginResult

    /** The session was approved; [authState] is the persisted, renewable login. */
    data class Approved(val authState: ProviderAuthState) : ProviderScanLoginResult

    /** The session timed out; the app must create a new one. */
    data object Expired : ProviderScanLoginResult

    /** The provider refused the session; [message] is user-presentable. */
    data class Failed(val message: String) : ProviderScanLoginResult
}

@Serializable
data class ProviderHeaderInput(
    val authorization: String = "",
    val cookie: String = "",
)

@Serializable
data class ProviderOAuthInput(
    val clientId: String = "",
    val clientSecret: String = "",
)

data class ProviderAuthState(
    val providerId: String,
    val providerName: String,
    val isLoggedIn: Boolean,
    val userName: String? = null,
    /**
     * Why the session is not usable, when the provider learned it from the server rather than from
     * the absence of stored credentials. Lets the UI offer "重新登录" for an expired session without
     * asking the user to log out first.
     */
    val failure: ProviderFailure? = null,
)

data class ProviderCapabilities(
    val providerId: String,
    val providerName: String,
    val canAddSongToPlaylist: Boolean = false,
    val canRemoveSongFromPlaylist: Boolean = false,
    val canCreatePlaylist: Boolean = false,
    val canDeletePlaylist: Boolean = false,
    val canListDislikedSongs: Boolean = false,
    val canAddDislikedSong: Boolean = false,
    val canRemoveDislikedSong: Boolean = false,
    val canFavoritePlaylist: Boolean = false,
    val canUnfavoritePlaylist: Boolean = false,
    val canFavoriteArtist: Boolean = false,
    val canUnfavoriteArtist: Boolean = false,
    val canFavoriteAlbum: Boolean = false,
    val canUnfavoriteAlbum: Boolean = false,
)

data class ProviderResourceState(
    val providerId: String,
    val resourceId: String,
    val isFavorite: Boolean = false,
    val canFavorite: Boolean = false,
    val canUnfavorite: Boolean = false,
)

data class ProviderMutationResult(
    val success: Boolean,
    val message: String,
)

/**
 * One selectable web-login entry point.
 *
 * Most providers have exactly one (`loginChoices` stays empty and [ProviderLoginConfig.loginUrl] is
 * used). Providers that offer several account ecosystems — QQ Music accepts a QQ account or a WeChat
 * account, each with its own authorize URL — declare them here so the UI can offer a choice instead
 * of hard-coding one of them.
 */
data class ProviderLoginChoice(
    /** Stable identifier the platform may echo back, e.g. `qq` / `wechat`. */
    val id: String,
    val label: String,
    val loginUrl: String,
    /**
     * Value handed to the provider's own login call to select this ecosystem, when the provider
     * numbers them (QQ Music: 1 = QQ account, 2 = WeChat account). Null when the entry needs no
     * selector, in which case the provider applies its default.
     */
    val loginType: Int? = null,
)

/**
 * Describes the OAuth redirect that ends a provider web login.
 *
 * Platforms intercept this navigation instead of letting the page run: the page would exchange the
 * single-use [codeParameter] code itself, and the app needs that code to obtain renewable
 * credentials. Declaring it in the provider keeps provider-specific host/path knowledge out of the
 * three platform hosts.
 */
data class ProviderAuthorizationCodeRedirect(
    val host: String,
    val path: String,
    val codeParameter: String = "code",
    /** Query parameter carrying the account ecosystem (QQ Music: 1 = QQ, 2 = WeChat), if any. */
    val loginTypeParameter: String? = null,
)

data class ProviderLoginConfig(
    val loginUrl: String,
    val cookieKeyGroups: List<List<String>>,
    val loginChoices: List<ProviderLoginChoice> = emptyList(),
    /**
     * Set when the login completes by handing an authorization code to the provider. Platforms then
     * wait for that redirect instead of finishing on captured cookies, which would store a
     * non-renewable session.
     */
    val authorizationCodeRedirect: ProviderAuthorizationCodeRedirect? = null,
) {
    /** Selectable entries; falls back to the single [loginUrl] so existing providers are unchanged. */
    val effectiveLoginChoices: List<ProviderLoginChoice>
        get() = loginChoices.ifEmpty { listOf(ProviderLoginChoice(id = "", label = "", loginUrl = loginUrl)) }
}

data class ProviderInfo(
    val providerId: String,
    val providerName: String,
    val loginConfig: ProviderLoginConfig? = null,
    val supportedLoginModes: Set<ProviderLoginMode> = setOf(ProviderLoginMode.WebView, ProviderLoginMode.Cookie),
)

enum class ProviderFeatureCategory {
    Recommend,
    Music,
    MinePlaylists,
    MineFavoritePlaylists,
    Mine,
}

enum class ProviderContentType {
    Songs,
    Playlists,
    Artists,
    Albums,
    Videos,
}

data class ProviderFeature(
    val id: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val category: ProviderFeatureCategory,
    val contentType: ProviderContentType,
    val requiresLogin: Boolean,
)

data class ProviderPlaylist(
    val id: String,
    val title: String,
    val providerId: String,
    val providerName: String,
    val coverUrl: String? = null,
    val description: String = "",
    val playCount: Long? = null,
    val providerUrl: String? = null,
    val trackCount: Int? = null,
    val isOwnedByCurrentUser: Boolean? = null,
    val isSubscribed: Boolean? = null,
    val canAddTracks: Boolean? = null,
    val canRemoveTracks: Boolean? = null,
    val canDelete: Boolean? = null,
)

typealias ProviderMediaItemType = MediaRefType
typealias ProviderMediaItem = MediaRef

fun ProviderMediaItem.toMediaRef(): MediaRef = this

fun MediaRef.toProviderMediaItem(): ProviderMediaItem = this

data class ProviderVideo(
    val id: String,
    val title: String,
    val artists: String = "",
    val providerId: String,
    val providerName: String,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val providerUrl: String? = null,
)

data class ProviderComment(
    val id: String,
    val userName: String,
    val content: String,
    val likedCount: Long = 0,
    val timeSeconds: Long = 0,
)

data class VideoPlaybackPayload(
    val video: ProviderVideo,
    val url: String = "",
    val videoUrl: String = "",
    val audioUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val fallbackUrls: List<String> = emptyList(),
    val fallbackVideoUrls: List<String> = emptyList(),
    val fallbackAudioUrls: List<String> = emptyList(),
)

const val PROVIDER_PAGE_SIZE = 50
