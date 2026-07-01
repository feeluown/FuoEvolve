package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TrackSourceType {
    Provider,
    LocalMediaStore,
    Downloaded,
}

enum class ProviderLoginMode {
    WebView,
    Cookie,
}

data class AppSettings(
    val homeSection: HomeSection = HomeSection.Recommend,
    val localMusicViewMode: LocalMusicViewMode = LocalMusicViewMode.All,
    val searchScope: SearchScope = SearchScope.All,
    val selectedSearchProviderId: String? = null,
    val selectedSettingsProviderId: String? = null,
    val providerLoginMode: ProviderLoginMode = ProviderLoginMode.WebView,
    val providerCookieInputs: Map<String, String> = emptyMap(),
    val audioCacheLimitMb: Int = DEFAULT_AUDIO_CACHE_LIMIT_MB,
    val imageCacheLimitMb: Int = DEFAULT_IMAGE_CACHE_LIMIT_MB,
)

const val DEFAULT_AUDIO_CACHE_LIMIT_MB = 512
const val DEFAULT_IMAGE_CACHE_LIMIT_MB = 128

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
    val providerName: String? = null,
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

data class ProviderLoginConfig(
    val loginUrl: String,
    val cookieKeyGroups: List<List<String>>,
)

data class ProviderInfo(
    val providerId: String,
    val providerName: String,
    val loginConfig: ProviderLoginConfig? = null,
)

enum class ProviderFeatureCategory {
    Recommend,
    Music,
}

enum class ProviderContentType {
    Songs,
    Playlists,
}

data class ProviderFeature(
    val id: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val category: ProviderFeatureCategory,
    val contentType: ProviderContentType,
    val requiresLogin: Boolean,
)

data class ProviderPlaylist(
    val id: String,
    val title: String,
    val providerId: String,
    val providerName: String,
    val coverUrl: String? = null,
    val description: String = "",
    val playCount: Long? = null,
)

data class ProviderContentSection(
    val feature: ProviderFeature,
    val tracks: List<MusicTrack> = emptyList(),
    val playlists: List<ProviderPlaylist> = emptyList(),
    val isLoginRequired: Boolean = false,
    val errorMessage: String? = null,
)

interface ProviderMusicRepository {
    suspend fun initialize()
    suspend fun providers(): List<ProviderInfo>
    suspend fun search(keyword: String, providerId: String? = null): List<MusicTrack>
    suspend fun resolve(track: MusicTrack): PlaybackPayload
    suspend fun authState(providerId: String): ProviderAuthState
    suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState
    suspend fun features(): List<ProviderFeature>
    suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection
    suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack>
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

interface AppSettingsStore {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
}

object NoOpAppSettingsStore : AppSettingsStore {
    override suspend fun load(): AppSettings = AppSettings()

    override suspend fun save(settings: AppSettings) = Unit
}

data class CacheUsage(
    val audioBytes: Long = 0,
    val imageBytes: Long = 0,
) {
    val totalBytes: Long
        get() = audioBytes + imageBytes
}

data class CacheLimit(
    val audioMaxBytes: Long,
    val imageMaxBytes: Long,
)

interface ResourceCacheRepository {
    val usage: StateFlow<CacheUsage>
    suspend fun refreshUsage()
    suspend fun clearAll()
    suspend fun updateLimit(limit: CacheLimit)
}

object NoOpResourceCacheRepository : ResourceCacheRepository {
    private val mutableUsage = MutableStateFlow(CacheUsage())
    override val usage: StateFlow<CacheUsage> = mutableUsage

    override suspend fun refreshUsage() = Unit

    override suspend fun clearAll() = Unit

    override suspend fun updateLimit(limit: CacheLimit) = Unit
}
