package org.feeluown.mobile

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
class IosLocalPlaylistRepository : LocalPlaylistRepository {
    private val fileSystem = FileSystem.SYSTEM
    private val fileManager = NSFileManager.defaultManager
    private val mutex = Mutex()

    override suspend fun list(): List<LocalPlaylist> = mutex.withLock {
        directory().let { directory ->
            fileSystem.list(directory)
                .filter { it.name.substringAfterLast('.').equals(FILE_EXTENSION, ignoreCase = true) }
                .sortedBy { it.name }
                .mapNotNull(::readPlaylist)
        }
    }

    override suspend fun create(title: String): LocalPlaylistOperationResult = mutex.withLock {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) {
            return@withLock LocalPlaylistOperationResult(false, "歌单名称不能为空")
        }
        val fileName = uniqueFileName(normalizedTitle)
        val playlist = LocalPlaylist(fileName, fileName, normalizedTitle)
        writePlaylist(playlist)
        LocalPlaylistOperationResult(true, "已新建歌单：${playlist.title}", playlist)
    }

    override suspend fun delete(playlist: LocalPlaylist): LocalPlaylistOperationResult = mutex.withLock {
        val target = fileFor(playlist.fileName)
        if (!fileSystem.exists(target)) {
            return@withLock LocalPlaylistOperationResult(false, "歌单不存在")
        }
        fileSystem.delete(target)
        LocalPlaylistOperationResult(true, "已删除歌单：${playlist.title}")
    }

    override suspend fun addTrack(
        playlist: LocalPlaylist,
        track: LocalPlaylistTrack,
    ): LocalPlaylistOperationResult = mutex.withLock {
        val current = readPlaylist(fileFor(playlist.fileName))
            ?: return@withLock LocalPlaylistOperationResult(false, "歌单不存在")
        if (current.tracks.any { it.uri == track.uri }) {
            return@withLock LocalPlaylistOperationResult(false, "歌曲已在歌单中", current)
        }
        val updated = current.copy(tracks = current.tracks + track)
        writePlaylist(updated)
        LocalPlaylistOperationResult(true, "已添加到：${current.title}", updated)
    }

    override suspend fun removeTrack(
        playlist: LocalPlaylist,
        uri: String,
    ): LocalPlaylistOperationResult = mutex.withLock {
        val current = readPlaylist(fileFor(playlist.fileName))
            ?: return@withLock LocalPlaylistOperationResult(false, "歌单不存在")
        val updated = current.copy(tracks = current.tracks.filterNot { it.uri == uri })
        writePlaylist(updated)
        LocalPlaylistOperationResult(true, "已从歌单移除", updated)
    }

    override suspend fun importPlaylist(
        preview: LocalPlaylistImportPreview,
        mode: LocalPlaylistImportMode,
        replacePlaylist: LocalPlaylist?,
    ): LocalPlaylistOperationResult = mutex.withLock {
        val target = when (mode) {
            LocalPlaylistImportMode.Replace -> {
                replacePlaylist ?: return@withLock LocalPlaylistOperationResult(false, "没有可覆盖的歌单")
                LocalPlaylist(
                    replacePlaylist.id,
                    replacePlaylist.fileName,
                    preview.title,
                    preview.description,
                    preview.tracks,
                )
            }
            LocalPlaylistImportMode.CreateNew -> {
                val fileName = uniqueFileName(preview.title)
                LocalPlaylist(fileName, fileName, preview.title, preview.description, preview.tracks)
            }
        }
        writePlaylist(target)
        val skippedMessage = preview.skippedLineCount.takeIf { it > 0 }
            ?.let { "，已跳过 $it 行不支持内容" }
            .orEmpty()
        LocalPlaylistOperationResult(true, "已导入：${target.title}$skippedMessage", target)
    }

    override suspend fun export(playlist: LocalPlaylist): LocalPlaylistFile = mutex.withLock {
        val current = readPlaylist(fileFor(playlist.fileName)) ?: playlist
        LocalPlaylistFile(current.fileName, LocalPlaylistFileCodec.encode(current))
    }

    private fun directory(): Path {
        val documentsPath = requireNotNull(
            fileManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )?.path,
        )
        val directory = ("$documentsPath/$COLLECTIONS_DIRECTORY").toPath()
        fileSystem.createDirectories(directory)
        return directory
    }

    private fun fileFor(fileName: String): Path = directory() / safeFileName(fileName)

    private fun readPlaylist(file: Path): LocalPlaylist? {
        if (!fileSystem.exists(file)) return null
        return runCatching {
            val preview = fileSystem.read(file) { readUtf8() }
            val decoded = LocalPlaylistFileCodec.decode(file.name, preview)
            LocalPlaylist(file.name, file.name, decoded.title, decoded.description, decoded.tracks)
        }.getOrNull()
    }

    private fun writePlaylist(playlist: LocalPlaylist) {
        val target = fileFor(playlist.fileName)
        val temp = directory() / ".${target.name}.${kotlin.random.Random.nextLong()}.tmp"
        fileSystem.write(temp) { writeUtf8(LocalPlaylistFileCodec.encode(playlist)) }
        runCatching {
            fileSystem.atomicMove(temp, target)
        }.onFailure {
            if (fileSystem.exists(target)) fileSystem.delete(target)
            fileSystem.atomicMove(temp, target)
        }
    }

    private fun uniqueFileName(title: String): String {
        val base = LocalPlaylistFileCodec.sanitizeFileName(title).ifBlank { "playlist" }
        var candidate = "$base.$FILE_EXTENSION"
        var index = 2
        while (fileSystem.exists(fileFor(candidate))) {
            candidate = "${base}_$index.$FILE_EXTENSION"
            index += 1
        }
        return candidate
    }

    private fun safeFileName(fileName: String): String {
        return fileName.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "playlist.$FILE_EXTENSION" }
    }

    private companion object {
        const val COLLECTIONS_DIRECTORY = "collections"
        const val FILE_EXTENSION = "fuo"
    }
}
