package org.feeluown.mobile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** A provider song reference persisted in a local FeelUOwn collection. */
data class LocalPlaylistTrack(
    val uri: String,
    val providerId: String,
    val identifier: String,
    val title: String = "",
    val artists: String = "",
    val album: String = "",
    val durationMs: Long? = null,
)

data class LocalPlaylist(
    val id: String,
    val fileName: String,
    val title: String,
    val description: String = "",
    val tracks: List<LocalPlaylistTrack> = emptyList(),
)

data class LocalPlaylistFile(
    val fileName: String,
    val content: String,
)

data class LocalPlaylistImportPreview(
    val fileName: String,
    val title: String,
    val description: String = "",
    val tracks: List<LocalPlaylistTrack> = emptyList(),
    val skippedLineCount: Int = 0,
) {
    val hasSkippedLines: Boolean
        get() = skippedLineCount > 0
}

enum class LocalPlaylistImportMode {
    Replace,
    CreateNew,
}

data class LocalPlaylistOperationResult(
    val success: Boolean,
    val message: String,
    val playlist: LocalPlaylist? = null,
)

interface LocalPlaylistRepository {
    suspend fun list(): List<LocalPlaylist>
    suspend fun create(title: String): LocalPlaylistOperationResult
    suspend fun delete(playlist: LocalPlaylist): LocalPlaylistOperationResult
    suspend fun addTrack(
        playlist: LocalPlaylist,
        track: LocalPlaylistTrack,
    ): LocalPlaylistOperationResult
    suspend fun removeTrack(
        playlist: LocalPlaylist,
        uri: String,
    ): LocalPlaylistOperationResult
    suspend fun importPlaylist(
        preview: LocalPlaylistImportPreview,
        mode: LocalPlaylistImportMode,
        replacePlaylist: LocalPlaylist? = null,
    ): LocalPlaylistOperationResult
    suspend fun export(playlist: LocalPlaylist): LocalPlaylistFile
}

object NoOpLocalPlaylistRepository : LocalPlaylistRepository {
    override suspend fun list(): List<LocalPlaylist> = emptyList()

    override suspend fun create(title: String): LocalPlaylistOperationResult =
        LocalPlaylistOperationResult(false, "当前平台不支持本地歌单")

    override suspend fun delete(playlist: LocalPlaylist): LocalPlaylistOperationResult =
        LocalPlaylistOperationResult(false, "当前平台不支持本地歌单")

    override suspend fun addTrack(
        playlist: LocalPlaylist,
        track: LocalPlaylistTrack,
    ): LocalPlaylistOperationResult = LocalPlaylistOperationResult(false, "当前平台不支持本地歌单")

    override suspend fun removeTrack(
        playlist: LocalPlaylist,
        uri: String,
    ): LocalPlaylistOperationResult = LocalPlaylistOperationResult(false, "当前平台不支持本地歌单")

    override suspend fun importPlaylist(
        preview: LocalPlaylistImportPreview,
        mode: LocalPlaylistImportMode,
        replacePlaylist: LocalPlaylist?,
    ): LocalPlaylistOperationResult = LocalPlaylistOperationResult(false, "当前平台不支持本地歌单")

    override suspend fun export(playlist: LocalPlaylist): LocalPlaylistFile =
        LocalPlaylistFile(playlist.fileName, LocalPlaylistFileCodec.encode(playlist))
}

/**
 * FeelUOwn collection files are intentionally small and line-oriented.  The
 * parser implements the stable upstream subset instead of bringing a TOML
 * dependency into the shared mobile module.
 */
object LocalPlaylistFileCodec {
    private const val TOML_DELIMITER = "+++"
    private const val URI_PREFIX = "fuo://"
    private val songUriPattern = Regex(
        "^fuo://([A-Za-z0-9_]+)/songs/([A-Za-z0-9_-]+)$",
    )

    fun decode(fileName: String, raw: String): LocalPlaylistImportPreview {
        val fallbackTitle = fileName
            .substringAfterLast('/')
            .substringBeforeLast('.', missingDelimiterValue = fileName)
            .trim()
            .ifBlank { "未命名歌单" }
        var title: String? = null
        var description = ""
        var skippedLineCount = 0
        val tracks = mutableListOf<LocalPlaylistTrack>()

        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var bodyStartIndex = 0
        if (lines.firstOrNull()?.trim() == TOML_DELIMITER) {
            val closingIndex = lines.drop(1).indexOfFirst { it.trim() == TOML_DELIMITER }
                .takeIf { it >= 0 }
                ?.plus(1)
            if (closingIndex != null) {
                lines.subList(1, closingIndex)
                    .map(String::trim)
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val separator = line.indexOf('=')
                        if (separator <= 0) return@forEach
                        val key = line.substring(0, separator).trim()
                        val value = decodeTomlString(line.substring(separator + 1))
                        when (key) {
                            "title" -> title = value
                            "description" -> description = value
                        }
                    }
                bodyStartIndex = closingIndex + 1
            } else {
                // Match upstream behavior for an unterminated metadata block:
                // ignore the opening delimiter and continue parsing remaining
                // lines as collection body entries.
                bodyStartIndex = 1
            }
        }

