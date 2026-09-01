package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit placeholders for desktop capabilities that are intentionally outside the foundation PR.
 * Keeping them at the platform composition edge lets feature/common code stay identical on desktop.
 */
internal object DesktopUnsupportedLocalMusicRepository : LocalMusicRepository {
    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) = Unit

    override suspend fun directories(): List<LocalMusicDirectory> = emptyList()

    override suspend fun tracks(): List<MusicTrack> = emptyList()

    override suspend fun refreshDatabase(): List<MusicTrack> = emptyList()

    override suspend fun search(keyword: String): List<MusicTrack> = emptyList()
}

internal object DesktopUnsupportedDownloadRepository : DownloadRepository {
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()

    override suspend fun load() = Unit

    override suspend fun download(track: MusicTrack) = Unit

    override suspend fun deleteDownloaded(track: MusicTrack) = Unit
}

internal class DesktopUnsupportedPlaybackEngine : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    override fun prepareLoading(track: MusicTrack) {
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = track,
            positionMs = 0L,
            durationMs = track.durationMs ?: 0L,
            errorMessage = null,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Error,
            currentTrack = track,
            positionMs = 0L,
            durationMs = payload.durationMs ?: track.durationMs ?: 0L,
            errorMessage = "桌面播放引擎将在后续开发阶段接入",
        )
    }

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() {
        mutableState.value = PlaybackState()
    }

    override fun seekTo(positionMs: Long) {
        mutableState.value = mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }
}
