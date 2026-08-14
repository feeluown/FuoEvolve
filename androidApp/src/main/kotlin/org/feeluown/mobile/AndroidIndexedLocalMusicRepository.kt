package org.feeluown.mobile

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Locale

/**
 * Incremental MediaStore index used by the Android local library.
 *
 * The legacy repository is retained for media observation and write operations (metadata / lyrics)
 * so this migration changes indexing only. Track rows are upserted when MediaStore's modification
 * timestamp or the associated offline asset identity changes; missing media ids are removed.
 */
internal class AndroidIndexedLocalMusicRepository(
    context: Context,
    private val assetStore: AndroidOfflineAssetStore,
) : LocalMusicRepository {
    private val appContext = context.applicationContext
    private val legacy = AndroidLocalMusicRepository(appContext)
    private val database = IndexedLocalMusicDatabase(appContext)

    @Volatile
    private var scanSettings = LocalMusicScanSettings()

    override val mediaChangeEvents: Flow<Unit> = legacy.mediaChangeEvents

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
        scanSettings = settings
        legacy.updateScanSettings(settings)
    }

    override suspend fun isDatabaseReady(): Boolean = withContext(Dispatchers.IO) {
        database.readableDatabase.indexMeta(INDEX_KEY_INITIALIZED) == "1"
    }

    override suspend fun isDatabaseStale(): Boolean = withContext(Dispatchers.IO) {
        val db = database.readableDatabase
        if (db.indexMeta(INDEX_KEY_LAYOUT_VERSION) != INDEX_LAYOUT_VERSION) return@withContext true
        val rows = queryAudioRows()
        if (db.indexMeta(INDEX_KEY_FINGERPRINT) != mediaFingerprint(rows)) return@withContext true
        val stored = db.readStoredSignatures()
        val assetsByUri = assetStore.all().associateBy { it.localUri }
        val folderLyrics = queryLyrics()
        val appLyrics = queryAppLyrics()
        rows.any { row ->
            val uri = row.uri.toString()
            val signature = stored[row.mediaId] ?: return@any true
            signature.localUri != uri ||
                signature.assetId != assetsByUri[uri]?.id ||
                signature.lyrics != row.indexedLyrics(folderLyrics, appLyrics)
        }
    }

    override suspend fun directories(): List<LocalMusicDirectory> = withContext(Dispatchers.IO) {
        database.readableDatabase.readDirectories()
    }

    override suspend fun tracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        database.readableDatabase.readIndexedTracks(scanSettings)
    }

    override suspend fun refreshDatabase(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val rows = queryAudioRows()
        val fingerprint = mediaFingerprint(rows)
        val imageCovers = queryImageCovers()
        val fallbackCovers = rows
            .groupBy { it.directory.id }
            .mapValues { (_, directoryRows) ->
                directoryRows
                    .sortedWith(
                        compareBy<IndexedAudioRow> { it.displayName.lowercase(Locale.ROOT) }
                            .thenBy { it.mediaId },
                    )
                    .firstOrNull()
                    ?.let { indexedLocalCoverUri(it.uri, it.albumId) }
            }
        val directories = rows
            .map { it.directory }
            .distinctBy { it.id }
            .map { directory ->
                directory.copy(coverUrl = imageCovers[directory.id] ?: fallbackCovers[directory.id])
            }

        val assetsByUri = assetStore.all().associateBy { it.localUri }
        val metadataOverrides = metadataOverrides()
        val folderLyrics = queryLyrics()
        val appLyrics = queryAppLyrics()
        val db = database.writableDatabase
        val stored = db.readStoredSignatures()
        val currentIds = rows.mapTo(linkedSetOf()) { it.mediaId }
        val changed = rows.mapNotNull { row ->
        val uri = row.uri.toString()
        val asset = assetsByUri[uri]
        val signature = stored[row.mediaId]
        val lyrics = row.indexedLyrics(folderLyrics, appLyrics)
        if (signature != null &&
            signature.dateModified == row.dateModified &&
            signature.localUri == uri &&
            signature.assetId == asset?.id &&
            signature.lyrics == lyrics
        ) {
            return@mapNotNull null
        }
        row.toRecord(
            asset = asset,
            metadataOverride = metadataOverrides[uri],
            lyrics = lyrics,
        )
    }
            db.syncIndex(
            changedRecords = changed,
            currentMediaIds = currentIds,
            directories = directories,
            fingerprint = fingerprint,
        )
        db.readIndexedTracks(scanSettings)
    }

    override suspend fun search(keyword: String): List<MusicTrack> = withContext(Dispatchers.IO) {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) emptyList()
        else database.readableDatabase.readIndexedTracks(scanSettings, normalized)
    }

    override suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) {
        legacy.updateMetadata(track, metadata)
        val localUri = track.localUri ?: return
        database.writableDatabase.updateIndexedMetadata(
            localUri = localUri,
            metadata = LocalTrackMetadata(
                title = metadata.title.ifBlank { track.title },
                artists = metadata.artists,
                album = metadata.album,
            ),
        )
    }

    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) {
        legacy.saveLyrics(track, lyrics)
        val localUri = track.localUri ?: return
        val text = lyrics.takeIf { it.isNotBlank() } ?: return
        database.writableDatabase.updateIndexedLyrics(localUri, text)
    }

    private fun queryAudioRows(): List<IndexedAudioRow> {
        val rows = mutableListOf<IndexedAudioRow>()
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
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn).orEmpty() else ""
                val filePath = if (dataColumn >= 0) cursor.getString(dataColumn).orEmpty() else ""
                val directory = indexedDirectoryInfo(relativePath, filePath) ?: continue
                val mediaId = cursor.getLong(idColumn)
                rows += IndexedAudioRow(
                    uri = ContentUris.withAppendedId(collection, mediaId),
                    title = cursor.getString(titleColumn).orEmpty(),
                    artist = cursor.getString(artistColumn).orEmpty(),
                    album = cursor.getString(albumColumn).orEmpty(),
                    albumId = cursor.getLong(albumIdColumn),
                    durationMs = cursor.getLong(durationColumn),
                    displayName = cursor.getString(displayNameColumn).orEmpty(),
                    relativePath = relativePath,
                    filePath = filePath,
                    directory = directory,
                    mediaId = mediaId,
                    dateAdded = cursor.getLong(dateAddedColumn),
                    dateModified = cursor.getLong(dateModifiedColumn),
                )
            }
        }
        return rows
    }

    private fun queryImageCovers(): Map<String, String> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
            else {
                @Suppress("DEPRECATION")
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()
        val rows = mutableListOf<IndexedImageRow>()
        try {
            appContext.contentResolver.query(collection, projection, null, null, null)
        } catch (_: SecurityException) {
            null
        }?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else -1
            val dataColumn = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            } else -1
            while (cursor.moveToNext()) {
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn).orEmpty() else ""
                val filePath = if (dataColumn >= 0) cursor.getString(dataColumn).orEmpty() else ""
                val directory = indexedDirectoryInfo(relativePath, filePath) ?: continue
                rows += IndexedImageRow(
                    directoryId = directory.id,
                    displayName = cursor.getString(nameColumn).orEmpty(),
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                )
            }
        }
        return rows
            .sortedWith(compareBy<IndexedImageRow> { it.directoryId }.thenBy { it.displayName.lowercase(Locale.ROOT) })
            .distinctBy { it.directoryId }
            .associate { it.directoryId to it.uri.toString() }
    }

    private fun queryLyrics(): Map<String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyMap()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val result = linkedMapOf<String, String>()
        try {
            appContext.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%.lrc"),
                null,
            )
        } catch (_: SecurityException) {
            null
        }?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameColumn).orEmpty()
                if (!displayName.endsWith(".lrc", ignoreCase = true)) continue
                val key = indexedLyricKey(cursor.getString(pathColumn).orEmpty(), displayName) ?: continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val text = runCatching {
                    appContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                result[key] = text
            }
        }
        return result
    }

    private fun queryAppLyrics(): Map<String, String> {
        val directory = File(appContext.filesDir, INDEX_LYRICS_FOLDER)
        if (!directory.isDirectory) return emptyMap()
        return buildMap {
            directory.listFiles { file -> file.isFile && file.name.endsWith(".lrc", ignoreCase = true) }
                ?.forEach { file ->
                    val key = indexedLyricBaseName(file.name)?.lowercase(Locale.ROOT) ?: return@forEach
                    val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
                    put(key, text)
                }
        }
    }

    private fun metadataOverrides(): Map<String, LocalTrackMetadata> {
        val file = File(appContext.filesDir, INDEX_METADATA_OVERRIDE_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val array = JSONArray(file.readText())
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                    put(
                        uri,
                        LocalTrackMetadata(
                            title = item.optString("title"),
                            artists = item.optString("artists"),
                            album = item.optString("album"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }
}

private class IndexedLocalMusicDatabase(context: Context) :
    SQLiteOpenHelper(context, INDEX_DATABASE_NAME, null, INDEX_DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
                row_id TEXT PRIMARY KEY NOT NULL,
                id TEXT NOT NULL,
                local_uri TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                artists TEXT NOT NULL,
                album TEXT NOT NULL,
                source TEXT NOT NULL,
                source_type TEXT NOT NULL,
                provider_id TEXT,
                provider_name TEXT,
                cover_url TEXT,
                duration_ms INTEGER NOT NULL,
                lyrics TEXT,
                directory_id TEXT NOT NULL,
                directory_name TEXT NOT NULL,
                media_id INTEGER NOT NULL UNIQUE,
                date_added INTEGER NOT NULL,
                date_modified INTEGER NOT NULL,
                asset_id TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX tracks_directory_idx ON tracks(directory_id)")
        db.execSQL("CREATE INDEX tracks_title_idx ON tracks(title)")
        db.execSQL("CREATE TABLE directories (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, cover_url TEXT)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS tracks")
        db.execSQL("DROP TABLE IF EXISTS directories")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }
}

private data class IndexedAudioRow(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val displayName: String,
    val relativePath: String,
    val filePath: String,
    val directory: LocalMusicDirectory,
    val mediaId: Long,
    val dateAdded: Long,
    val dateModified: Long,
)

private data class IndexedImageRow(
    val directoryId: String,
    val displayName: String,
    val uri: Uri,
)

private data class IndexedTrackRecord(
    val track: MusicTrack,
    val directory: LocalMusicDirectory,
    val mediaId: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val assetId: String?,
)

private data class StoredIndexedSignature(
        val dateModified: Long,
        val localUri: String,
        val assetId: String?,
        val lyrics: String?,
    )

    private fun IndexedAudioRow.toRecord(
        asset: OfflineAsset?,
        metadataOverride: LocalTrackMetadata?,
        lyrics: String?,
    ): IndexedTrackRecord {
        val legacyDownloaded = directory.id == INDEX_FEELUOWN_DIRECTORY_ID
        val sourceType = if (asset != null || legacyDownloaded) TrackSourceType.Downloaded else TrackSourceType.LocalMediaStore
        val uriString = uri.toString()
        val title = metadataOverride?.title ?: asset?.title ?: this.title
        val artists = metadataOverride?.artists ?: asset?.artists ?: artist
        val album = metadataOverride?.album ?: asset?.album ?: this.album
        val track = MusicTrack(
            id = asset?.providerTrackId ?: if (sourceType == TrackSourceType.Downloaded) "downloaded:$uriString" else "local:$uriString",
            title = title,
            artists = artists,
            album = album,
            source = asset?.source ?: "local",
            sourceType = sourceType,
            coverUrl = indexedLocalCoverUri(uri, albumId),
            durationMs = (asset?.durationMs ?: durationMs).takeIf { it > 0 },
            localUri = uriString,
            localDirectoryId = directory.id,
            lyrics = lyrics,
            providerId = asset?.providerId,
            providerName = asset?.providerName,
        )
        return IndexedTrackRecord(
            track = track,
            directory = directory,
            mediaId = mediaId,
            dateAdded = dateAdded,
            dateModified = dateModified,
            assetId = asset?.id,
        )
    }

private fun SQLiteDatabase.syncIndex(
    changedRecords: List<IndexedTrackRecord>,
    currentMediaIds: Set<Long>,
    directories: List<LocalMusicDirectory>,
    fingerprint: String,
) {
    beginTransaction()
    try {
        val existingIds = mutableListOf<Long>()
        rawQuery("SELECT media_id FROM tracks", null).use { cursor ->
            while (cursor.moveToNext()) existingIds += cursor.getLong(0)
        }
        existingIds.asSequence()
            .filterNot(currentMediaIds::contains)
            .forEach { mediaId -> delete("tracks", "media_id = ?", arrayOf(mediaId.toString())) }
        changedRecords.forEach { record -> replace("tracks", null, record.toContentValues()) }
        delete("directories", null, null)
        directories.forEach { directory ->
            replace(
                "directories",
                null,
                ContentValues().apply {
                    put("id", directory.id)
                    put("name", directory.name)
                    put("cover_url", directory.coverUrl)
                },
            )
        }
        putIndexMeta(INDEX_KEY_INITIALIZED, "1")
        putIndexMeta(INDEX_KEY_LAYOUT_VERSION, INDEX_LAYOUT_VERSION)
        putIndexMeta(INDEX_KEY_FINGERPRINT, fingerprint)
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun SQLiteDatabase.readStoredSignatures(): Map<Long, StoredIndexedSignature> {
        val result = linkedMapOf<Long, StoredIndexedSignature>()
        rawQuery("SELECT media_id, date_modified, local_uri, asset_id, lyrics FROM tracks", null).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getLong(0)] = StoredIndexedSignature(
                    dateModified = cursor.getLong(1),
                    localUri = cursor.getString(2).orEmpty(),
                    assetId = cursor.getString(3)?.takeIf { it.isNotBlank() },
                    lyrics = cursor.getString(4)?.takeIf { it.isNotBlank() },
                )
            }
        }
        return result
    }

private fun SQLiteDatabase.readDirectories(): List<LocalMusicDirectory> {
    val counts = linkedMapOf<String, Int>()
    rawQuery("SELECT directory_id, COUNT(*) FROM tracks GROUP BY directory_id", null).use { cursor ->
        while (cursor.moveToNext()) counts[cursor.getString(0).orEmpty()] = cursor.getInt(1)
    }
    val result = mutableListOf<LocalMusicDirectory>()
    rawQuery("SELECT id, name, cover_url FROM directories ORDER BY name COLLATE NOCASE", null).use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getString(0).orEmpty()
            result += LocalMusicDirectory(
                id = id,
                name = cursor.getString(1).orEmpty(),
                trackCount = counts[id] ?: 0,
                coverUrl = cursor.getString(2)?.takeIf { it.isNotBlank() },
            )
        }
    }
    return result
}

