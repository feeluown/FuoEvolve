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

data class ProviderLoginConfig(
    val loginUrl: String,
    val cookieKeyGroups: List<List<String>>,
)

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
)

const val PROVIDER_PAGE_SIZE = 50
