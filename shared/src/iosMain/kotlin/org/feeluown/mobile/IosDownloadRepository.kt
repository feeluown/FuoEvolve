package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IosDownloadRepository(
    private val providerRepository: ProviderMusicRepository,
) : DownloadRepository {
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()

    override suspend fun load() = Unit

    override suspend fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        runCatching { providerRepository.resolve(track) }
            .onFailure {
                mutableStates.value = mutableStates.value + (track.id to DownloadState.Failed("iOS 下载暂未接入"))
            }
            .onSuccess {
                mutableStates.value = mutableStates.value + (track.id to DownloadState.Failed("iOS 下载暂未接入"))
            }
    }

    override suspend fun deleteDownloaded(track: MusicTrack) {
        mutableStates.value = mutableStates.value - track.id
    }
}