private fun SQLiteDatabase.readIndexedTracks(
    settings: LocalMusicScanSettings,
    keyword: String? = null,
): List<MusicTrack> {
    val where = mutableListOf<String>()
    val args = mutableListOf<String>()
    val excluded = settings.excludedDirectoryIds.flatMap(::localMusicDirectoryIdAliases).toSet()
    if (excluded.isNotEmpty()) {
        where += "directory_id NOT IN (${excluded.joinToString(",") { "?" }})"
        args += excluded
    }
    if (settings.minDurationSeconds > 0) {
        where += "duration_ms >= ?"
        args += (settings.minDurationSeconds * 1000L).toString()
    }
    keyword?.takeIf { it.isNotBlank() }?.let { value ->
        where += "(title LIKE ? COLLATE NOCASE OR artists LIKE ? COLLATE NOCASE OR album LIKE ? COLLATE NOCASE)"
        val pattern = "%$value%"
        repeat(3) { args += pattern }
    }
    val selection = where.takeIf { it.isNotEmpty() }?.joinToString(" AND ")?.let { "WHERE $it" }.orEmpty()
    val result = mutableListOf<MusicTrack>()
    rawQuery(
        """
        SELECT id, title, artists, album, source, source_type, provider_id, provider_name,
               cover_url, duration_ms, local_uri, lyrics, directory_id
        FROM tracks
        $selection
        ORDER BY date_added DESC, title COLLATE NOCASE ASC
        """.trimIndent(),
        args.toTypedArray(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            result += MusicTrack(
                id = cursor.getString(0).orEmpty(),
                title = cursor.getString(1).orEmpty(),
                artists = cursor.getString(2).orEmpty(),
                album = cursor.getString(3).orEmpty(),
                source = cursor.getString(4).orEmpty(),
                sourceType = runCatching { TrackSourceType.valueOf(cursor.getString(5).orEmpty()) }
                    .getOrDefault(TrackSourceType.LocalMediaStore),
                providerId = cursor.getString(6)?.takeIf { it.isNotBlank() },
                providerName = cursor.getString(7)?.takeIf { it.isNotBlank() },
                coverUrl = cursor.getString(8)?.takeIf { it.isNotBlank() },
                durationMs = cursor.getLong(9).takeIf { it > 0 },
                localUri = cursor.getString(10).orEmpty(),
                lyrics = cursor.getString(11)?.takeIf { it.isNotBlank() },
                localDirectoryId = cursor.getString(12).orEmpty(),
            )
        }
    }
    return result
}

