package org.feeluown.mobile

import kotlinx.coroutines.sync.Mutex

/**
 * Minimal persistence boundary for local playlist collection files.
 *
 * File naming, directory selection and atomic replacement are platform responsibilities. The
 * playlist lifecycle and `.fuo` semantics stay in common code so desktop/mobile hosts do not grow
 * parallel repository implementations.
 */
interface LocalPlaylistFileStorage {
    suspend fun listFileNames(): List<String>
    suspend fun readText(fileName: String): String?
    suspend fun writeTextAtomically(fileName: String, content: String)
    suspend fun delete(fileName: String): Boolean
    suspend fun exists(fileName: String): Boolean
}

class FileBackedLocalPlaylistRepository(
    private val storage: LocalPlaylistFileStorage,
    private val fileExtension: String = "fuo",
) : LocalPlaylistRepository {
    private val mutex = Mutex()

    override suspend fun list(): List<LocalPlaylist> = locked {
        storage.listFileNames()
            .filter { it.substringAfterLast('.', missingDelimiterValue = "").equals(fileExtension, ignoreCase = true) }
            .sorted()
            .mapNotNull { fileName -> readPlaylist(fileName) }
    }

    override suspend fun create(title: String): LocalPlaylistOperationResult = locked {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) {
            return@locked LocalPlaylistOperationResult(false, "歌单名称不能为空")
        }
        val fileName = uniqueFileName(normalizedTitle)
        val playlist = LocalPlaylist(
            id = fileName,
            fileName = fileName,
            title = normalizedTitle,
        )
        writePlaylist(playlist)
        LocalPlaylistOperationResult(true, "已新建歌单：$normalizedTitle", playlist)
    }

    override suspend fun delete(playlist: LocalPlaylist): LocalPlaylistOperationResult = locked {
        if (!storage.delete(playlist.fileName)) {
            return@locked LocalPlaylistOperationResult(false, "删除歌单失败：${playlist.title}")
        }
        LocalPlaylistOperationResult(true, "已删除歌单：${playlist.title}")
    }

    override suspend fun addTrack(
        playlist: LocalPlaylist,
        track: LocalPlaylistTrack,
    ): LocalPlaylistOperationResult = locked {
        val current = readPlaylist(playlist.fileName)
            ?: return@locked LocalPlaylistOperationResult(false, "歌单不存在")
        if (current.tracks.any { it.uri == track.uri }) {
            return@locked LocalPlaylistOperationResult(false, "歌曲已在歌单中", current)
        }
        val updated = current.copy(tracks = current.tracks + track)
        writePlaylist(updated)
        LocalPlaylistOperationResult(true, "已添加到：${current.title}", updated)
    }

    override suspend fun removeTrack(
        playlist: LocalPlaylist,
        uri: String,
    ): LocalPlaylistOperationResult = locked {
        val current = readPlaylist(playlist.fileName)
            ?: return@locked LocalPlaylistOperationResult(false, "歌单不存在")
        val updated = current.copy(tracks = current.tracks.filterNot { it.uri == uri })
        writePlaylist(updated)
        LocalPlaylistOperationResult(true, "已从歌单移除", updated)
    }

    override suspend fun importPlaylist(
        preview: LocalPlaylistImportPreview,
        mode: LocalPlaylistImportMode,
        replacePlaylist: LocalPlaylist?,
    ): LocalPlaylistOperationResult = locked {
        val target = when (mode) {
            LocalPlaylistImportMode.Replace -> {
                val existing = replacePlaylist
                    ?: return@locked LocalPlaylistOperationResult(false, "没有可覆盖的歌单")
                LocalPlaylist(
                    id = existing.id,
                    fileName = existing.fileName,
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

    override suspend fun export(playlist: LocalPlaylist): LocalPlaylistFile = locked {
        val current = readPlaylist(playlist.fileName) ?: playlist
        LocalPlaylistFile(current.fileName, LocalPlaylistFileCodec.encode(current))
    }

    private suspend fun readPlaylist(fileName: String): LocalPlaylist? {
        val raw = storage.readText(fileName) ?: return null
        return runCatching {
            val preview = LocalPlaylistFileCodec.decode(fileName, raw)
            LocalPlaylist(
                id = fileName,
                fileName = fileName,
                title = preview.title,
                description = preview.description,
                tracks = preview.tracks,
            )
        }.getOrNull()
    }

    private suspend fun writePlaylist(playlist: LocalPlaylist) {
        storage.writeTextAtomically(playlist.fileName, LocalPlaylistFileCodec.encode(playlist))
    }

    private suspend fun uniqueFileName(title: String): String {
        val base = LocalPlaylistFileCodec.sanitizeFileName(title).ifBlank { "playlist" }
        var candidate = "$base.$fileExtension"
        var index = 2
        while (storage.exists(candidate)) {
            candidate = "${base}_$index.$fileExtension"
            index += 1
        }
        return candidate
    }

    private suspend inline fun <T> locked(crossinline block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
