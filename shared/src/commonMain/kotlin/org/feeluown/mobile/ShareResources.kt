package org.feeluown.mobile

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
    val appLinkUrl: String,
    val fuoUri: String,
    val providerUrl: String?,
    val text: String,
) {
    fun content(mode: ShareMode): String? = when (mode) {
        ShareMode.FullText -> text
        ShareMode.FuoLink -> appLinkUrl
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

@OptIn(ExperimentalEncodingApi::class)
fun ShareResourceRef.toSharePayload(): SharePayload {
    val fuoUri = "fuo://$providerId/${type.namespace}/$identifier"
    val appLinkUrl = "$FUO_EVOLVE_PAGES_BASE_URL/$providerId/${type.namespace}/$identifier?d=${shareTitleData(title)}"
    val introLine = when (type) {
        ShareResourceType.Song -> "分享一首歌："
        ShareResourceType.Playlist -> "分享一个歌单："
        ShareResourceType.Artist -> "分享一位歌手："
        ShareResourceType.Album -> "分享一张专辑："
    }
    val titleLine = when (type) {
        ShareResourceType.Song -> buildString {
            append("《")
            append(title)
            append("》")
            if (artists.isNotBlank()) append(" - ").append(artists)
            if (album.isNotBlank()) append("（专辑：").append(album).append("）")
        }
        ShareResourceType.Playlist -> "《$title》"
        ShareResourceType.Artist -> title
        ShareResourceType.Album -> "《$title》"
    }
    val openVerb = if (type == ShareResourceType.Song) "打开收听" else "打开查看"
    val providerVerb = if (type == ShareResourceType.Song) "收听" else "查看"
    val text = buildList {
        add(introLine)
        add(titleLine)
        add("$openVerb：$appLinkUrl")
        providerUrl?.takeIf { it.isNotBlank() }?.let { add("也可以在$providerName$providerVerb：$it") }
    }.joinToString("\n")
    return SharePayload(
        resource = this,
        appLinkUrl = appLinkUrl,
        fuoUri = fuoUri,
        providerUrl = providerUrl?.takeIf { it.isNotBlank() },
        text = text,
    )
}

fun parseSharedResource(text: String): ShareResourceRef? {
    parseFuoSharedResource(text)?.let { return it }
    return HTTP_URL_REGEX.findAll(text)
        .map { match -> match.value.trimEnd(*URL_TRAILING_PUNCTUATION) }
        .mapNotNull(::parseProviderSharedUrl)
        .firstOrNull()
}

fun sharedSearchQuery(text: String): String? {
    val title = SHARE_TITLE_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val artists = NETEASE_SHARE_ARTISTS_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (title.isNotBlank()) {
        return buildList {
            add(title)
            artists.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" ")
    }
    return HTTP_URL_REGEX.replace(text, " ")
        .replace(SHARE_SOURCE_SUFFIX_REGEX, " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', ':', '：', '-', '—')
        .takeIf { it.isNotBlank() }
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

const val FUO_EVOLVE_PAGES_BASE_URL = "https://feeluown.github.io/FuoEvolve/r"

private val FUO_URI_REGEX = Regex("""fuo://([A-Za-z0-9_]+)/(?:(songs|playlists|artists|albums)/)?([A-Za-z0-9_-]+)""")
private val FUO_EVOLVE_PAGES_REGEX =
    Regex("""https://feeluown\.github\.io/FuoEvolve/r/([A-Za-z0-9_]+)/(songs|playlists|artists|albums)/([A-Za-z0-9_-]+)""")
private val HTTP_URL_REGEX = Regex("""https?://[^\s<>\"'，。！？、；]+""", RegexOption.IGNORE_CASE)
private val NETEASE_RESOURCE_REGEX = Regex(
    """https?://(?:music\.163\.com|y\.music\.163\.com)/(?:#/)?(?:m/|f/)?(song|playlist|artist|album)(?:[/?#]|$)""",
    RegexOption.IGNORE_CASE,
)
private val BILIBILI_VIDEO_REGEX = Regex(
    """https?://(?:(?:www|m)\.)?bilibili\.com/video/(BV[0-9A-Za-z]+)""",
    RegexOption.IGNORE_CASE,
)
private val QQ_RESOURCE_REGEX = Regex(
    """https?://y\.qq\.com/n/ryqq/(songDetail|playlist|singer|albumDetail|album)/([^/?#]+)""",
    RegexOption.IGNORE_CASE,
)
private val YOUTUBE_SHORT_REGEX = Regex(
    """https?://(?:www\.)?youtu\.be/([A-Za-z0-9_-]+)""",
    RegexOption.IGNORE_CASE,
)
private val YOUTUBE_CHANNEL_REGEX = Regex(
    """https?://music\.youtube\.com/channel/([A-Za-z0-9_-]+)""",
    RegexOption.IGNORE_CASE,
)
private val YOUTUBE_BROWSE_REGEX = Regex(
    """https?://music\.youtube\.com/browse/([A-Za-z0-9_-]+)""",
    RegexOption.IGNORE_CASE,
)
private val SHARE_TITLE_REGEX = Regex("""《([^》]+)》""")
private val NETEASE_SHARE_ARTISTS_REGEX = Regex("""分享\s*(.+?)\s*的(?:单曲|歌曲)《""")
private val SHARE_SOURCE_SUFFIX_REGEX = Regex("""[（(]?\s*(?:来自\s*)?@?(?:网易云音乐|QQ音乐|哔哩哔哩|bilibili|YouTube Music|YouTube|Spotify|Apple Music)\s*[）)]?""", RegexOption.IGNORE_CASE)
private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ':', ';', '!', '?', ')', ']', '}', '。', '，', '：', '；', '！', '？')

private fun parseFuoSharedResource(text: String): ShareResourceRef? {
    val appLinkMatch = FUO_EVOLVE_PAGES_REGEX.find(text)
    val fuoMatch = FUO_URI_REGEX.find(text)
    val match = appLinkMatch ?: fuoMatch ?: return null
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

private fun parseProviderSharedUrl(url: String): ShareResourceRef? {
    parseNeteaseUrl(url)?.let { return it }
    parseBilibiliUrl(url)?.let { return it }
    parseQqMusicUrl(url)?.let { return it }
    return parseYtMusicUrl(url)
}

private fun parseNeteaseUrl(url: String): ShareResourceRef? {
    val resource = NETEASE_RESOURCE_REGEX.find(url)?.groupValues?.getOrNull(1)?.lowercase() ?: return null
    val identifier = queryParameter(url, "id") ?: return null
    val type = when (resource) {
        "song" -> ShareResourceType.Song
        "playlist" -> ShareResourceType.Playlist
        "artist" -> ShareResourceType.Artist
        "album" -> ShareResourceType.Album
        else -> return null
    }
    return providerResource(type, "netease", "网易云音乐", identifier, url)
}

private fun parseBilibiliUrl(url: String): ShareResourceRef? {
    val bvid = BILIBILI_VIDEO_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return null
    val page = queryParameter(url, "p")?.toIntOrNull()?.takeIf { it > 0 }
    val identifier = if (page == null) bvid else "paged_${bvid}__${page}"
    return providerResource(ShareResourceType.Song, "bilibili", "哔哩哔哩", identifier, url)
}

private fun parseQqMusicUrl(url: String): ShareResourceRef? {
    val pathMatch = QQ_RESOURCE_REGEX.find(url)
    if (pathMatch != null) {
        val type = when (pathMatch.groupValues[1].lowercase()) {
            "songdetail" -> ShareResourceType.Song
            "playlist" -> ShareResourceType.Playlist
            "singer" -> ShareResourceType.Artist
            "albumdetail", "album" -> ShareResourceType.Album
            else -> null
        }
        if (type != null) {
            return providerResource(type, "qqmusic", "QQ音乐", pathMatch.groupValues[2], url)
        }
    }
    if (!url.contains("qq.com", ignoreCase = true)) return null
    queryParameter(url, "songmid")?.let {
        return providerResource(ShareResourceType.Song, "qqmusic", "QQ音乐", it, url)
    }
    queryParameter(url, "disstid")?.let {
        return providerResource(ShareResourceType.Playlist, "qqmusic", "QQ音乐", it, url)
    }
    if (url.contains("taoge", ignoreCase = true) || url.contains("playlist", ignoreCase = true)) {
        queryParameter(url, "id")?.let {
            return providerResource(ShareResourceType.Playlist, "qqmusic", "QQ音乐", it, url)
        }
    }
    queryParameter(url, "singermid")?.let {
        return providerResource(ShareResourceType.Artist, "qqmusic", "QQ音乐", it, url)
    }
    queryParameter(url, "albummid")?.let {
        return providerResource(ShareResourceType.Album, "qqmusic", "QQ音乐", it, url)
    }
    return null
}

private fun parseYtMusicUrl(url: String): ShareResourceRef? {
    YOUTUBE_SHORT_REGEX.find(url)?.groupValues?.getOrNull(1)?.let {
        return providerResource(ShareResourceType.Song, "ytmusic", "YouTube Music", it, url)
    }
    if (!url.contains("youtube.com", ignoreCase = true)) return null
    if (url.contains("/watch", ignoreCase = true)) {
        queryParameter(url, "v")?.let {
            return providerResource(ShareResourceType.Song, "ytmusic", "YouTube Music", it, url)
        }
    }
    if (url.contains("/playlist", ignoreCase = true)) {
        queryParameter(url, "list")?.let {
            return providerResource(ShareResourceType.Playlist, "ytmusic", "YouTube Music", it, url)
        }
    }
    YOUTUBE_CHANNEL_REGEX.find(url)?.groupValues?.getOrNull(1)?.let {
        return providerResource(ShareResourceType.Artist, "ytmusic", "YouTube Music", it, url)
    }
    YOUTUBE_BROWSE_REGEX.find(url)?.groupValues?.getOrNull(1)?.let { identifier ->
        val type = when {
            identifier.startsWith("UC", ignoreCase = true) -> ShareResourceType.Artist
            identifier.startsWith("MPRE", ignoreCase = true) -> ShareResourceType.Album
            else -> return null
        }
        return providerResource(type, "ytmusic", "YouTube Music", identifier, url)
    }
    return null
}

private fun providerResource(
    type: ShareResourceType,
    providerId: String,
    providerName: String,
    identifier: String,
    providerUrl: String,
): ShareResourceRef? = identifier
    .takeIf { it.isNotBlank() }
    ?.let {
        ShareResourceRef(
            type = type,
            providerId = providerId,
            providerName = providerName,
            identifier = it,
            title = "",
            providerUrl = providerUrl,
        )
    }

private fun queryParameter(url: String, name: String): String? {
    val match = Regex("""(?:[?&#]|&amp;)${Regex.escape(name)}=([^&#\s]+)""", RegexOption.IGNORE_CASE).find(url)
    return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalEncodingApi::class)
private fun shareTitleData(title: String): String =
    Base64.UrlSafe.encode(title.encodeToByteArray()).trimEnd('=')

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