private fun IndexedTrackRecord.toContentValues(): ContentValues = ContentValues().apply {
    put("row_id", assetId ?: track.localUri.orEmpty())
    put("id", track.id)
    put("local_uri", track.localUri.orEmpty())
    put("title", track.title)
    put("artists", track.artists)
    put("album", track.album)
    put("source", track.source)
    put("source_type", track.sourceType.name)
    put("provider_id", track.providerId)
    put("provider_name", track.providerName)
    put("cover_url", track.coverUrl)
    put("duration_ms", track.durationMs ?: 0)
    put("lyrics", track.lyrics)
    put("directory_id", directory.id)
    put("directory_name", directory.name)
    put("media_id", mediaId)
    put("date_added", dateAdded)
    put("date_modified", dateModified)
    put("asset_id", assetId)
}

private fun SQLiteDatabase.updateIndexedMetadata(localUri: String, metadata: LocalTrackMetadata) {
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

private fun SQLiteDatabase.updateIndexedLyrics(localUri: String, lyrics: String) {
    update("tracks", ContentValues().apply { put("lyrics", lyrics) }, "local_uri = ?", arrayOf(localUri))
}

private fun SQLiteDatabase.indexMeta(key: String): String? {
    query("meta", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { cursor ->
        return if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

private fun SQLiteDatabase.putIndexMeta(key: String, value: String) {
    replace("meta", null, ContentValues().apply { put("key", key); put("value", value) })
}

private fun indexedDirectoryInfo(relativePath: String, filePath: String): LocalMusicDirectory? {
    val firstLevelName = if (relativePath.isNotBlank()) {
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotBlank() }
        if (segments.firstOrNull()?.equals(INDEX_MUSIC_DIRECTORY_NAME, ignoreCase = true) != true) return null
        segments.getOrNull(1)
    } else {
        val musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            .path.replace('\\', '/').trimEnd('/')
        val directoryPath = filePath.replace('\\', '/').substringBeforeLast('/', "").trimEnd('/')
        if (directoryPath != musicRoot && !directoryPath.startsWith("$musicRoot/")) return null
        if (directoryPath == musicRoot) null
        else directoryPath.removePrefix("$musicRoot/").substringBefore('/').takeIf { it.isNotBlank() }
    }
    return if (firstLevelName.isNullOrBlank()) {
        LocalMusicDirectory(id = INDEX_MUSIC_ROOT_DIRECTORY_ID, name = "歌曲", trackCount = 0)
    } else {
        LocalMusicDirectory(id = "$INDEX_MUSIC_DIRECTORY_NAME/$firstLevelName/", name = firstLevelName, trackCount = 0)
    }
}

private fun IndexedAudioRow.indexedLyrics(
    folderLyrics: Map<String, String>,
    appLyrics: Map<String, String>,
): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val audioFile = filePath.takeIf { it.isNotBlank() }?.let(::File)
        audioFile?.resolveSibling("${audioFile.nameWithoutExtension}.lrc")
            ?.takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull()?.takeIf(String::isNotBlank) }
            ?.let { return it }
        val base = audioFile?.nameWithoutExtension ?: indexedLyricBaseName(displayName) ?: return null
        return appLyrics[base.lowercase(Locale.ROOT)]
    }
    indexedLyricKey(relativePath, displayName)?.let(folderLyrics::get)?.let { return it }
    val base = indexedLyricBaseName(displayName)?.lowercase(Locale.ROOT) ?: return null
    return appLyrics[base]
}

