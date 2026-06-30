package org.feeluown.mobile

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidLocalMusicRepository(
    private val context: Context,
) : LocalMusicRepository {
    private var cachedTracks: List<MusicTrack> = emptyList()

    override suspend fun scan(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<MusicTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
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
            val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val albumId = cursor.getLong(albumIdColumn)
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn).orEmpty() else ""
                val sourceType = if (relativePath.contains(FEELUOWN_FOLDER)) {
                    TrackSourceType.Downloaded
                } else {
                    TrackSourceType.LocalMediaStore
                }
                tracks += MusicTrack(
                    id = if (sourceType == TrackSourceType.Downloaded) {
                        "downloaded:${uri}"
                    } else {
                        "local:${uri}"
                    },
                    title = cursor.getString(titleColumn).orEmpty(),
                    artists = cursor.getString(artistColumn).orEmpty(),
                    album = cursor.getString(albumColumn).orEmpty(),
                    source = "local",
                    sourceType = sourceType,
                    coverUrl = albumArtUri(albumId),
                    durationMs = cursor.getLong(durationColumn).takeIf { it > 0 },
                    localUri = uri.toString(),
                )
            }
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

    private companion object {
        private const val FEELUOWN_FOLDER = "FeelUOwn"
    }
}

private fun albumArtUri(albumId: Long): String? {
    if (albumId <= 0) return null
    return Uri.parse("content://media/external/audio/albumart")
        .buildUpon()
        .appendPath(albumId.toString())
        .build()
        .toString()
}
