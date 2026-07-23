package org.feeluown.mobile.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.feeluown.mobile.LocalMusicDirectory
import org.feeluown.mobile.LocalMusicRepository
import org.feeluown.mobile.LocalMusicScanSettings
import org.feeluown.mobile.LocalTrackMetadata
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.TrackSourceType
import java.io.File

internal class DesktopLocalMusicRepository(
    private val musicDir: File = DesktopPaths.musicDir,
) : LocalMusicRepository {
    private val mutableMediaChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var scanSettings = LocalMusicScanSettings()
    private var cachedTracks: List<MusicTrack> = emptyList()
    private var initialized = false

    override val mediaChangeEvents: Flow<Unit> = mutableMediaChangeEvents

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
        scanSettings = settings
        cachedTracks = filter(cachedTracks)
    }

    override suspend fun isDatabaseReady(): Boolean = initialized

    override suspend fun isDatabaseStale(): Boolean = !initialized

    override suspend fun directories(): List<LocalMusicDirectory> = withContext(Dispatchers.IO) {
        ensureScanned()
        cachedTracks
            .mapNotNull { track -> track.localUri?.let { File(java.net.URI(it)).parentFile } }
            .groupBy { it.absolutePath }
            .map { (_, files) ->
                val directory = files.first()
                LocalMusicDirectory(
                    id = directory.absolutePath,
                    name = directory.name.ifBlank { directory.absolutePath },
                    trackCount = files.size,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun tracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        ensureScanned()
        filter(cachedTracks)
    }

    override suspend fun refreshDatabase(): List<MusicTrack> = withContext(Dispatchers.IO) {
        cachedTracks = scanMusicDir()
        initialized = true
        mutableMediaChangeEvents.tryEmit(Unit)
        filter(cachedTracks)
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
        cachedTracks = cachedTracks.map {
            if (it.id == track.id) {
                it.copy(
                    title = metadata.title.ifBlank { it.title },
                    artists = metadata.artists,
                    album = metadata.album,
                )
            } else {
                it
            }
        }
    }

    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) {
        val localUri = track.localUri ?: return
        withContext(Dispatchers.IO) {
            val audio = File(java.net.URI(localUri))
            val lyricFile = File(audio.parentFile, "${audio.nameWithoutExtension}.lrc")
            lyricFile.writeText(lyrics)
            cachedTracks = cachedTracks.map {
                if (it.id == track.id) it.copy(lyrics = lyrics) else it
            }
        }
    }

    private fun ensureScanned() {
        if (!initialized) {
            cachedTracks = scanMusicDir()
            initialized = true
        }
    }

    private fun scanMusicDir(): List<MusicTrack> {
        if (!musicDir.isDirectory) return emptyList()
        return musicDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .map { file -> file.toTrack() }
            .sortedBy { it.title.lowercase() }
            .toList()
    }

    private fun File.toTrack(): MusicTrack {
        val title = nameWithoutExtension.substringBefore(" - ").ifBlank { nameWithoutExtension }
        val artists = nameWithoutExtension.substringAfter(" - ", missingDelimiterValue = "").ifBlank { "未知歌手" }
        val cover = parentFile
            ?.listFiles()
            .orEmpty()
            .firstOrNull { it.isFile && it.nameWithoutExtension.lowercase() in COVER_NAMES && it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.toURI()
            ?.toString()
        val lyrics = File(parentFile, "$nameWithoutExtension.lrc").takeIf { it.isFile }?.readText()
        return MusicTrack(
            id = "local:${absolutePath}",
            title = title,
            artists = artists,
            album = parentFile?.name.orEmpty().ifBlank { "本地音乐" },
            source = "local",
            sourceType = TrackSourceType.LocalMediaStore,
            coverUrl = cover,
            localUri = toURI().toString(),
            lyrics = lyrics,
        )
    }

    private fun filter(tracks: List<MusicTrack>): List<MusicTrack> {
        val excluded = scanSettings.excludedDirectoryIds
        return tracks.filter { track ->
            val file = track.localUri?.let { runCatching { File(java.net.URI(it)) }.getOrNull() }
            file?.parentFile?.absolutePath !in excluded
        }
    }

    private companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_NAMES = setOf("cover", "folder", "front", "album")
    }
}
