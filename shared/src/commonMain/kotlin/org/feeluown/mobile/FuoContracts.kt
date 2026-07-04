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
    val playlistFilter: PlaylistFilter = PlaylistFilter.All,
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
    val smartReplacementProviderIds: Set<String> = emptySet(),
    val smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    val smartReplacementUseOriginalMetadata: Boolean = false,
    val smartReplacementUseOriginalLyrics: Boolean = false,
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
const val DEFAULT_SMART_REPLACEMENT_MIN_SCORE = 0.55

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
    val originalCoverUrl: String? = null,
    val replacementTitle: String? = null,
    val replacementArtists: String? = null,
    val replacementSource: String? = null,
    val replacementProviderName: String? = null,
    val replacementCoverUrl: String? = null,
    val replacementStrategy: String? = null,
    val replacementScore: Double? = null,
    val isUnavailable: Boolean = false,
    val artistItemId: String? = null,
    val albumItemId: String? = null,
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
    val originalCoverUrl: String? = null,
    val replacementTitle: String? = null,
    val replacementArtists: String? = null,
    val replacementSource: String? = null,
    val replacementProviderName: String? = null,
    val replacementCoverUrl: String? = null,
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

data class PlaybackQueueSnapshot(
    val mainQueue: List<MusicTrack> = emptyList(),
    val originalMainQueue: List<MusicTrack> = emptyList(),
    val upNextQueue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatEnabled: Boolean = true,
    val isFmQueue: Boolean = false,
    val shuffleBeforeFm: Boolean? = null,
)

interface PlaybackQueueStore {
    suspend fun load(): PlaybackQueueSnapshot
    suspend fun save(snapshot: PlaybackQueueSnapshot)
}

object NoOpPlaybackQueueStore : PlaybackQueueStore {
    override suspend fun load(): PlaybackQueueSnapshot = PlaybackQueueSnapshot()

    override suspend fun save(snapshot: PlaybackQueueSnapshot) = Unit
}

object PlaybackQueueCodec {
    fun encode(snapshot: PlaybackQueueSnapshot): String {
        return buildList {
            add("v1")
            add(
                listOf(
                    snapshot.queueIndex.toString(),
                    snapshot.shuffleEnabled.toString(),
                    snapshot.repeatEnabled.toString(),
                    snapshot.isFmQueue.toString(),
                    snapshot.shuffleBeforeFm?.toString().orEmpty(),
                ).joinToString("\t")
            )
            addTracks("main", snapshot.mainQueue)
            addTracks("original", snapshot.originalMainQueue)
            addTracks("upNext", snapshot.upNextQueue)
        }.joinToString("\n")
    }

