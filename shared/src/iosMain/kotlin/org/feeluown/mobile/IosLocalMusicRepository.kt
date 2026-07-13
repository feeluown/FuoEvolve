package org.feeluown.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import platform.Foundation.NSUserDefaults

class IosLocalMusicRepository(
    private val mediaLibrary: IosMediaLibraryOutput,
) : LocalMusicRepository {
    private var scanSettings = LocalMusicScanSettings()
    private var libraryTracks = emptyList<MusicTrack>()
    private var cachedTracks = emptyList<MusicTrack>()
    private val defaults = NSUserDefaults.standardUserDefaults

    val hasPermission: Boolean
        get() = mediaLibrary.hasPermission()

    fun requestPermission(onGranted: () -> Unit) {
        mediaLibrary.requestPermission { granted ->
            if (granted) onGranted()
        }
    }

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
        scanSettings = settings
        cachedTracks = filterTracks(libraryTracks)
    }

    override suspend fun isDatabaseReady(): Boolean = hasPermission

    override suspend fun isDatabaseStale(): Boolean = libraryTracks.isEmpty() && hasPermission

    override suspend fun directories(): List<LocalMusicDirectory> {
        if (cachedTracks.isEmpty()) return emptyList()
        return listOf(LocalMusicDirectory(LIBRARY_DIRECTORY_ID, "媒体资料库", cachedTracks.size))
    }

    override suspend fun tracks(): List<MusicTrack> = if (libraryTracks.isEmpty()) refreshDatabase() else cachedTracks

    override suspend fun refreshDatabase(): List<MusicTrack> {
        if (!hasPermission) return emptyList()
        val root = Json.parseToJsonElement(mediaLibrary.tracksJson()).jsonObject
        libraryTracks = root["tracks"]?.jsonArray.orEmpty().map { element ->
            val item = element.jsonObject
            val persistentId = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            applyOverrides(MusicTrack(
                id = "ios-media:$persistentId",
                title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "未知歌曲" },
                artists = item["artists"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "未知歌手" },
                album = item["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                source = "本地音乐",
                sourceType = TrackSourceType.LocalMediaStore,
                durationMs = item["duration_ms"]?.jsonPrimitive?.longOrNull,
                localUri = item["local_uri"]?.jsonPrimitive?.contentOrNull,
            ))
        }
        cachedTracks = filterTracks(libraryTracks)
        return cachedTracks
    }

    override suspend fun search(keyword: String): List<MusicTrack> {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) return tracks()
        return tracks().filter {
            it.title.contains(normalized, ignoreCase = true) ||
                it.artists.contains(normalized, ignoreCase = true) ||
                it.album.contains(normalized, ignoreCase = true)
        }
    }

    override suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) {
        defaults.setObject(
            listOf(metadata.title, metadata.artists, metadata.album).joinToString(METADATA_SEPARATOR),
            metadataKey(track.id),
        )
        defaults.synchronize()
        libraryTracks = libraryTracks.map { item ->
            if (item.id == track.id) {
                item.copy(title = metadata.title, artists = metadata.artists, album = metadata.album)
            } else {
                item
            }
        }
        cachedTracks = filterTracks(libraryTracks)
    }

    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) {
        defaults.setObject(lyrics, lyricsKey(track.id))
        defaults.synchronize()
        libraryTracks = libraryTracks.map { item ->
            if (item.id == track.id) item.copy(lyrics = lyrics) else item
        }
        cachedTracks = filterTracks(libraryTracks)
    }

    private fun applyOverrides(track: MusicTrack): MusicTrack {
        val metadata = defaults.stringForKey(metadataKey(track.id))?.split(METADATA_SEPARATOR, limit = 3)
        return track.copy(
            title = metadata?.getOrNull(0)?.takeIf { it.isNotBlank() } ?: track.title,
            artists = metadata?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: track.artists,
            album = metadata?.getOrNull(2) ?: track.album,
            lyrics = defaults.stringForKey(lyricsKey(track.id))?.takeIf { it.isNotBlank() },
        )
    }

    private fun metadataKey(trackId: String) = "ios_local_metadata_$trackId"

    private fun lyricsKey(trackId: String) = "ios_local_lyrics_$trackId"

    private fun filterTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        if (LIBRARY_DIRECTORY_ID in scanSettings.excludedDirectoryIds) return emptyList()
        val minDurationMs = scanSettings.minDurationSeconds.toLong() * 1_000L
        return tracks.filter { (it.durationMs ?: 0) >= minDurationMs }
    }

    private companion object {
        private const val LIBRARY_DIRECTORY_ID = "ios-media-library"
        private const val METADATA_SEPARATOR = "\u001f"
    }
}
