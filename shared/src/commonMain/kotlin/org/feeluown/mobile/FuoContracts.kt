package org.feeluown.mobile

import kotlinx.coroutines.flow.StateFlow

enum class TrackSourceType {
    Provider,
    LocalMediaStore,
    Downloaded,
}

data class MusicTrack(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val source: String,
    val sourceType: TrackSourceType,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val localUri: String? = null,
    val providerId: String? = null,
)

data class PlaybackPayload(
    val url: String,
    val title: String,
    val artists: String,
    val album: String,
    val source: String,
    val headers: Map<String, String> = emptyMap(),
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val lyrics: String? = null,
)

enum class PlayerStatus {
    Idle,
    Loading,
    Playing,
    Paused,
    Error,
    Ended,
}

enum class PlayMode {
    ListLoop,
    SingleLoop,
}

data class PlaybackState(
    val status: PlayerStatus = PlayerStatus.Idle,
    val currentTrack: MusicTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val queue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val playMode: PlayMode = PlayMode.ListLoop,
    val lyrics: String? = null,
    val errorMessage: String? = null,
)

sealed class DownloadState {
    data object NotDownloaded : DownloadState()
    data object Queued : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Downloaded(val uri: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

data class ProviderAuthState(
    val providerId: String,
    val providerName: String,
    val isLoggedIn: Boolean,
    val userName: String? = null,
)

interface ProviderMusicRepository {
    suspend fun initialize()
    suspend fun search(keyword: String): List<MusicTrack>
    suspend fun resolve(track: MusicTrack): PlaybackPayload
    suspend fun authState(providerId: String): ProviderAuthState
    suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState
}

interface LocalMusicRepository {
    suspend fun scan(): List<MusicTrack>
    suspend fun search(keyword: String): List<MusicTrack>
}

interface DownloadRepository {
    val states: StateFlow<Map<String, DownloadState>>
    suspend fun load()
    suspend fun download(track: MusicTrack)
    suspend fun deleteDownloaded(track: MusicTrack)
}

interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    fun play(track: MusicTrack, payload: PlaybackPayload)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
}