    fun decode(raw: String): PlaybackQueueSnapshot {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2 || lines.first() != "v1") return PlaybackQueueSnapshot()
        val flags = lines[1].split("\t")
        val tracksBySection = lines.drop(2)
            .mapNotNull { line ->
                val fields = line.split("\t")
                if (fields.size < 2 || fields[0] != "track") return@mapNotNull null
                decodeTrack(fields.drop(2))?.let { track -> fields[1] to track }
            }
            .groupBy({ it.first }, { it.second })
        return PlaybackQueueSnapshot(
            mainQueue = tracksBySection["main"].orEmpty(),
            originalMainQueue = tracksBySection["original"].orEmpty(),
            upNextQueue = tracksBySection["upNext"].orEmpty(),
            queueIndex = flags.getOrNull(0)?.toIntOrNull() ?: -1,
            shuffleEnabled = flags.getOrNull(1)?.toBooleanStrictOrNull() ?: false,
            repeatEnabled = flags.getOrNull(2)?.toBooleanStrictOrNull() ?: true,
            isFmQueue = flags.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
            shuffleBeforeFm = flags.getOrNull(4)?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull(),
        )
    }

    private fun MutableList<String>.addTracks(section: String, tracks: List<MusicTrack>) {
        tracks.forEach { track ->
            add((listOf("track", section) + encodeTrack(track)).joinToString("\t"))
        }
    }

    private fun encodeTrack(track: MusicTrack): List<String> = listOf(
        track.id,
        track.title,
        track.artists,
        track.album,
        track.source,
        track.sourceType.name,
        track.coverUrl.orEmpty(),
        track.durationMs?.toString().orEmpty(),
        track.localUri.orEmpty(),
        track.lyrics.orEmpty(),
        track.providerId.orEmpty(),
        track.providerName.orEmpty(),
        track.isSmartReplacement.toString(),
        track.originalTitle.orEmpty(),
        track.originalProviderName.orEmpty(),
        track.isUnavailable.toString(),
        track.artistItemId.orEmpty(),
        track.albumItemId.orEmpty(),
        track.originalCoverUrl.orEmpty(),
        track.replacementTitle.orEmpty(),
        track.replacementArtists.orEmpty(),
        track.replacementSource.orEmpty(),
        track.replacementProviderName.orEmpty(),
        track.replacementCoverUrl.orEmpty(),
        track.replacementStrategy.orEmpty(),
        track.replacementScore?.toString().orEmpty(),
    ).map(::escape)

    private fun decodeTrack(fields: List<String>): MusicTrack? {
        if (fields.size < 18) return null
        return runCatching {
            MusicTrack(
                id = unescape(fields[0]),
                title = unescape(fields[1]),
                artists = unescape(fields[2]),
                album = unescape(fields[3]),
                source = unescape(fields[4]),
                sourceType = TrackSourceType.valueOf(unescape(fields[5])),
                coverUrl = unescape(fields[6]).ifBlank { null },
                durationMs = unescape(fields[7]).toLongOrNull(),
                localUri = unescape(fields[8]).ifBlank { null },
                lyrics = unescape(fields[9]).ifBlank { null },
                providerId = unescape(fields[10]).ifBlank { null },
                providerName = unescape(fields[11]).ifBlank { null },
                isSmartReplacement = unescape(fields[12]).toBooleanStrictOrNull() ?: false,
                originalTitle = unescape(fields[13]).ifBlank { null },
                originalProviderName = unescape(fields[14]).ifBlank { null },
                originalCoverUrl = fields.unescapedOrNull(18),
                replacementTitle = fields.unescapedOrNull(19),
                replacementArtists = fields.unescapedOrNull(20),
                replacementSource = fields.unescapedOrNull(21),
                replacementProviderName = fields.unescapedOrNull(22),
                replacementCoverUrl = fields.unescapedOrNull(23),
                replacementStrategy = fields.unescapedOrNull(24),
                replacementScore = fields.unescapedOrNull(25)?.toDoubleOrNull(),
                isUnavailable = unescape(fields[15]).toBooleanStrictOrNull() ?: false,
                artistItemId = unescape(fields[16]).ifBlank { null },
                albumItemId = unescape(fields[17]).ifBlank { null },
            )
        }.getOrNull()
    }

    private fun List<String>.unescapedOrNull(index: Int): String? {
        return getOrNull(index)?.let(::unescape)?.ifBlank { null }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '%' -> append("%25")
                '\t' -> append("%09")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                when (value.substring(index + 1, index + 3)) {
                    "25" -> {
                        result.append('%')
                        index += 3
                    }
                    "09" -> {
                        result.append('\t')
                        index += 3
                    }
                    "0A" -> {
                        result.append('\n')
                        index += 3
                    }
                    "0D" -> {
                        result.append('\r')
                        index += 3
                    }
                    else -> {
                        result.append(value[index])
                        index += 1
                    }
                }
            } else {
                result.append(value[index])
                index += 1
            }
        }
        return result.toString()
    }
}

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

data class ProviderPlaylistDetail(
    val playlist: ProviderPlaylist,
    val tracks: List<MusicTrack> = emptyList(),
)

data class ProviderMediaItemDetail(
    val item: ProviderMediaItem,
    val tracks: List<MusicTrack> = emptyList(),
    val albums: List<ProviderMediaItem> = emptyList(),
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
        smartReplacementProviderIds: Set<String> = emptySet(),
        smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
        smartReplacementUseOriginalMetadata: Boolean = false,
        smartReplacementUseOriginalLyrics: Boolean = false,
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
    suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail =
        ProviderPlaylistDetail(playlist, playlistTracks(playlist))
    suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack>
    suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        ProviderMediaItemDetail(item, mediaItemTracks(item))
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
    fun prepareLoading(track: MusicTrack) = Unit
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