        lines.drop(bodyStartIndex).forEach { originalLine ->
            val line = originalLine.trim()
            if (line.isBlank() || line.startsWith('#')) return@forEach

            // Upstream accepts both the tab emitted by reverse(...,
            // as_line=true) and ordinary whitespace before the comment.
            val commentIndex = line.indexOf('#')
            val uri = (if (commentIndex >= 0) line.substring(0, commentIndex) else line).trim()
            val match = songUriPattern.matchEntire(uri)
            if (match == null) {
                skippedLineCount += 1
                return@forEach
            }
            val providerId = match.groupValues[1]
            val identifier = match.groupValues[2]
            val fields = if (commentIndex >= 0) {
                parseSongDescription(line.substring(commentIndex).trim())
            } else {
                emptyList()
            }
            tracks += LocalPlaylistTrack(
                uri = normalizeSongUri(providerId, identifier),
                providerId = providerId,
                identifier = identifier,
                title = fields.getOrNull(0).orEmpty(),
                artists = fields.getOrNull(1).orEmpty(),
                album = fields.getOrNull(2).orEmpty(),
                durationMs = fields.getOrNull(3)?.let(::parseDurationMs),
            )
        }

        return LocalPlaylistImportPreview(
            fileName = fileName,
            title = title?.trim().orEmpty().ifBlank { fallbackTitle },
            description = description,
            tracks = tracks.distinctBy { it.uri },
            skippedLineCount = skippedLineCount,
        )
    }

    fun encode(playlist: LocalPlaylist): String = buildString {
        appendLine(TOML_DELIMITER)
        appendLine("title = ${encodeTomlString(playlist.title)}")
        if (playlist.description.isNotBlank()) {
            appendLine("description = ${encodeTomlString(playlist.description)}")
        }
        appendLine(TOML_DELIMITER)
        playlist.tracks.distinctBy { normalizeSongUri(it.providerId, it.identifier) }.forEach { track ->
            append(normalizeSongUri(track.providerId, track.identifier))
            val rawFields = listOf(track.title, track.artists, track.album, formatDuration(track.durationMs))
                .map(String::trim)
                .dropLastWhile(String::isEmpty)
            if (rawFields.isNotEmpty()) {
                append("\t# ")
                append(rawFields.joinToString(" - ", transform = ::encodeSongField))
            }
            appendLine()
        }
    }

    fun normalizeSongUri(providerId: String, identifier: String): String {
        val provider = providerId.trim()
        val id = identifier.trim()
        return "$URI_PREFIX$provider/songs/$id"
    }

    private fun parseSongDescription(value: String): List<String> {
        val comment = value.removePrefix("#").trim().let {
            if (it.endsWith(" -")) it.dropLast(2) else it
        }
        if (comment.isBlank()) return emptyList()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var escaped = false
        var index = 0
        while (index < comment.length) {
            val character = comment[index]
            if (escaped) {
                field.append(character)
                escaped = false
                index += 1
                continue
            }
            if (character == '\\' && quoted) {
                field.append(character)
                escaped = true
                index += 1
                continue
            }
            if (character == '"') {
                quoted = !quoted
                field.append(character)
                index += 1
                continue
            }
            if (!quoted && comment.startsWith(" - ", index)) {
                fields += decodeSongField(field.toString().trim())
                field.clear()
                index += 3
                continue
            }
            field.append(character)
            index += 1
        }
        fields += decodeSongField(field.toString().trim())
        return fields
    }

    private fun encodeSongField(value: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) return "\"\""
        return if (
            normalized.contains(" - ") ||
                normalized.contains('"') ||
                normalized.startsWith('#') ||
                normalized.startsWith('"') ||
                normalized.endsWith('"')
        ) {
            encodeJsonString(normalized)
        } else {
            normalized
        }
    }

    private fun decodeSongField(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return runCatching { Json.decodeFromString<String>(value) }.getOrElse {
                value.substring(1, value.length - 1)
            }
        }
        return value
    }

    private fun decodeTomlString(value: String): String {
        val trimmed = value.trim().removeTomlInlineComment()
        return when {
            trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"' -> {
                runCatching { Json.decodeFromString<String>(trimmed) }.getOrElse {
                    trimmed.substring(1, trimmed.length - 1)
                }
            }
            trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'' -> {
                trimmed.substring(1, trimmed.length - 1)
            }
            else -> trimmed
        }
    }

    private fun String.removeTomlInlineComment(): String {
        var inBasicString = false
        var inLiteralString = false
        var escaped = false
        forEachIndexed { index, character ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            when (character) {
                '\\' -> if (inBasicString) escaped = true
                '"' -> if (!inLiteralString) inBasicString = !inBasicString
                '\'' -> if (!inBasicString) inLiteralString = !inLiteralString
                '#' -> if (!inBasicString && !inLiteralString) {
                    return substring(0, index).trim()
                }
            }
        }
        return trim()
    }

    private fun encodeTomlString(value: String): String = encodeJsonString(value)

    private fun encodeJsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
    }

    private fun parseDurationMs(value: String): Long? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        if (normalized.contains(':')) {
            val parts = normalized.split(':')
            if (parts.size !in 2..3) return null
            val seconds = parts.last().toDoubleOrNull() ?: return null
            val minutes = parts[parts.lastIndex - 1].toLongOrNull() ?: return null
            val hours = if (parts.size == 3) parts.first().toLongOrNull() ?: return null else 0L
            return ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong())
                .takeIf { it > 0 }
        }
        val numeric = normalized.toDoubleOrNull() ?: return null
        if (numeric <= 0) return null
        return if (numeric < 10_000) (numeric * 1000).toLong() else numeric.toLong()
    }

    private fun formatDuration(durationMs: Long?): String {
        val duration = durationMs?.takeIf { it > 0 } ?: return ""
        val totalSeconds = (duration / 1000).toInt()
        val seconds = totalSeconds % 60
        // FeelUOwn's duration_ms_display uses total minutes, so a one-hour
        // song is written as 60:00 rather than a separate hour component.
        val minutes = totalSeconds / 60
        return "${secondsText(minutes)}:${secondsText(seconds)}"
    }

    fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), "_")
        .trim('_', '.')

    private fun secondsText(value: Int): String = value.toString().padStart(2, '0')
}

