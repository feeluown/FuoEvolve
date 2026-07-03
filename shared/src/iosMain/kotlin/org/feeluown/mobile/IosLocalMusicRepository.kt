package org.feeluown.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.MediaPlayer.MPMediaItem
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyAssetURL
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyPersistentID
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPMediaLibrary
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatus
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusAuthorized
import platform.MediaPlayer.MPMediaQuery

class IosLocalMusicRepository : LocalMusicRepository {
    private var cachedTracks: List<MusicTrack> = emptyList()
    private var scanSettings = LocalMusicScanSettings()

    val hasPermission: Boolean
        get() = MPMediaLibrary.authorizationStatus() == MPMediaLibraryAuthorizationStatusAuthorized

    fun requestPermission(onGranted: () -> Unit) {
        MPMediaLibrary.requestAuthorization { status: MPMediaLibraryAuthorizationStatus ->
            if (status == MPMediaLibraryAuthorizationStatusAuthorized) {
                onGranted()
            }
        }
    }

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
        scanSettings = settings
    }

    override suspend fun directories(): List<LocalMusicDirectory> = withContext(Dispatchers.Default) {
        if (!hasPermission) return@withContext emptyList()
        val counts = linkedMapOf<String, Int>()
        mediaItems().forEach { item ->
            val album = item.stringValue(MPMediaItemPropertyAlbumTitle).ifBlank { OTHER_DIRECTORY_ID }
            counts[album] = (counts[album] ?: 0) + 1
        }
        counts.map { (id, count) ->
            LocalMusicDirectory(
                id = id,
                name = if (id == OTHER_DIRECTORY_ID) "其他媒体库" else id,
                trackCount = count,
            )
        }.sortedBy { it.name }
    }

    override suspend fun scan(): List<MusicTrack> = withContext(Dispatchers.Default) {
        if (!hasPermission) return@withContext emptyList()
        val tracks = mediaItems().mapNotNull { item ->
            val url = item.urlValue(MPMediaItemPropertyAssetURL) ?: return@mapNotNull null
            val durationMs = (item.doubleValue(MPMediaItemPropertyPlaybackDuration) * 1000.0).toLong()
            if (scanSettings.minDurationSeconds > 0 && durationMs < scanSettings.minDurationSeconds * 1000L) {
                return@mapNotNull null
            }
            val album = item.stringValue(MPMediaItemPropertyAlbumTitle)
            val directoryId = album.ifBlank { OTHER_DIRECTORY_ID }
            if (directoryId in scanSettings.excludedDirectoryIds) return@mapNotNull null
            MusicTrack(
                id = "local:${item.stringValue(MPMediaItemPropertyPersistentID).ifBlank { url }}",
                title = item.stringValue(MPMediaItemPropertyTitle).ifBlank { "未知歌曲" },
                artists = item.stringValue(MPMediaItemPropertyArtist),
                album = album,
                source = "local",
                sourceType = TrackSourceType.LocalMediaStore,
                coverUrl = null,
                durationMs = durationMs.takeIf { it > 0 },
                localUri = url,
            )
        } + downloadedTracks()
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

    private fun mediaItems(): List<MPMediaItem> {
        return MPMediaQuery.songsQuery().items?.filterIsInstance<MPMediaItem>().orEmpty()
    }

    private fun downloadedTracks(): List<MusicTrack> = IosDownloadIndex.load().map { record ->
        MusicTrack(
            id = "downloaded:${record.uri}",
            title = record.title,
            artists = record.artists,
            album = record.album,
            source = record.source,
            sourceType = TrackSourceType.Downloaded,
            coverUrl = record.coverUrl,
            durationMs = record.durationMs,
            localUri = record.uri,
            providerId = record.trackId,
        )
    }

    private companion object {
        private const val OTHER_DIRECTORY_ID = "__other__"
    }
}

private fun MPMediaItem.stringValue(property: String): String {
    return (valueForProperty(property) as? Any)?.toString().orEmpty()
}

private fun MPMediaItem.doubleValue(property: String): Double {
    return (valueForProperty(property) as? Number)?.toDouble() ?: 0.0
}

private fun MPMediaItem.urlValue(property: String): String? {
    return valueForProperty(property)?.toString()?.takeIf { it.isNotBlank() }
}