private fun mediaFingerprint(rows: List<IndexedAudioRow>): String {
    var maxAdded = 0L
    var maxModified = 0L
    var hash = 1125899906842597L
    rows.forEach { row ->
        maxAdded = maxOf(maxAdded, row.dateAdded)
        maxModified = maxOf(maxModified, row.dateModified)
        hash = hash * 31 + row.mediaId
        hash = hash * 31 + row.dateModified
        hash = hash * 31 + row.durationMs
    }
    return "${rows.size}:$maxAdded:$maxModified:$hash"
}

private fun indexedLyricKey(relativePath: String, fileName: String): String? {
    val base = indexedLyricBaseName(fileName) ?: return null
    return "${relativePath.trim('/').lowercase(Locale.ROOT)}/${base.lowercase(Locale.ROOT)}"
}

private fun indexedLyricBaseName(fileName: String): String? =
    fileName.substringBeforeLast('.', "").ifBlank { null }

private fun indexedAlbumArtUri(albumId: Long): String? {
    if (albumId <= 0) return null
    return Uri.parse("content://media/external/audio/albumart")
        .buildUpon()
        .appendPath(albumId.toString())
        .build()
        .toString()
}

private fun indexedLocalCoverUri(audioUri: Uri, albumId: Long): String = Uri.Builder()
    .scheme("fuo-cover")
    .appendQueryParameter("albumArt", indexedAlbumArtUri(albumId).orEmpty())
    .appendQueryParameter("audio", audioUri.toString())
    .build()
    .toString()

private const val INDEX_DATABASE_NAME = "local_music_index_v3.db"
private const val INDEX_DATABASE_VERSION = 2
private const val INDEX_LAYOUT_VERSION = "music-first-level-v3-incremental"
private const val INDEX_KEY_INITIALIZED = "initialized"
private const val INDEX_KEY_LAYOUT_VERSION = "layout_version"
private const val INDEX_KEY_FINGERPRINT = "media_fingerprint"
private const val INDEX_MUSIC_DIRECTORY_NAME = "Music"
private const val INDEX_MUSIC_ROOT_DIRECTORY_ID = "Music/"
private const val INDEX_FEELUOWN_DIRECTORY_ID = "Music/FeelUOwn/"
private const val INDEX_METADATA_OVERRIDE_FILE = "local_music_metadata.json"
private const val INDEX_LYRICS_FOLDER = "lyrics"