/** Small in-memory implementation used by tests and non-file hosts. */
class InMemoryLocalPlaylistRepository(
    initial: List<LocalPlaylist> = emptyList(),
) : LocalPlaylistRepository {
    private val mutex = Mutex()
    private var playlists = initial

    override suspend fun list(): List<LocalPlaylist> = mutex.withLock { playlists }

    override suspend fun create(title: String): LocalPlaylistOperationResult = mutex.withLock {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) return@withLock LocalPlaylistOperationResult(false, "歌单名称不能为空")
        val fileName = uniqueFileName(normalizedTitle)
        val playlist = LocalPlaylist(fileName, fileName, normalizedTitle)
        playlists += playlist
        LocalPlaylistOperationResult(true, "已新建歌单：$normalizedTitle", playlist)
    }

    override suspend fun delete(playlist: LocalPlaylist): LocalPlaylistOperationResult = mutex.withLock {
        playlists = playlists.filterNot { it.id == playlist.id }
        LocalPlaylistOperationResult(true, "已删除歌单：${playlist.title}")
    }

    override suspend fun addTrack(
        playlist: LocalPlaylist,
        track: LocalPlaylistTrack,
    ): LocalPlaylistOperationResult = mutex.withLock {
        val current = playlists.firstOrNull { it.id == playlist.id }
            ?: return@withLock LocalPlaylistOperationResult(false, "歌单不存在")
        if (current.tracks.any { it.uri == track.uri }) {
            return@withLock LocalPlaylistOperationResult(false, "歌曲已在歌单中", current)
        }
        val updated = current.copy(tracks = current.tracks + track)
        playlists = playlists.map { if (it.id == current.id) updated else it }
        LocalPlaylistOperationResult(true, "已添加到：${current.title}", updated)
    }

    override suspend fun removeTrack(
        playlist: LocalPlaylist,
        uri: String,
    ): LocalPlaylistOperationResult = mutex.withLock {
        val current = playlists.firstOrNull { it.id == playlist.id }
            ?: return@withLock LocalPlaylistOperationResult(false, "歌单不存在")
        val updated = current.copy(tracks = current.tracks.filterNot { it.uri == uri })
        playlists = playlists.map { if (it.id == current.id) updated else it }
        LocalPlaylistOperationResult(true, "已从歌单移除", updated)
    }

    override suspend fun importPlaylist(
        preview: LocalPlaylistImportPreview,
        mode: LocalPlaylistImportMode,
        replacePlaylist: LocalPlaylist?,
    ): LocalPlaylistOperationResult = mutex.withLock {
        val target = when (mode) {
            LocalPlaylistImportMode.Replace -> {
                val existing = replacePlaylist
                    ?: return@withLock LocalPlaylistOperationResult(false, "没有可覆盖的歌单")
                existing.copy(
                    title = preview.title,
                    description = preview.description,
                    tracks = preview.tracks,
                )
            }
            LocalPlaylistImportMode.CreateNew -> {
                val fileName = uniqueFileName(preview.title)
                LocalPlaylist(fileName, fileName, preview.title, preview.description, preview.tracks)
            }
        }
        playlists = if (mode == LocalPlaylistImportMode.Replace) {
            playlists.map { if (it.id == target.id) target else it }
        } else {
            playlists + target
        }
        LocalPlaylistOperationResult(true, "已导入：${target.title}", target)
    }

    override suspend fun export(playlist: LocalPlaylist): LocalPlaylistFile =
        LocalPlaylistFile(playlist.fileName, LocalPlaylistFileCodec.encode(playlist))

    private fun uniqueFileName(title: String): String {
        val base = LocalPlaylistFileCodec.sanitizeFileName(title).ifBlank { "playlist" }
        var candidate = "$base.fuo"
        var index = 2
        while (playlists.any { it.fileName == candidate }) {
            candidate = "${base}_$index.fuo"
            index += 1
        }
        return candidate
    }

}
