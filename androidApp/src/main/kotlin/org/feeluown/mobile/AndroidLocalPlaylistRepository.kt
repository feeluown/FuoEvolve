package org.feeluown.mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidLocalPlaylistRepository(
    context: Context,
) : LocalPlaylistRepository {
    private val collectionsDirectory = File(context.applicationContext.filesDir, COLLECTIONS_DIRECTORY)
    private val mutex = Mutex()

    override suspend fun list(): List<LocalPlaylist> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            collectionsDirectory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals(FILE_EXTENSION, ignoreCase = true) }
                .sortedBy { it.name }
                .mapNotNull(::readPlaylist)
        }
    }

    override suspend fun create(title: String): LocalPlaylistOperationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val normalizedTitle = title.trim()
            if (normalizedTitle.isBlank()) {
                return@withLock LocalPlaylistOperationResult(false, "歌单名称不能为空")
            }
            ensureDirectory()
            val fileName = uniqueFileName(normalizedTitle)
            val playlist = LocalPlaylist(
                id = fileName,
                fileName = fileName,
                title = normalizedTitle,
            )
            writePlaylist(playlist)
            LocalPlaylistOperationResult(true, "已新建歌单：$normalizedTitle", playlist)
        }
    }

    override suspend fun delete(playlist: LocalPlaylist): LocalPlaylistOperationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = fileFor(playlist.fileName)
            if (!target.exists() || !target.delete()) {
                return@withLock LocalPlaylistOperationResult(false, "删除歌单失败：${playlist.title}")
            }
            LocalPlaylistOperationResult(true, "已删除歌单：${playlist.title}")
        }
    }

    override suspend fun addTrack(
        playlist: LocalPlaylist,
        track: LocalPlaylistTrack,
    ): LocalPlaylistOperationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPlaylist(fileFor(playlist.fileName))
                ?: return@withLock LocalPlaylistOperationResult(false, "歌单不存在")
            if (current.tracks.any { it.uri == track.uri }) {
                return@withLock LocalPlaylistOperationResult(false, "歌曲已在歌单中", current)
            }
            val updated = current.copy(tracks = current.tracks + track)
            writePlaylist(updated)
            LocalPlaylistOperationResult(true, "已添加到：${current.title}", updated)
        }
    }

    override suspend fun removeTrack(
        playlist: LocalPlaylist,
        uri: String,
    ): LocalPlaylistOperationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPlaylist(fileFor(playlist.fileName))
                ?: return@withLock LocalPlaylistOperationResult(false, "歌单不存在")
            val updated = current.copy(tracks = current.tracks.filterNot { it.uri == uri })
            writePlaylist(updated)
            LocalPlaylistOperationResult(true, "已从歌单移除", updated)
        }
    }

    override suspend fun importPlaylist(
        preview: LocalPlaylistImportPreview,
        mode: LocalPlaylistImportMode,
        replacePlaylist: LocalPlaylist?,
    ): LocalPlaylistOperationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val target = when (mode) {
                LocalPlaylistImportMode.Replace -> {
                    replacePlaylist ?: return@withLock LocalPlaylistOperationResult(false, "没有可覆盖的歌单")
                    LocalPlaylist(
                        id = replacePlaylist.id,
                        fileName = replacePlaylist.fileName,
                        title = preview.title,
                        description = preview.description,
                        tracks = preview.tracks,
                    )
                }
                LocalPlaylistImportMode.CreateNew -> {
                    val fileName = uniqueFileName(preview.title)
                    LocalPlaylist(
                        id = fileName,
                        fileName = fileName,
                        title = preview.title,
                        description = preview.description,
                        tracks = preview.tracks,
                    )
                }
            }
            writePlaylist(target)
            val skippedMessage = preview.skippedLineCount.takeIf { it > 0 }
                ?.let { "，已跳过 $it 行不支持内容" }
                .orEmpty()
            LocalPlaylistOperationResult(true, "已导入：${target.title}$skippedMessage", target)
        }
    }

    override suspend fun export(playlist: LocalPlaylist): LocalPlaylistFile = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPlaylist(fileFor(playlist.fileName)) ?: playlist
            LocalPlaylistFile(current.fileName, LocalPlaylistFileCodec.encode(current))
        }
    }

    private fun ensureDirectory() {
        check(collectionsDirectory.exists() || collectionsDirectory.mkdirs()) {
            "无法创建本地歌单目录"
        }
    }

    private fun fileFor(fileName: String): File {
        val safeName = File(fileName).name
        return File(collectionsDirectory, safeName)
    }

    private fun readPlaylist(file: File): LocalPlaylist? {
        if (!file.isFile) return null
        return runCatching {
            val preview = LocalPlaylistFileCodec.decode(file.name, file.readText(Charsets.UTF_8))
            LocalPlaylist(
                id = file.name,
                fileName = file.name,
                title = preview.title,
                description = preview.description,
                tracks = preview.tracks,
            )
        }.getOrNull()
    }

    private fun writePlaylist(playlist: LocalPlaylist) {
        ensureDirectory()
        val target = fileFor(playlist.fileName)
        val temp = File(collectionsDirectory, ".${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(LocalPlaylistFileCodec.encode(playlist).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            // renameTo is atomic on the same filesystem. The fallback is only
            // for filesystems that refuse replacing an existing target.
            if (target.exists() && !target.delete()) {
                temp.delete()
                error("无法替换本地歌单文件：${target.name}")
            }
            check(temp.renameTo(target)) { "无法写入本地歌单文件：${target.name}" }
        }
    }

    private fun uniqueFileName(title: String): String {
        val base = LocalPlaylistFileCodec.sanitizeFileName(title).ifBlank { "playlist" }
        var candidate = "$base.$FILE_EXTENSION"
        var index = 2
        while (fileFor(candidate).exists()) {
            candidate = "${base}_$index.$FILE_EXTENSION"
            index += 1
        }
        return candidate
    }

    private companion object {
        const val COLLECTIONS_DIRECTORY = "collections"
        const val FILE_EXTENSION = "fuo"
    }
}
