package org.feeluown.mobile

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderLoginMode {
    WebView,
    Cookie,
    Headers,
    OAuth,
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
)

data class ProviderLoginConfig(
    val loginUrl: String,
    val cookieKeyGroups: List<List<String>>,
    val loginChoices: List<ProviderLoginChoice> = emptyList(),
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
