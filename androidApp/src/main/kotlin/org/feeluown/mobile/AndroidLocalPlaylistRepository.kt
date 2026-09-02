package org.feeluown.mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidLocalPlaylistRepository(
    context: Context,
) : LocalPlaylistRepository by FileBackedLocalPlaylistRepository(
    storage = AndroidLocalPlaylistFileStorage(
        directory = File(context.applicationContext.filesDir, COLLECTIONS_DIRECTORY),
    ),
) {
    private companion object {
        const val COLLECTIONS_DIRECTORY = "collections"
    }
}

/** Android owns filesystem mechanics; playlist semantics live in common code. */
private class AndroidLocalPlaylistFileStorage(
    private val directory: File,
) : LocalPlaylistFileStorage {
    override suspend fun listFileNames(): List<String> = withContext(Dispatchers.IO) {
        ensureDirectory()
        directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .map(File::getName)
    }

    override suspend fun readText(fileName: String): String? = withContext(Dispatchers.IO) {
        val target = fileFor(fileName)
        if (!target.isFile) return@withContext null
        target.readText(Charsets.UTF_8)
    }

    override suspend fun writeTextAtomically(fileName: String, content: String) = withContext(Dispatchers.IO) {
        ensureDirectory()
        val target = fileFor(fileName)
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temp.renameTo(target)) {
                if (target.exists() && !target.delete()) {
                    error("无法替换本地歌单文件：${target.name}")
                }
                check(temp.renameTo(target)) { "无法写入本地歌单文件：${target.name}" }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        Unit
    }

    override suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val target = fileFor(fileName)
        target.exists() && target.delete()
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        fileFor(fileName).exists()
    }

    private fun ensureDirectory() {
        check(directory.exists() || directory.mkdirs()) {
            "无法创建本地歌单目录"
        }
    }

    private fun fileFor(fileName: String): File = File(directory, File(fileName).name)
}
