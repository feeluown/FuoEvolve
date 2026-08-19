package org.feeluown.mobile

import kotlinx.serialization.Serializable

@Serializable
data class NavigationFeature(
    val id: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val category: String,
    val contentType: String,
    val requiresLogin: Boolean,
)

@Serializable
data class NavigationTrack(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val source: String,
    val sourceType: String,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val lyrics: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val isUnavailable: Boolean = false,
    val artistItemId: String? = null,
    val albumItemId: String? = null,
    val providerUrl: String? = null,
    val providerTags: List<String> = emptyList(),
)

@Serializable
data class NavigationPlaylist(
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

@Serializable
data class NavigationMediaItem(
    val id: String,
    val title: String,
    val providerId: String,
    val providerName: String,
    val type: String,
    val coverUrl: String? = null,
    val description: String = "",
    val providerUrl: String? = null,
    val trackCount: Int? = null,
    val albumCount: Int? = null,
)

@Serializable
data class NavigationVideo(
    val id: String,
    val title: String,
    val artists: String,
    val providerId: String,
    val providerName: String,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val providerUrl: String? = null,
)

fun ProviderFeature.toNavigationFeature(): NavigationFeature = NavigationFeature(
    id = id,
    providerId = providerId,
    providerName = providerName,
    title = title,
    category = category.name,
    contentType = contentType.name,
    requiresLogin = requiresLogin,
)

fun NavigationFeature.toProviderFeature(): ProviderFeature = ProviderFeature(
    id = id,
    providerId = providerId,
    providerName = providerName,
    title = title,
    category = ProviderFeatureCategory.valueOf(category),
    contentType = ProviderContentType.valueOf(contentType),
    requiresLogin = requiresLogin,
)

fun MusicTrack.toNavigationTrack(): NavigationTrack = NavigationTrack(
    id = id,
    title = title,
    artists = artists,
    album = album,
    source = source,
    sourceType = sourceType.name,
    coverUrl = coverUrl,
    durationMs = durationMs,
    lyrics = lyrics,
    providerId = providerId,
    providerName = providerName,
    isUnavailable = isUnavailable,
    artistItemId = artistItemId,
    albumItemId = albumItemId,
    providerUrl = providerUrl,
    providerTags = providerTags,
)

fun NavigationTrack.toMusicTrack(): MusicTrack = MusicTrack(
    id = id,
    title = title,
    artists = artists,
    album = album,
    source = source,
    sourceType = TrackSourceType.valueOf(sourceType),
    coverUrl = coverUrl,
    durationMs = durationMs,
    lyrics = lyrics,
    providerId = providerId,
    providerName = providerName,
    isUnavailable = isUnavailable,
    artistItemId = artistItemId,
    albumItemId = albumItemId,
    providerUrl = providerUrl,
    providerTags = providerTags,
)

fun ProviderPlaylist.toNavigationPlaylist(): NavigationPlaylist = NavigationPlaylist(
    id = id,
    title = title,
    providerId = providerId,
    providerName = providerName,
    coverUrl = coverUrl,
    description = description,
    playCount = playCount,
    providerUrl = providerUrl,
    trackCount = trackCount,
    isOwnedByCurrentUser = isOwnedByCurrentUser,
    isSubscribed = isSubscribed,
    canAddTracks = canAddTracks,
    canRemoveTracks = canRemoveTracks,
    canDelete = canDelete,
)

fun NavigationPlaylist.toProviderPlaylist(): ProviderPlaylist = ProviderPlaylist(
    id = id,
    title = title,
    providerId = providerId,
    providerName = providerName,
    coverUrl = coverUrl,
    description = description,
    playCount = playCount,
    providerUrl = providerUrl,
    trackCount = trackCount,
    isOwnedByCurrentUser = isOwnedByCurrentUser,
    isSubscribed = isSubscribed,
    canAddTracks = canAddTracks,
    canRemoveTracks = canRemoveTracks,
    canDelete = canDelete,
)

fun ProviderMediaItem.toNavigationMediaItem(): NavigationMediaItem = NavigationMediaItem(
    id = id,
    title = title,
    providerId = providerId,
    providerName = providerName,
    type = type.name,
    coverUrl = coverUrl,
    description = description,
    providerUrl = providerUrl,
    trackCount = trackCount,
    albumCount = albumCount,
)

fun NavigationMediaItem.toProviderMediaItem(): ProviderMediaItem = ProviderMediaItem(
    id = id,
    title = title,
    providerId = providerId,
    providerName = providerName,
    type = ProviderMediaItemType.valueOf(type),
    coverUrl = coverUrl,
    description = description,
    providerUrl = providerUrl,
    trackCount = trackCount,
    albumCount = albumCount,
)

fun ProviderVideo.toNavigationVideo(): NavigationVideo = NavigationVideo(
    id = id,
    title = title,
    artists = artists,
    providerId = providerId,
    providerName = providerName,
    coverUrl = coverUrl,
    durationMs = durationMs,
    providerUrl = providerUrl,
)

fun NavigationVideo.toProviderVideo(): ProviderVideo = ProviderVideo(
    id = id,
    title = title,
    artists = artists,
    providerId = providerId,
    providerName = providerName,
    coverUrl = coverUrl,
    durationMs = durationMs,
    providerUrl = providerUrl,
)
