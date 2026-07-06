package org.feeluown.mobile

enum class ShareResourceType(
    val namespace: String,
    val displayName: String,
) {
    Song("songs", "歌曲"),
    Playlist("playlists", "歌单"),
    Artist("artists", "歌手"),
    Album("albums", "专辑"),
}

enum class ShareMode {
    FullText,
    FuoLink,
    ProviderLink,
}

data class ShareResourceRef(
    val type: ShareResourceType,
    val providerId: String,
    val providerName: String,
    val identifier: String,
    val title: String,
    val artists: String = "",
    val album: String = "",
    val providerUrl: String? = null,
)

data class SharePayload(
    val resource: ShareResourceRef,
    val fuoUri: String,
    val providerUrl: String?,
    val text: String,
) {
    fun content(mode: ShareMode): String? = when (mode) {
        ShareMode.FullText -> text
        ShareMode.FuoLink -> fuoUri
        ShareMode.ProviderLink -> providerUrl
    }
}

fun MusicTrack.toSharePayload(): SharePayload? {
    if (sourceType != TrackSourceType.Provider || source.isBlank()) return null
    val identifier = providerIdentifier(id, source) ?: return null
    return ShareResourceRef(
        type = ShareResourceType.Song,
        providerId = source,
        providerName = providerName.orEmpty().ifBlank { source },
        identifier = identifier,
        title = title.ifBlank { "未知歌曲" },
        artists = artists,
        album = album,
        providerUrl = providerUrl,
    ).toSharePayload()
}

fun ProviderPlaylist.toSharePayload(): SharePayload? {
    val identifier = providerIdentifier(id, providerId, "playlist") ?: return null
    return ShareResourceRef(
        type = ShareResourceType.Playlist,
        providerId = providerId,
        providerName = providerName.ifBlank { providerId },
        identifier = identifier,
        title = title.ifBlank { "未命名歌单" },
        providerUrl = providerUrl,
    ).toSharePayload()
}

fun ProviderMediaItem.toSharePayload(): SharePayload? {
    val resourceType = when (type) {
        ProviderMediaItemType.Artist -> ShareResourceType.Artist
        ProviderMediaItemType.Album -> ShareResourceType.Album
    }
    val prefix = when (type) {
        ProviderMediaItemType.Artist -> "artist"
        ProviderMediaItemType.Album -> "album"
    }
    val identifier = providerIdentifier(id, providerId, prefix) ?: return null
    return ShareResourceRef(
        type = resourceType,
        providerId = providerId,
        providerName = providerName.ifBlank { providerId },
        identifier = identifier,
        title = title.ifBlank { resourceType.displayName },
        providerUrl = providerUrl,
    ).toSharePayload()
}

fun ShareResourceRef.toSharePayload(): SharePayload {
    val fuoUri = "fuo://$providerId/${type.namespace}/$identifier"
    val titleLine = when (type) {
        ShareResourceType.Song -> buildString {
            append("《")
            append(title)
            append("》")
            if (artists.isNotBlank()) append(" - ").append(artists)
            if (album.isNotBlank()) append("，来自专辑《").append(album).append("》")
        }
        ShareResourceType.Playlist -> "分享歌单《$title》"
        ShareResourceType.Artist -> "分享歌手 $title"
        ShareResourceType.Album -> "分享专辑《$title》"
    }
    val text = buildList {
        add(titleLine)
        add("来源：$providerName")
        providerUrl?.takeIf { it.isNotBlank() }?.let { add("点击 $it 一起听") }
        add("或复制到 FuoEvolve：")
        add(fuoUri)
    }.joinToString("\n")
    return SharePayload(
        resource = this,
        fuoUri = fuoUri,
        providerUrl = providerUrl?.takeIf { it.isNotBlank() },
        text = text,
    )
}

fun parseSharedResource(text: String): ShareResourceRef? {
    val match = FUO_URI_REGEX.find(text) ?: return null
    val providerId = match.groupValues[1]
    val namespace = match.groupValues[2].ifBlank { ShareResourceType.Song.namespace }
    val identifier = match.groupValues[3]
    val type = when (namespace) {
        ShareResourceType.Song.namespace -> ShareResourceType.Song
        ShareResourceType.Playlist.namespace -> ShareResourceType.Playlist
        ShareResourceType.Artist.namespace -> ShareResourceType.Artist
        ShareResourceType.Album.namespace -> ShareResourceType.Album
        else -> return null
    }
    return ShareResourceRef(
        type = type,
        providerId = providerId,
        providerName = providerId,
        identifier = identifier,
        title = "",
    )
}

fun ShareResourceRef.toProviderTrackId(): String = "$providerId:$identifier"

fun ShareResourceRef.toProviderPlaylist(): ProviderPlaylist = ProviderPlaylist(
    id = "playlist:$providerId:$identifier",
    title = title,
    providerId = providerId,
    providerName = providerName,
)

fun ShareResourceRef.toProviderMediaItem(): ProviderMediaItem {
    val itemType = when (type) {
        ShareResourceType.Artist -> ProviderMediaItemType.Artist
        ShareResourceType.Album -> ProviderMediaItemType.Album
        else -> error("unsupported media item type: $type")
    }
    val prefix = when (itemType) {
        ProviderMediaItemType.Artist -> "artist"
        ProviderMediaItemType.Album -> "album"
    }
    return ProviderMediaItem(
        id = "$prefix:$providerId:$identifier",
        title = title,
        providerId = providerId,
        providerName = providerName,
        type = itemType,
    )
}

private val FUO_URI_REGEX = Regex("""fuo://([A-Za-z0-9_]+)/(?:(songs|playlists|artists|albums)/)?([A-Za-z0-9_-]+)""")

private fun providerIdentifier(id: String, providerId: String, prefix: String? = null): String? {
    if (id.isBlank() || providerId.isBlank()) return null
    val parts = id.split(":", limit = 3)
    return when {
        prefix == null && parts.size == 2 && parts[0] == providerId -> parts[1]
        prefix != null && parts.size == 3 && parts[0] == prefix && parts[1] == providerId -> parts[2]
        !id.contains(":") -> id
        else -> null
    }
}
