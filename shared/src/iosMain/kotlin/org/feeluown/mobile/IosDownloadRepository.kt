package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSUserDefaults
import kotlin.coroutines.resume

class IosDownloadRepository(
    private val providerRepository: ProviderMusicRepository,
    private val output: IosDownloadOutput,
) : DownloadRepository {
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val defaults = NSUserDefaults.standardUserDefaults

    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()

    override suspend fun load() {
        mutableStates.value = readRecords().mapValues { DownloadState.Downloaded(it.value) }
    }

    override suspend fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        download(track, providerRepository.resolve(track))
    }

    override suspend fun download(track: MusicTrack, payload: PlaybackPayload) {
        if (track.sourceType != TrackSourceType.Provider) return
        mutableStates.value = mutableStates.value + (track.id to DownloadState.Queued)
        mutableStates.value = mutableStates.value + (track.id to DownloadState.Downloading(0f))
        val result = suspendCancellableCoroutine<Result<String>> { continuation ->
            output.download(
                url = payload.url,
                headers = payload.headers,
                fileName = downloadFileName(track, payload),
                lyrics = payload.lyrics,
            ) { uri, error ->
                if (!continuation.isActive) return@download
                if (uri != null) continuation.resume(Result.success(uri))
                else continuation.resume(Result.failure(IllegalStateException(error ?: "下载失败")))
            }
        }
        result.onSuccess { uri ->
            val records = readRecords() + (track.id to uri)
            writeRecords(records)
            mutableStates.value = mutableStates.value + (track.id to DownloadState.Downloaded(uri))
        }.onFailure { throwable ->
            mutableStates.value = mutableStates.value +
                (track.id to DownloadState.Failed(throwable.message ?: "下载失败"))
            throw throwable
        }
    }

    override suspend fun deleteDownloaded(track: MusicTrack) {
        val key = track.providerId ?: track.id
        val records = readRecords().toMutableMap()
        val uri = records.remove(key) ?: track.localUri
        if (uri != null) output.delete(uri)
        writeRecords(records)
        mutableStates.value = mutableStates.value - key
    }

    private fun downloadFileName(track: MusicTrack, payload: PlaybackPayload): String {
        val extension = payload.url.substringBefore('?').substringAfterLast('.', "mp3")
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "mp3"
        val raw = "${payload.title.ifBlank { track.title }} - ${payload.artists.ifBlank { track.artists }}"
        val safe = raw.map { character ->
            if (character in "\\/:*?\"<>|") '_' else character
        }.joinToString("").take(120).ifBlank { "FeelUOwn" }
        return "$safe.$extension"
    }

    private fun readRecords(): Map<String, String> {
        return defaults.stringForKey(KEY_DOWNLOAD_RECORDS)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val values = line.split(RECORD_SEPARATOR, limit = 2)
                if (values.size == 2) values[0] to values[1] else null
            }
            ?.toMap()
            .orEmpty()
    }

    private fun writeRecords(records: Map<String, String>) {
        defaults.setObject(
            records.entries.joinToString("\n") { "${it.key}$RECORD_SEPARATOR${it.value}" },
            KEY_DOWNLOAD_RECORDS,
        )
        defaults.synchronize()
    }

    private companion object {
        private const val KEY_DOWNLOAD_RECORDS = "ios_download_records"
        private const val RECORD_SEPARATOR = "\u001f"
    }
}
