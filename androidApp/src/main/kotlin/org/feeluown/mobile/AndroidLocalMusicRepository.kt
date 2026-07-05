package org.feeluown.mobile

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class AndroidLocalMusicRepository(
    private val context: Context,
) : LocalMusicRepository {
    private var cachedTracks: List<MusicTrack> = emptyList()
    private var scanSettings = LocalMusicScanSettings()

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
        withContext(Dispatchers.IO) {
            scanSettings = settings
        }
    }

    override suspend fun directories(): List<LocalMusicDirectory> = withContext(Dispatchers.IO) {
        val counts = linkedMapOf<String, Pair<String, Int>>()
        queryAudioRows { row ->
            val directory = directoryInfo(row.relativePath)
            val current = counts[directory.id]
            counts[directory.id] = directory.name to ((current?.second ?: 0) + 1)
        }
        counts.map { (id, value) ->
            LocalMusicDirectory(
                id = id,
                name = value.first,
                trackCount = value.second,
            )
        }.sortedBy { it.name }
    }

    override suspend fun scan(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<MusicTrack>()
        val settings = scanSettings
        val excludedDirectoryIds = settings.excludedDirectoryIds.normalizedDirectoryIds()
        val metadataOverrides = metadataOverrides()
        val lyrics = queryLyrics()
        val appLyrics = queryAppLyrics()
        queryAudioRows { row ->
            val directory = directoryInfo(row.relativePath)
            if (directory.id in excludedDirectoryIds) return@queryAudioRows
            val durationMs = row.durationMs.takeIf { it > 0 }
            if (settings.minDurationSeconds > 0 &&
                (durationMs == null || durationMs < settings.minDurationSeconds * 1000L)
            ) {
                return@queryAudioRows
            }
            val sourceType = if (row.relativePath.contains(FEELUOWN_FOLDER)) {
                TrackSourceType.Downloaded
            } else {
                TrackSourceType.LocalMediaStore
            }
            val override = metadataOverrides[row.uri.toString()]
            tracks += MusicTrack(
                id = if (sourceType == TrackSourceType.Downloaded) {
                    "downloaded:${row.uri}"
                } else {
                    "local:${row.uri}"
                },
                title = override?.title ?: row.title,
                artists = override?.artists ?: row.artist,
                album = override?.album ?: row.album,
                source = "local",
                sourceType = sourceType,
                coverUrl = localCoverUri(row.uri, row.albumId),
                durationMs = durationMs,
                localUri = row.uri.toString(),
                lyrics = row.lyrics(lyrics, appLyrics),
            )
        }
        cachedTracks = tracks
        tracks
    }

    override suspend fun search(keyword: String): List<MusicTrack> {
        val tracks = cachedTracks.ifEmpty { scan() }
        val normalized = keyword.trim()
        if (normalized.isEmpty()) return emptyList()
        return tracks.filter {
            it.title.contains(normalized, ignoreCase = true) ||
                it.artists.contains(normalized, ignoreCase = true) ||
                it.album.contains(normalized, ignoreCase = true)
        }
    }

    override suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) {
        withContext(Dispatchers.IO) {
            val key = track.localUri ?: error("无法定位本地音乐文件")
            val nextMetadata = LocalTrackMetadata(
                title = metadata.title.ifBlank { track.title },
                artists = metadata.artists,
                album = metadata.album,
            )
            val overrides = metadataOverrides().toMutableMap()
            overrides[key] = nextMetadata
            saveMetadataOverrides(overrides)
            updateMediaStoreMetadata(key, nextMetadata)
            cachedTracks = cachedTracks.map {
                if (it.localUri == key) {
                    it.copy(
                        title = nextMetadata.title,
                        artists = nextMetadata.artists,
                        album = nextMetadata.album,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) {
        withContext(Dispatchers.IO) {
            val text = lyrics.takeIf { it.isNotBlank() } ?: return@withContext
            val fileName = lyricFileNameForTrack(track)
            val target = File(File(context.filesDir, LYRICS_FOLDER), fileName)
            val directory = requireNotNull(target.parentFile)
            if (!directory.exists()) directory.mkdirs()
            target.writeText(text, Charsets.UTF_8)
            cachedTracks = cachedTracks.map {
                if (it.id == track.id) it.copy(lyrics = text) else it
            }
        }
    }

    private fun queryAudioRows(onRow: (AudioRow) -> Unit) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)
        } catch (_: SecurityException) {
            null
        }?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val dataColumn = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                onRow(
                    AudioRow(
                        uri = uri,
                        title = cursor.getString(titleColumn).orEmpty(),
                        artist = cursor.getString(artistColumn).orEmpty(),
                        album = cursor.getString(albumColumn).orEmpty(),
                        albumId = cursor.getLong(albumIdColumn),
                        durationMs = cursor.getLong(durationColumn),
                        displayName = cursor.getString(displayNameColumn).orEmpty(),
                        relativePath = if (relativePathColumn >= 0) {
                            cursor.getString(relativePathColumn).orEmpty()
                        } else {
                            ""
                        },
                        filePath = if (dataColumn >= 0) {
                            cursor.getString(dataColumn).orEmpty()
                        } else {
                            ""
                        },
                    )
                )
            }
        }
    }

    private fun queryLyrics(): Map<String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyMap()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val result = linkedMapOf<String, String>()
        try {
            context.contentResolver.query(collection, projection, selection, arrayOf("%.lrc"), null)
        } catch (_: SecurityException) {
            null
        }?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(displayNameColumn).orEmpty()
                if (!displayName.endsWith(".lrc", ignoreCase = true)) continue
                val relativePath = cursor.getString(relativePathColumn).orEmpty()
                val key = lyricKey(relativePath, displayName) ?: continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val text = readText(uri) ?: continue
                result[key] = text
            }
        }
        return result
    }

    private fun queryAppLyrics(): Map<String, String> {
        val directory = File(context.filesDir, LYRICS_FOLDER)
        if (!directory.isDirectory) return emptyMap()
        val result = linkedMapOf<String, String>()
        directory.listFiles { file ->
            file.isFile && file.name.endsWith(".lrc", ignoreCase = true)
        }?.forEach { file ->
            val key = lyricBaseName(file.name)?.lowercase(Locale.ROOT) ?: return@forEach
            val text = file.runCatchingReadText() ?: return@forEach
            result[key] = text
        }
        return result
    }

    private fun readText(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun metadataOverrides(): Map<String, LocalTrackMetadata> {
        val file = metadataOverrideFile()
        if (!file.isFile) return emptyMap()
        return runCatching {
            val array = JSONArray(file.readText())
            val result = linkedMapOf<String, LocalTrackMetadata>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val uri = item.optString("uri").takeIf { it.isNotBlank() }
                if (uri != null) {
                    result[uri] = LocalTrackMetadata(
                        title = item.optString("title"),
                        artists = item.optString("artists"),
                        album = item.optString("album"),
                    )
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun saveMetadataOverrides(overrides: Map<String, LocalTrackMetadata>) {
        val array = JSONArray()
        overrides.forEach { (uri, metadata) ->
            array.put(
                JSONObject()
                    .put("uri", uri)
                    .put("title", metadata.title)
                    .put("artists", metadata.artists)
                    .put("album", metadata.album)
            )
        }
        metadataOverrideFile().writeText(array.toString())
    }

    private fun metadataOverrideFile(): File = File(context.filesDir, METADATA_OVERRIDE_FILE)

    private fun updateMediaStoreMetadata(uriString: String, metadata: LocalTrackMetadata) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (uri.scheme != "content") return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, metadata.title)
                put(MediaStore.Audio.Media.ARTIST, metadata.artists)
                put(MediaStore.Audio.Media.ALBUM, metadata.album)
            }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    private fun lyricFileNameForTrack(track: MusicTrack): String {
        val displayName = displayNameForTrack(track)
        val baseName = lyricBaseName(displayName)
            ?: track.title.takeIf { it.isNotBlank() }
            ?: "unknown"
        return "${sanitizeFileName(baseName)}.lrc"
    }

    private fun displayNameForTrack(track: MusicTrack): String {
        val uri = track.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri?.scheme == "file") {
            return uri.path?.let(::File)?.name.orEmpty().ifBlank { track.title }
        }
        if (uri?.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
            }.getOrNull()?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (column >= 0) return cursor.getString(column).orEmpty().ifBlank { track.title }
                }
            }
        }
        return track.title
    }

    private fun directoryInfo(relativePath: String): LocalMusicDirectory {
        val normalized = relativePath.trim('/').ifBlank { OTHER_DIRECTORY_ID }
        val name = if (normalized == OTHER_DIRECTORY_ID) "其他媒体库" else normalized
        return LocalMusicDirectory(
            id = normalized,
            name = name,
            trackCount = 0,
        )
    }

    private companion object {
        private const val FEELUOWN_FOLDER = "FeelUOwn"
        private const val LYRICS_FOLDER = "lyrics"
        private const val METADATA_OVERRIDE_FILE = "local_music_metadata.json"
        private const val OTHER_DIRECTORY_ID = "__other__"
    }
}

