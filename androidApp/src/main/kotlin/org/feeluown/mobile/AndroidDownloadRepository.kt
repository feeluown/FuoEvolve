package org.feeluown.mobile

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class AndroidDownloadRepository(
    private val context: Context,
    private val providerRepository: ProviderMusicRepository,
) : DownloadRepository {
    private val records = linkedMapOf<String, DownloadRecord>()
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()

    override suspend fun load() {
        withContext(Dispatchers.IO) {
            records.clear()
            val file = indexFile()
            if (file.exists()) {
                val array = JSONArray(file.readText())
                for (index in 0 until array.length()) {
                    val record = array.getJSONObject(index).toRecord()
                    records[record.trackId] = record
                }
            }
            publishStates()
        }
    }

    override suspend fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        withContext(Dispatchers.IO) {
            val payload = providerRepository.resolve(track)
            mutableStates.update { it + (track.id to DownloadState.Downloading(0f)) }
            val extension = extension(payload.url)
            val tempFile = File.createTempFile("fuo-download-", ".$extension", context.cacheDir)
            var target: DownloadTarget? = null
            try {
                writePayload(track.id, payload, tempFile)
                writeId3TagIfNeeded(tempFile, extension, track, payload)
                target = createTarget(track, payload, extension)
                val bytes = copyToTarget(track.id, tempFile, target.uri)
                val finalUri = finishTarget(target, success = true)
                val record = DownloadRecord(
                    trackId = track.id,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    source = track.source,
                    uri = finalUri.toString(),
                    coverUrl = payload.coverUrl,
                    durationMs = payload.durationMs ?: track.durationMs,
                    fileSize = bytes,
                    createdAt = System.currentTimeMillis(),
                )
                records[track.id] = record
                saveRecords()
                publishStates()
            } catch (throwable: Throwable) {
                target?.let { finishTarget(it, success = false) }
                mutableStates.update { it + (track.id to DownloadState.Failed(throwable.message ?: "下载失败")) }
                throw throwable
            } finally {
                tempFile.delete()
            }
        }
    }

    override suspend fun deleteDownloaded(track: MusicTrack) {
        withContext(Dispatchers.IO) {
            val key = track.providerId ?: track.id
            val record = records.remove(key)
                ?: track.localUri?.let { uri ->
                    val matched = records.entries.firstOrNull { it.value.uri == uri }
                    if (matched != null) records.remove(matched.key) else null
                }
            val uri = record?.uri ?: track.localUri
            if ((record != null || track.sourceType == TrackSourceType.Downloaded) && uri != null) {
                runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
                if (uri.startsWith("file:")) {
                    runCatching { File(requireNotNull(Uri.parse(uri).path)).delete() }
                }
            }
            saveRecords()
            publishStates()
        }
    }

    private fun writePayload(trackId: String, payload: PlaybackPayload, targetFile: File): Long {
        val connection = URL(payload.url).openConnection()
        payload.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        val total = connection.contentLengthLong.takeIf { it > 0 }
        var written = 0L
        connection.getInputStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (total != null) {
                        val progress = (written.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        mutableStates.update { current ->
                            current + (trackId to DownloadState.Downloading(progress))
                        }
                    }
                }
            }
        }
        return written
    }

    private fun copyToTarget(trackId: String, sourceFile: File, targetUri: Uri): Long {
        var written = 0L
        sourceFile.inputStream().use { input ->
            val outputStream = if (targetUri.scheme == "file") {
                FileOutputStream(File(requireNotNull(targetUri.path)))
            } else {
                context.contentResolver.openOutputStream(targetUri, "w")
            }
            outputStream?.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
            } ?: error("无法写入下载文件")
        }
        mutableStates.update { it + (trackId to DownloadState.Downloading(1f)) }
        return written
    }

    private fun createTarget(track: MusicTrack, payload: PlaybackPayload, extension: String): DownloadTarget {
        val mimeType = mimeType(extension)
        val title = payload.title.ifBlank { track.title }
        val artists = payload.artists.ifBlank { track.artists }
        val album = payload.album.ifBlank { track.album }
        val fileName = "${sanitize(title)} - ${sanitize(artists)}.$extension"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artists)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/FeelUOwn")
            }
            val uri = requireNotNull(
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ) { "无法创建系统音乐库文件" }
            DownloadTarget(uri, fileName, mimeType)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "FeelUOwn",
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            DownloadTarget(Uri.fromFile(file), fileName, mimeType)
        }
    }

    private fun finishTarget(target: DownloadTarget, success: Boolean): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (success) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(target.uri, values, null, null)
            } else {
                context.contentResolver.delete(target.uri, null, null)
            }
            return target.uri
        }
        val file = File(requireNotNull(target.uri.path))
        if (success) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(target.mimeType),
                null,
            )
        } else {
            file.delete()
        }
        return target.uri
    }

    private fun saveRecords() {
        val array = JSONArray()
        records.values.forEach { array.put(it.toJson()) }
        indexFile().writeText(array.toString())
    }

    private fun publishStates() {
        mutableStates.value = records.mapValues { DownloadState.Downloaded(it.value.uri) }
    }

    private fun indexFile(): File = File(context.filesDir, "downloads.json")

    private fun JSONObject.toRecord(): DownloadRecord = DownloadRecord(
        trackId = getString("trackId"),
        title = optString("title"),
        artists = optString("artists"),
        album = optString("album"),
        source = optString("source"),
        uri = optString("uri"),
        coverUrl = optString("coverUrl").takeIf { it.isNotBlank() },
        durationMs = optLong("durationMs").takeIf { it > 0 },
        fileSize = optLong("fileSize"),
        createdAt = optLong("createdAt"),
    )

    private fun DownloadRecord.toJson(): JSONObject = JSONObject()
        .put("trackId", trackId)
        .put("title", title)
        .put("artists", artists)
        .put("album", album)
        .put("source", source)
        .put("uri", uri)
        .put("coverUrl", coverUrl ?: "")
        .put("durationMs", durationMs ?: 0)
        .put("fileSize", fileSize)
        .put("createdAt", createdAt)

    private fun sanitize(value: String): String {
        val sanitized = value.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        return sanitized.ifBlank { "unknown" }.take(80)
    }

    private fun extension(url: String): String {
        val clean = url.substringBefore('?').substringAfterLast('/', "")
        val ext = clean.substringAfterLast('.', "mp3").lowercase()
        return ext.takeIf { it.length in 2..5 } ?: "mp3"
    }

    private fun mimeType(extension: String): String {
        return when (extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
    }

    private fun writeId3TagIfNeeded(
        file: File,
        extension: String,
        track: MusicTrack,
        payload: PlaybackPayload,
    ) {
        if (extension.lowercase() != "mp3") return
        val title = payload.title.ifBlank { track.title }
        val artists = payload.artists.ifBlank { track.artists }
        val album = payload.album.ifBlank { track.album }
        if (title.isBlank() && artists.isBlank() && album.isBlank()) return

        val audioBytes = file.readBytes().stripId3v2Tag()
        val tagBytes = buildId3v23Tag(
            title = title,
            artists = artists,
            album = album,
        )
        FileOutputStream(file, false).use { output ->
            output.write(tagBytes)
            output.write(audioBytes)
        }
    }

    private fun ByteArray.stripId3v2Tag(): ByteArray {
        if (size < ID3_HEADER_SIZE || this[0] != 'I'.code.toByte() || this[1] != 'D'.code.toByte() || this[2] != '3'.code.toByte()) {
            return this
        }
        val tagSize = readSynchsafeInt(6)
        val footerSize = if (size > 5 && (this[5].toInt() and 0x10) != 0) ID3_HEADER_SIZE else 0
        val start = (ID3_HEADER_SIZE + tagSize + footerSize).coerceAtMost(size)
        return copyOfRange(start, size)
    }

    private fun ByteArray.readSynchsafeInt(offset: Int): Int {
        return ((this[offset].toInt() and 0x7F) shl 21) or
            ((this[offset + 1].toInt() and 0x7F) shl 14) or
            ((this[offset + 2].toInt() and 0x7F) shl 7) or
            (this[offset + 3].toInt() and 0x7F)
    }

    private fun buildId3v23Tag(title: String, artists: String, album: String): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.writeTextFrame("TIT2", title)
        frames.writeTextFrame("TPE1", artists)
        frames.writeTextFrame("TALB", album)
        val frameBytes = frames.toByteArray()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
            writeSynchsafeInt(frameBytes.size)
            write(frameBytes)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeTextFrame(id: String, value: String) {
        if (value.isBlank()) return
        val body = ByteArrayOutputStream().apply {
            write(1)
            write(value.toByteArray(Charsets.UTF_16))
        }.toByteArray()
        write(id.encodeToByteArray())
        writeInt32(body.size)
        write(byteArrayOf(0, 0))
        write(body)
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeSynchsafeInt(value: Int) {
        write((value ushr 21) and 0x7F)
        write((value ushr 14) and 0x7F)
        write((value ushr 7) and 0x7F)
        write(value and 0x7F)
    }

    private data class DownloadRecord(
        val trackId: String,
        val title: String,
        val artists: String,
        val album: String,
        val source: String,
        val uri: String,
        val coverUrl: String?,
        val durationMs: Long?,
        val fileSize: Long,
        val createdAt: Long,
    )

    private data class DownloadTarget(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
    )

    private companion object {
        private const val ID3_HEADER_SIZE = 10
    }
}
