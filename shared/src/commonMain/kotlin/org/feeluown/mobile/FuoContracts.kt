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
    Headers,
}

enum class AudioQualityPolicy(
    val label: String,
    val policy: String,
) {
    Highest("最高", ">>>"),
    High("高", "hq<>"),
    Standard("标准", "sq<>"),
    Low("低流量", "lq<>"),
}

enum class UnavailablePlaybackPolicy(
    val label: String,
) {
    SmartReplace("智能替换"),
    Skip("跳过"),
}

data class AppSettings(
    val homeSection: HomeSection = HomeSection.Recommend,
    val mineSection: MineSection = MineSection.Playlists,
    val localMusicViewMode: LocalMusicViewMode = LocalMusicViewMode.All,
    val excludedLocalMusicDirectoryIds: Set<String> = emptySet(),
    val localMusicMinDurationSeconds: Int = DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
    val searchScope: SearchScope = SearchScope.All,
    val selectedSearchProviderId: String? = null,
    val selectedSettingsProviderId: String? = null,
    val providerLoginMode: ProviderLoginMode = ProviderLoginMode.WebView,
    val providerCookieInputs: Map<String, String> = emptyMap(),
    val providerHeaderInputs: Map<String, ProviderHeaderInput> = emptyMap(),
    val enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS,
    val providerOrderIds: List<String> = DEFAULT_PROVIDER_ORDER_IDS,
    val audioCacheLimitMb: Int = DEFAULT_AUDIO_CACHE_LIMIT_MB,
    val imageCacheLimitMb: Int = DEFAULT_IMAGE_CACHE_LIMIT_MB,
    val wifiAudioQualityPolicy: AudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY,
    val cellularAudioQualityPolicy: AudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY,
    val unavailablePlaybackPolicy: UnavailablePlaybackPolicy = DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
)

data class ProviderHeaderInput(
    val authorization: String = "",
    val cookie: String = "",
)

const val DEFAULT_AUDIO_CACHE_LIMIT_MB = 512
const val DEFAULT_IMAGE_CACHE_LIMIT_MB = 128
const val DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS = 0
val DEFAULT_ENABLED_PROVIDER_IDS = setOf("netease")
val DEFAULT_PROVIDER_ORDER_IDS = listOf("netease", "qqmusic", "bilibili", "ytmusic")
val DEFAULT_WIFI_AUDIO_QUALITY_POLICY = AudioQualityPolicy.High
val DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY = AudioQualityPolicy.Standard
val DEFAULT_UNAVAILABLE_PLAYBACK_POLICY = UnavailablePlaybackPolicy.SmartReplace

data class LocalMusicScanSettings(
    val excludedDirectoryIds: Set<String> = emptySet(),
    val minDurationSeconds: Int = DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
)

data class LocalMusicDirectory(
    val id: String,
    val name: String,
    val trackCount: Int,
)

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
    val lyrics: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val isSmartReplacement: Boolean = false,
    val originalTitle: String? = null,
    val originalProviderName: String? = null,
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
    val audioQuality: String? = null,
    val providerName: String? = null,
    val isSmartReplacement: Boolean = false,
    val originalTitle: String? = null,
    val originalProviderName: String? = null,
    val replacementStrategy: String? = null,
    val replacementScore: Double? = null,
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
    val audioQuality: String? = null,
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
    val supportedLoginModes: Set<ProviderLoginMode> = setOf(ProviderLoginMode.WebView, ProviderLoginMode.Cookie),
)

enum class ProviderFeatureCategory {
    Recommend,
    Music,
    MinePlaylists,
    MineFavoritePlaylists,
    Mine,
}

enum class ProviderContentType {
    Songs,
    Playlists,
    Artists,
    Albums,
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

enum class ProviderMediaItemType {
    Artist,
    Album,
}

data class ProviderMediaItem(
    val id: String,
    val title: String,
    val providerId: String,
    val providerName: String,
    val type: ProviderMediaItemType,
    val coverUrl: String? = null,
    val description: String = "",
)

data class ProviderContentSection(
    val feature: ProviderFeature,
    val tracks: List<MusicTrack> = emptyList(),
    val playlists: List<ProviderPlaylist> = emptyList(),
    val mediaItems: List<ProviderMediaItem> = emptyList(),
    val isLoginRequired: Boolean = false,
    val errorMessage: String? = null,
)

interface ProviderMusicRepository {
    suspend fun initialize()
    suspend fun availableProviders(): List<ProviderInfo> = providers()
    suspend fun updateEnabledProviders(providerIds: Set<String>) = Unit
    suspend fun providers(): List<ProviderInfo>
    suspend fun search(keyword: String, providerId: String? = null): List<MusicTrack>
    suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy = DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
    ): PlaybackPayload
    suspend fun authState(providerId: String): ProviderAuthState
    suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState
    suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support header login: $providerId")
    }
    suspend fun logout(providerId: String): ProviderAuthState
    suspend fun updateAudioQualityPolicies(wifiPolicy: AudioQualityPolicy, cellularPolicy: AudioQualityPolicy)
    suspend fun features(): List<ProviderFeature>
    suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection
    suspend fun loadMoreFeatureTracks(feature: ProviderFeature): List<MusicTrack> = loadFeature(feature).tracks
    suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack>
    suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack>
}

interface LocalMusicRepository {
    suspend fun updateScanSettings(settings: LocalMusicScanSettings)
    suspend fun directories(): List<LocalMusicDirectory>
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

interface DebugLogRepository {
    val isAvailable: Boolean
    suspend fun logLines(): List<String>
}

object NoOpDebugLogRepository : DebugLogRepository {
    override val isAvailable: Boolean = false

    override suspend fun logLines(): List<String> = emptyList()
}
