package org.feeluown.mobile

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class AndroidLocalMusicRepository(
    context: Context,
) : LocalMusicRepository {
    private val appContext = context.applicationContext
    private val database = LocalMusicDatabase(appContext)
    private val mutableMediaChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    private var scanSettings = LocalMusicScanSettings()

    override val mediaChangeEvents: Flow<Unit> = mutableMediaChangeEvents

    init {
        val observer = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                mutableMediaChangeEvents.tryEmit(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                mutableMediaChangeEvents.tryEmit(Unit)
            }
        }
        appContext.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        appContext.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,
            observer,
        )
    }

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
        withContext(Dispatchers.IO) {
            scanSettings = settings
        }
    }

    override suspend fun isDatabaseReady(): Boolean = withContext(Dispatchers.IO) {
        database.readableDatabase.getMeta(KEY_INITIALIZED) == "1"
    }

    override suspend fun isDatabaseStale(): Boolean = withContext(Dispatchers.IO) {
        val stored = database.readableDatabase.getMeta(KEY_MEDIA_FINGERPRINT)
        stored == null || stored != queryMediaFingerprint()
    }

    override suspend fun directories(): List<LocalMusicDirectory> = withContext(Dispatchers.IO) {
        val db = database.readableDatabase
        val result = mutableListOf<LocalMusicDirectory>()
        db.rawQuery(
            """
            SELECT directory_id, directory_name, COUNT(*)
            FROM tracks
            GROUP BY directory_id, directory_name
            ORDER BY directory_name
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LocalMusicDirectory(
                    id = cursor.getString(0).orEmpty(),
                    name = cursor.getString(1).orEmpty(),
                    trackCount = cursor.getInt(2),
                )
            }
        }
        result
    }

    override suspend fun tracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        database.readableDatabase.readTracks(scanSettings)
    }

    override suspend fun refreshDatabase(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val rows = queryAudioRows()
        val metadataOverrides = metadataOverrides()
        val folderLyrics = queryLyrics()
        val appLyrics = queryAppLyrics()
        val records = rows.map { row ->
            val directory = directoryInfo(row.relativePath)
            val sourceType = if (row.relativePath.contains(FEELUOWN_FOLDER)) {
                TrackSourceType.Downloaded
            } else {
                TrackSourceType.LocalMediaStore
            }
            val override = metadataOverrides[row.uri.toString()]
            LocalTrackRecord(
                track = MusicTrack(
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
                    durationMs = row.durationMs.takeIf { it > 0 },
                    localUri = row.uri.toString(),
                    lyrics = row.lyrics(folderLyrics, appLyrics),
                ),
                directory = directory,
                displayName = row.displayName,
                mediaId = row.mediaId,
                dateAdded = row.dateAdded,
                dateModified = row.dateModified,
            )
        }
        database.writableDatabase.replaceTracks(records, mediaFingerprint(rows))
        database.readableDatabase.readTracks(scanSettings)
    }

    override suspend fun search(keyword: String): List<MusicTrack> {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) return emptyList()
        return tracks().filter {
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
            database.writableDatabase.updateTrackMetadata(key, nextMetadata)
        }
    }

    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) {
        withContext(Dispatchers.IO) {
            val text = lyrics.takeIf { it.isNotBlank() } ?: return@withContext
            val fileName = lyricFileNameForTrack(track)
            val target = File(File(appContext.filesDir, LYRICS_FOLDER), fileName)
            val directory = requireNotNull(target.parentFile)
            if (!directory.exists()) directory.mkdirs()
            target.writeText(text, Charsets.UTF_8)
            track.localUri?.let { database.writableDatabase.updateTrackLyrics(it, text) }
        }
    }

    private fun queryAudioRows(): List<AudioRow> {
        val rows = mutableListOf<AudioRow>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
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
            appContext.contentResolver.query(collection, projection, selection, null, sortOrder)
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
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
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
                rows += AudioRow(
                    uri = ContentUris.withAppendedId(collection, id),
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
                    mediaId = id,
                    dateAdded = cursor.getLong(dateAddedColumn),
                    dateModified = cursor.getLong(dateModifiedColumn),
                )
            }
        }
        return rows
    }

    private fun queryMediaFingerprint(): String = mediaFingerprint(queryAudioRows())

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
            appContext.contentResolver.query(collection, projection, selection, arrayOf("%.lrc"), null)
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
        val directory = File(appContext.filesDir, LYRICS_FOLDER)
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
            appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
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

    private fun metadataOverrideFile(): File = File(appContext.filesDir, METADATA_OVERRIDE_FILE)

    private fun updateMediaStoreMetadata(uriString: String, metadata: LocalTrackMetadata) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (uri.scheme != "content") return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, metadata.title)
                put(MediaStore.Audio.Media.ARTIST, metadata.artists)
                put(MediaStore.Audio.Media.ALBUM, metadata.album)
            }
            appContext.contentResolver.update(uri, values, null, null)
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
                appContext.contentResolver.query(
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
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_MEDIA_FINGERPRINT = "media_fingerprint"
        private const val KEY_LAST_REFRESH_AT = "last_refresh_at"
    }
}

private class LocalMusicDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
                id TEXT PRIMARY KEY NOT NULL,
                local_uri TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                artists TEXT NOT NULL,
                album TEXT NOT NULL,
                source TEXT NOT NULL,
                source_type TEXT NOT NULL,
                cover_url TEXT,
                duration_ms INTEGER,
                lyrics TEXT,
                directory_id TEXT NOT NULL,
                directory_name TEXT NOT NULL,
                display_name TEXT NOT NULL,
                media_id INTEGER NOT NULL,
                date_added INTEGER NOT NULL,
                date_modified INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX tracks_directory_idx ON tracks(directory_id)")
        db.execSQL("CREATE INDEX tracks_title_idx ON tracks(title)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS tracks")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    private companion object {
        private const val DATABASE_NAME = "local_music.db"
        private const val DATABASE_VERSION = 1
    }
}

private data class LocalTrackRecord(
    val track: MusicTrack,
    val directory: LocalMusicDirectory,
    val displayName: String,
    val mediaId: Long,
    val dateAdded: Long,
    val dateModified: Long,
)

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
    val mediaId: Long,
    val dateAdded: Long,
    val dateModified: Long,
)

private fun SQLiteDatabase.replaceTracks(records: List<LocalTrackRecord>, fingerprint: String) {
    beginTransaction()
    try {
        delete("tracks", null, null)
        records.forEach { record ->
            replace("tracks", null, record.toContentValues())
        }
        putMeta("initialized", "1")
        putMeta("media_fingerprint", fingerprint)
        putMeta("last_refresh_at", System.currentTimeMillis().toString())
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun SQLiteDatabase.readTracks(settings: LocalMusicScanSettings): List<MusicTrack> {
    val result = mutableListOf<MusicTrack>()
    val excludedDirectoryIds = settings.excludedDirectoryIds.normalizedDirectoryIds()
    rawQuery(
        """
        SELECT id, title, artists, album, source, source_type, cover_url, duration_ms, local_uri, lyrics, directory_id
        FROM tracks
        ORDER BY date_added DESC, title COLLATE NOCASE ASC
        """.trimIndent(),
        null,
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val directoryId = cursor.getString(10).orEmpty()
            if (directoryId in excludedDirectoryIds) continue
            val durationMs = cursor.getLong(7).takeIf { it > 0 }
            if (settings.minDurationSeconds > 0 &&
                (durationMs == null || durationMs < settings.minDurationSeconds * 1000L)
            ) {
                continue
            }
            result += MusicTrack(
                id = cursor.getString(0).orEmpty(),
                title = cursor.getString(1).orEmpty(),
                artists = cursor.getString(2).orEmpty(),
                album = cursor.getString(3).orEmpty(),
                source = cursor.getString(4).orEmpty(),
                sourceType = runCatching { TrackSourceType.valueOf(cursor.getString(5).orEmpty()) }
                    .getOrDefault(TrackSourceType.LocalMediaStore),
                coverUrl = cursor.getString(6)?.takeIf { it.isNotBlank() },
                durationMs = durationMs,
                localUri = cursor.getString(8).orEmpty(),
                lyrics = cursor.getString(9)?.takeIf { it.isNotBlank() },
            )
        }
    }
    return result
}

private fun SQLiteDatabase.updateTrackMetadata(localUri: String, metadata: LocalTrackMetadata) {
    update(
        "tracks",
        ContentValues().apply {
            put("title", metadata.title)
            put("artists", metadata.artists)
            put("album", metadata.album)
        },
        "local_uri = ?",
        arrayOf(localUri),
    )
}

private fun SQLiteDatabase.updateTrackLyrics(localUri: String, lyrics: String) {
    update(
        "tracks",
        ContentValues().apply { put("lyrics", lyrics) },
        "local_uri = ?",
        arrayOf(localUri),
    )
}

private fun SQLiteDatabase.getMeta(key: String): String? {
    query("meta", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { cursor ->
        return if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

private fun SQLiteDatabase.putMeta(key: String, value: String) {
    replace(
        "meta",
        null,
        ContentValues().apply {
            put("key", key)
            put("value", value)
        },
    )
}

private fun LocalTrackRecord.toContentValues(): ContentValues {
    return ContentValues().apply {
        put("id", track.id)
        put("local_uri", track.localUri.orEmpty())
        put("title", track.title)
        put("artists", track.artists)
        put("album", track.album)
        put("source", track.source)
        put("source_type", track.sourceType.name)
        put("cover_url", track.coverUrl)
        put("duration_ms", track.durationMs ?: 0)
        put("lyrics", track.lyrics)
        put("directory_id", directory.id)
        put("directory_name", directory.name)
        put("display_name", displayName)
        put("media_id", mediaId)
        put("date_added", dateAdded)
        put("date_modified", dateModified)
    }
}

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

private fun mediaFingerprint(rows: List<AudioRow>): String {
    var maxDateAdded = 0L
    var maxDateModified = 0L
    var hash = 1125899906842597L
    rows.forEach { row ->
        if (row.dateAdded > maxDateAdded) maxDateAdded = row.dateAdded
        if (row.dateModified > maxDateModified) maxDateModified = row.dateModified
        hash = hash * 31 + row.mediaId
        hash = hash * 31 + row.dateModified
        hash = hash * 31 + row.durationMs
    }
    return "${rows.size}:$maxDateAdded:$maxDateModified:$hash"
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
    return Uri.Builder()
        .scheme("fuo-local-cover")
        .appendQueryParameter("albumArt", albumArtUri(albumId).orEmpty())
        .appendQueryParameter("audio", audioUri.toString())
        .build()
        .toString()
}