private data class AudioRow(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val displayName: String,
    val relativePath: String,
    val filePath: String,
)

private fun AudioRow.lyrics(folderLyrics: Map<String, String>, appLyrics: Map<String, String>): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val audioFile = filePath.takeIf { it.isNotBlank() }?.let(::File)
        val folderLyric = audioFile
            ?.resolveSibling("${audioFile.nameWithoutExtension}.lrc")
            ?.takeIf { it.isFile }
            ?.runCatchingReadText()
        if (folderLyric != null) return folderLyric
        val baseName = audioFile?.nameWithoutExtension ?: lyricBaseName(displayName) ?: return null
        return appLyrics[baseName.lowercase(Locale.ROOT)]
    }
    val folderLyric = lyricKey(relativePath, displayName)?.let(folderLyrics::get)
    if (folderLyric != null) return folderLyric
    val baseName = lyricBaseName(displayName)?.lowercase(Locale.ROOT) ?: return null
    return appLyrics[baseName]
}

private fun lyricKey(relativePath: String, fileName: String): String? {
    val baseName = lyricBaseName(fileName) ?: return null
    val directory = relativePath.trim('/').lowercase(Locale.ROOT)
    return "$directory/${baseName.lowercase(Locale.ROOT)}"
}

private fun lyricBaseName(fileName: String): String? {
    return fileName.substringBeforeLast('.', "").ifBlank { null }
}

private fun File.runCatchingReadText(): String? {
    return runCatching { readText() }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun Set<String>.normalizedDirectoryIds(): Set<String> {
    return flatMap { id ->
        val trimmed = id.trim('/')
        if (trimmed.isBlank()) listOf(id) else listOf(id, trimmed)
    }.toSet()
}

private fun sanitizeFileName(value: String): String {
    val sanitized = value.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    return sanitized.ifBlank { "unknown" }.take(80)
}

private fun albumArtUri(albumId: Long): String? {
    if (albumId <= 0) return null
    return Uri.parse("content://media/external/audio/albumart")
        .buildUpon()
        .appendPath(albumId.toString())
        .build()
        .toString()
}

private fun localCoverUri(audioUri: Uri, albumId: Long): String {
    return Uri.parse("fuo-cover://local")
        .buildUpon()
        .appendQueryParameter("audio", audioUri.toString())
        .appendQueryParameter("albumArt", albumArtUri(albumId).orEmpty())
        .build()
        .toString()
}
