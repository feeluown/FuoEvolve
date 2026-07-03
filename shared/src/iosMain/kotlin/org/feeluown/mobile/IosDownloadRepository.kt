package org.feeluown.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask

class IosDownloadRepository(
    private val providerRepository: ProviderMusicRepository,
) : DownloadRepository {
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()

    override suspend fun load() {
        withContext(Dispatchers.Default) {
            publishStates()
        }
    }

    override suspend fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        withContext(Dispatchers.Default) {
            val payload = providerRepository.resolve(track)
            mutableStates.update { it + (track.id to DownloadState.Downloading(0f)) }
            val url = payload.url.toNsUrl() ?: error("无效下载地址")
            val data = NSData.dataWithContentsOfURL(url) ?: error("下载失败")
            mutableStates.update { it + (track.id to DownloadState.Downloading(0.8f)) }
            val fileName = "${payload.title.ifBlank { track.title }.sanitize()} - ${payload.artists.ifBlank { track.artists }.sanitize()}.${payload.url.extension()}"
            val targetPath = "${downloadsDirectory()}/$fileName"
            data.writeToFile(targetPath, atomically = true)
            payload.lyrics?.takeIf { it.isNotBlank() }?.let { lyrics ->
                val lyricsFile = "$targetPath.lrc"
                (lyrics as NSString).writeToFile(lyricsFile, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            }
            val record = IosDownloadRecord(
                trackId = track.id,
                title = payload.title.ifBlank { track.title },
                artists = payload.artists.ifBlank { track.artists },
                album = payload.album.ifBlank { track.album },
                source = payload.source.ifBlank { track.source },
                uri = NSURL.fileURLWithPath(targetPath).absoluteString.orEmpty(),
                coverUrl = payload.coverUrl,
                durationMs = payload.durationMs ?: track.durationMs,
                createdAt = platform.Foundation.NSDate().timeIntervalSince1970.toLong(),
            )
            IosDownloadIndex.upsert(record)
            publishStates()
        }
    }

    override suspend fun deleteDownloaded(track: MusicTrack) {
        withContext(Dispatchers.Default) {
            val key = track.providerId ?: track.id
            val record = IosDownloadIndex.remove(key, track.localUri)
            val uri = record?.uri ?: track.localUri
            uri?.toNsUrl()?.path?.let { path ->
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                NSFileManager.defaultManager.removeItemAtPath("$path.lrc", error = null)
            }
            publishStates()
        }
    }

    private fun publishStates() {
        mutableStates.value = IosDownloadIndex.load().associate {
            it.trackId to DownloadState.Downloaded(it.uri)
        }
    }
}

internal data class IosDownloadRecord(
    val trackId: String,
    val title: String,
    val artists: String,
    val album: String,
    val source: String,
    val uri: String,
    val coverUrl: String?,
    val durationMs: Long?,
    val createdAt: Long,
)

internal object IosDownloadIndex {
    fun load(): List<IosDownloadRecord> {
        val path = indexPath()
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return emptyList()
        val text = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as? String
            ?: return emptyList()
        return text.lineSequence()
            .mapNotNull(::decode)
            .toList()
    }

    fun upsert(record: IosDownloadRecord) {
        val records = load().filterNot { it.trackId == record.trackId } + record
        save(records)
    }

    fun remove(trackId: String, uri: String?): IosDownloadRecord? {
        var removed: IosDownloadRecord? = null
        val records = load().filter {
            val matches = it.trackId == trackId || (uri != null && it.uri == uri)
            if (matches) removed = it
            !matches
        }
        save(records)
        return removed
    }

    private fun save(records: List<IosDownloadRecord>) {
        val text = records.joinToString("\n", transform = ::encode)
        (text as NSString).writeToFile(indexPath(), atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun encode(record: IosDownloadRecord): String {
        return listOf(
            record.trackId,
            record.title,
            record.artists,
            record.album,
            record.source,
            record.uri,
            record.coverUrl.orEmpty(),
            record.durationMs?.toString().orEmpty(),
            record.createdAt.toString(),
        ).joinToString(FIELD_SEPARATOR) { it.replace(FIELD_SEPARATOR, " ") }
    }

    private fun decode(line: String): IosDownloadRecord? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 9) return null
        return IosDownloadRecord(
            trackId = parts[0],
            title = parts[1],
            artists = parts[2],
            album = parts[3],
            source = parts[4],
            uri = parts[5],
            coverUrl = parts[6].takeIf { it.isNotBlank() },
            durationMs = parts[7].toLongOrNull(),
            createdAt = parts[8].toLongOrNull() ?: 0L,
        )
    }

    private fun indexPath(): String = "${downloadsDirectory()}/downloads.index"
}

internal fun downloadsDirectory(): String {
    val root = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
        ?: NSTemporaryDirectory()
    return "$root/FeelUOwn".ensureDirectory()
}

private const val FIELD_SEPARATOR = "\u001f"

private fun String.sanitize(): String {
    return replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "unknown" }
}

private fun String.extension(): String {
    return substringBefore('?').substringAfterLast('.', "mp3").ifBlank { "mp3" }
}
