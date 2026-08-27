package org.feeluown.mobile.provider.netease

import io.ktor.http.Parameters
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.MediaRef
import org.feeluown.mobile.MediaRefType
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderSearchHit
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.asString
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.mediaItemKey
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.playlistKey
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.trackKey
import org.feeluown.mobile.provider.core.videoKey
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

/**
 * NetEase default search uses cloud-search (`type=1018`) for the full result set and the native
 * multimatch endpoint for the provider-ranked best match. The old typed-search implementation
 * remains the compatibility fallback when the native comprehensive response cannot be consumed.
 */
internal class NeteaseComprehensiveSearchProvider(
    private val delegate: NeteaseProvider,
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) : KotlinMusicProvider by delegate {
    override suspend fun search(keyword: String): ProviderSearchResults = coroutineScope {
        if (keyword.isBlank()) return@coroutineScope ProviderSearchResults()

        val comprehensiveDeferred = async {
            runCatching { comprehensiveSearch(keyword) }.getOrNull()
        }
        val multimatchDeferred = async {
            runCatching { multimatchBestMatches(keyword) }.getOrElse { emptyList() }
        }

        val comprehensive = comprehensiveDeferred.await()
        val multimatch = multimatchDeferred.await()
        if (comprehensive != null && comprehensive.hasCatalogResults()) {
            return@coroutineScope comprehensive.copy(
                bestMatches = multimatch.ifEmpty {
                    comprehensive.bestMatches.ifEmpty { comprehensive.fallbackBestMatches(keyword) }
                },
            )
        }

        val fallback = delegate.search(keyword)
        fallback.copy(
            bestMatches = multimatch.ifEmpty {
                fallback.bestMatches.ifEmpty { fallback.fallbackBestMatches(keyword) }
            },
        )
    }

    private suspend fun comprehensiveSearch(keyword: String): ProviderSearchResults {
        val raw = http.postForm(
            providerId = ID,
            url = "$BASE/api/cloudsearch/pc",
            form = Parameters.build {
                append("s", keyword)
                append("type", COMPREHENSIVE_SEARCH_TYPE.toString())
                append("offset", "0")
                append("total", "true")
                append("limit", "30")
            },
            headers = authenticatedHeaders(),
            cacheKey = "netease:search:comprehensive:$keyword",
            cachePolicy = ProviderCachePolicies.search,
        ).value
        val root = providerJson.parseToJsonElement(raw).asObject()
        val result = root.obj("result") ?: root
        val values = searchResultsFrom(result)
        return values.copy(
            bestMatches = orderedBestMatches(result, values).ifEmpty {
                values.fallbackBestMatches(keyword)
            },
        )
    }

    private suspend fun multimatchBestMatches(keyword: String): List<ProviderSearchHit> {
        val raw = http.postForm(
            providerId = ID,
            url = "$BASE/api/search/suggest/multimatch",
            form = Parameters.build {
                append("s", keyword)
                append("type", "1")
            },
            headers = authenticatedHeaders(),
            cacheKey = "netease:search:multimatch:$keyword",
            cachePolicy = ProviderCachePolicies.search,
        ).value
        val root = providerJson.parseToJsonElement(raw).asObject()
        val result = root.obj("result") ?: root
        val values = searchResultsFrom(result)
        return orderedBestMatches(result, values).ifEmpty {
            values.fallbackBestMatches(keyword)
        }
    }

    private fun searchResultsFrom(result: JsonObject): ProviderSearchResults = ProviderSearchResults(
        tracks = songValues(result).mapNotNull(::toTrack).distinctBy { it.id },
        playlists = playlistValues(result).mapNotNull(::toPlaylist).distinctBy { it.id },
        artists = artistValues(result).mapNotNull(::toArtist).distinctBy { it.id },
        albums = albumValues(result).mapNotNull(::toAlbum).distinctBy { it.id },
        videos = mvValues(result).mapNotNull(::toVideo).distinctBy { it.id },
    )

    private suspend fun authenticatedHeaders(): Map<String, String> {
        val cookie = cookieHeader(credentials.read(ID))
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("Referer", "$BASE/")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
    }

    private fun songValues(result: JsonObject): JsonArray = firstNonEmpty(
        result.array("songs"),
        result.obj("song").arrayOrEmpty("songs"),
        result.obj("song").arrayOrEmpty("items"),
        result.obj("song").arrayOrEmpty("list"),
    )

    private fun artistValues(result: JsonObject): JsonArray = firstNonEmpty(
        result.array("artists"),
        result.obj("artist").arrayOrEmpty("artists"),
        result.obj("artist").arrayOrEmpty("items"),
        result.obj("artist").arrayOrEmpty("list"),
    )

    private fun albumValues(result: JsonObject): JsonArray = firstNonEmpty(
        result.array("albums"),
        result.obj("album").arrayOrEmpty("albums"),
        result.obj("album").arrayOrEmpty("items"),
        result.obj("album").arrayOrEmpty("list"),
    )

    private fun playlistValues(result: JsonObject): JsonArray = firstNonEmpty(
        result.array("playlists"),
        result.array("playLists"),
        result.obj("playlist").arrayOrEmpty("playlists"),
        result.obj("playlist").arrayOrEmpty("playLists"),
        result.obj("playList").arrayOrEmpty("playlists"),
        result.obj("playList").arrayOrEmpty("playLists"),
        result.obj("playList").arrayOrEmpty("items"),
    )

    private fun mvValues(result: JsonObject): JsonArray = firstNonEmpty(
        result.array("mvs"),
        result.obj("mv").arrayOrEmpty("mvs"),
        result.obj("mv").arrayOrEmpty("items"),
        result.obj("mv").arrayOrEmpty("list"),
    )

    private fun orderedBestMatches(result: JsonObject, values: ProviderSearchResults): List<ProviderSearchHit> {
        val order = firstNonEmpty(result.array("orders"), result.array("order"))
            .map { it.asString() }
            .filter(String::isNotBlank)
        if (order.isEmpty()) return emptyList()
        return order.asSequence().mapNotNull { key -> bestMatchForKey(key, values) }.take(1).toList()
    }

    private fun bestMatchForKey(key: String, values: ProviderSearchResults): ProviderSearchHit? =
        when (key.lowercase()) {
            "song", "songs" -> values.tracks.firstOrNull()?.let(ProviderSearchHit::Track)
            "artist", "artists" -> values.artists.firstOrNull()?.let(ProviderSearchHit::Artist)
            "album", "albums" -> values.albums.firstOrNull()?.let(ProviderSearchHit::Album)
            "playlist", "playlists", "playlistresult" -> values.playlists.firstOrNull()?.let(ProviderSearchHit::Playlist)
            "mv", "mvs", "video", "videos" -> values.videos.firstOrNull()?.let(ProviderSearchHit::Video)
            else -> null
        }

    private fun ProviderSearchResults.fallbackBestMatches(keyword: String): List<ProviderSearchHit> {
        val normalized = normalize(keyword)
        val exact = sequenceOf(
            artists.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Artist),
            albums.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Album),
            tracks.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Track),
            playlists.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Playlist),
            videos.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Video),
        ).filterNotNull().firstOrNull()
        val fallback = tracks.firstOrNull()?.let(ProviderSearchHit::Track)
            ?: artists.firstOrNull()?.let(ProviderSearchHit::Artist)
            ?: albums.firstOrNull()?.let(ProviderSearchHit::Album)
            ?: playlists.firstOrNull()?.let(ProviderSearchHit::Playlist)
            ?: videos.firstOrNull()?.let(ProviderSearchHit::Video)
        return listOfNotNull(exact ?: fallback)
    }

    private fun ProviderSearchResults.hasCatalogResults(): Boolean =
        tracks.isNotEmpty() || playlists.isNotEmpty() || artists.isNotEmpty() || albums.isNotEmpty() || videos.isNotEmpty()

    private fun toTrack(value: JsonElement): MusicTrack? {
        val item = runCatching { value.asObject() }.getOrNull() ?: return null
        val identifier = item.string("id").ifBlank { item.string("songId") }
        if (identifier.isBlank()) return null
        val artistValues = item.array("ar").takeIf { it.isNotEmpty() } ?: item.array("artists")
        val artist = artistValues.firstOrNull()?.let { runCatching { it.asObject() }.getOrNull() }
        val album = item.obj("al") ?: item.obj("album")
        return MusicTrack(
            id = trackKey(ID, identifier),
            title = item.string("name").ifBlank { item.string("title") },
            artists = artistValues.mapNotNull { runCatching { it.asObject().string("name") }.getOrNull() }
                .filter(String::isNotBlank)
                .joinToString(" / ")
                .ifBlank { item.string("artistName") },
            album = album?.string("name").orEmpty().ifBlank { item.string("albumName") },
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = album?.stringOrNull("picUrl") ?: item.stringOrNull("picUrl"),
            durationMs = item.long("dt") ?: item.long("duration") ?: item.long("interval")?.times(1_000),
            providerId = trackKey(ID, identifier),
            providerName = NAME,
            artistItemId = artist?.stringOrNull("id")?.let { mediaItemKey(MediaRefType.Artist, ID, it) },
            albumItemId = album?.stringOrNull("id")?.let { mediaItemKey(MediaRefType.Album, ID, it) },
            providerUrl = "$BASE/#/song?id=$identifier",
        )
    }

    private fun toArtist(value: JsonElement): MediaRef? {
        val item = runCatching { value.asObject() }.getOrNull() ?: return null
        val identifier = item.string("id")
        if (identifier.isBlank()) return null
        return MediaRef(
            id = mediaItemKey(MediaRefType.Artist, ID, identifier),
            title = item.string("name"),
            providerId = ID,
            providerName = NAME,
            type = MediaRefType.Artist,
            coverUrl = item.stringOrNull("picUrl") ?: item.stringOrNull("avatar") ?: item.stringOrNull("cover"),
            providerUrl = "$BASE/#/artist?id=$identifier",
        )
    }

    private fun toAlbum(value: JsonElement): MediaRef? {
        val item = runCatching { value.asObject() }.getOrNull() ?: return null
        val identifier = item.string("id")
        if (identifier.isBlank()) return null
        return MediaRef(
            id = mediaItemKey(MediaRefType.Album, ID, identifier),
            title = item.string("name"),
            providerId = ID,
            providerName = NAME,
            type = MediaRefType.Album,
            coverUrl = item.stringOrNull("picUrl") ?: item.stringOrNull("coverUrl") ?: item.stringOrNull("cover"),
            providerUrl = "$BASE/#/album?id=$identifier",
        )
    }

    private fun toPlaylist(value: JsonElement): ProviderPlaylist? {
        val item = runCatching { value.asObject() }.getOrNull() ?: return null
        val identifier = item.string("id")
        if (identifier.isBlank()) return null
        return ProviderPlaylist(
            id = playlistKey(ID, identifier),
            title = item.string("name"),
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("coverImgUrl") ?: item.stringOrNull("picUrl"),
            description = item.string("description").ifBlank {
                item.obj("creator")?.stringOrNull("nickname") ?: item.string("creatorName")
            },
            playCount = item.long("playCount"),
            providerUrl = "$BASE/#/playlist?id=$identifier",
            trackCount = item.int("trackCount"),
        )
    }

    private fun toVideo(value: JsonElement): ProviderVideo? {
        val item = runCatching { value.asObject() }.getOrNull() ?: return null
        val identifier = item.string("id").ifBlank { item.string("mvId") }
        if (identifier.isBlank()) return null
        val artists = item.array("artists").mapNotNull { element ->
            runCatching { element.asObject().string("name") }.getOrNull()
        }.filter(String::isNotBlank).joinToString(" / ")
        return ProviderVideo(
            id = videoKey(ID, identifier),
            title = item.string("name").ifBlank { item.string("title") },
            artists = artists.ifBlank { item.string("artistName") },
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("cover") ?: item.stringOrNull("imgurl") ?: item.stringOrNull("coverUrl"),
            durationMs = item.long("duration") ?: item.long("durationMs"),
            providerUrl = "$BASE/#/mv?id=$identifier",
        )
    }

    private fun JsonObject?.arrayOrEmpty(key: String): JsonArray = this?.array(key) ?: JsonArray(emptyList())

    private fun firstNonEmpty(vararg values: JsonArray): JsonArray =
        values.firstOrNull { it.isNotEmpty() } ?: JsonArray(emptyList())

    private fun normalize(value: String): String = value.trim().lowercase().replace(" ", "")

    private companion object {
        const val ID = "netease"
        const val NAME = "网易云音乐"
        const val BASE = "https://music.163.com"
        const val COMPREHENSIVE_SEARCH_TYPE = 1018
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}
