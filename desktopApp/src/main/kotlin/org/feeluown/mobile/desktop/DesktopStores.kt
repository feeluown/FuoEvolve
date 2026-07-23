package org.feeluown.mobile.desktop

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.feeluown.mobile.AppSettings
import org.feeluown.mobile.APP_SETTINGS_FILE_NAME
import org.feeluown.mobile.AppSettingsRepository
import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.CacheLimit
import org.feeluown.mobile.CacheUsage
import org.feeluown.mobile.DEFAULT_AUDIO_CACHE_LIMIT_MB
import org.feeluown.mobile.DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY
import org.feeluown.mobile.DEFAULT_ENABLED_PROVIDER_IDS
import org.feeluown.mobile.DEFAULT_IMAGE_CACHE_LIMIT_MB
import org.feeluown.mobile.DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS
import org.feeluown.mobile.DEFAULT_PROVIDER_ORDER_IDS
import org.feeluown.mobile.DEFAULT_SMART_REPLACEMENT_MIN_SCORE
import org.feeluown.mobile.DEFAULT_UNAVAILABLE_PLAYBACK_POLICY
import org.feeluown.mobile.DEFAULT_WIFI_AUDIO_QUALITY_POLICY
import org.feeluown.mobile.DataStoreAppSettingsRepository
import org.feeluown.mobile.DownloadRepository
import org.feeluown.mobile.DownloadState
import org.feeluown.mobile.HomeSection
import org.feeluown.mobile.LocalMusicViewMode
import org.feeluown.mobile.LegacyAppSettingsLoader
import org.feeluown.mobile.LyricFontSize
import org.feeluown.mobile.MineSection
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackQueueCodec
import org.feeluown.mobile.PlaybackQueueSnapshot
import org.feeluown.mobile.PlaybackQueueStore
import org.feeluown.mobile.PlaylistFilter
import org.feeluown.mobile.ProviderHeaderInput
import org.feeluown.mobile.ProviderLoginMode
import org.feeluown.mobile.ProviderMusicRepository
import org.feeluown.mobile.ResourceCacheRepository
import org.feeluown.mobile.SearchScope
import org.feeluown.mobile.ThemeColorScheme
import org.feeluown.mobile.ThemeMode
import org.feeluown.mobile.UnavailablePlaybackPolicy
import org.feeluown.mobile.createSettingsDataStore
import okio.FileSystem
import okio.Path.Companion.toPath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Properties

internal fun createDesktopAppSettingsRepository(scope: CoroutineScope): AppSettingsRepository {
    val dataStore = createSettingsDataStore(
        storage = OkioStorage<Preferences>(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = {
                File(DesktopPaths.configDir, APP_SETTINGS_FILE_NAME).absolutePath.toPath()
            },
        ),
    )
    return DataStoreAppSettingsRepository(
        dataStore = dataStore,
        legacyLoader = LegacyAppSettingsLoader { DesktopLegacySettingsLoader().load() },
        scope = scope,
    )
}

internal class DesktopLegacySettingsLoader(
    private val file: File = File(DesktopPaths.configDir, "settings.properties"),
) {
    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            if (file.isFile) file.inputStream().use(::load)
        }
        AppSettings(
            homeSection = enumValue(props, KEY_HOME_SECTION, HomeSection.Recommend),
            mineSection = enumValue(props, KEY_MINE_SECTION, MineSection.Playlists),
            playlistFilter = enumValue(props, KEY_PLAYLIST_FILTER, PlaylistFilter.All),
            localMusicViewMode = enumValue(props, KEY_LOCAL_MUSIC_VIEW_MODE, LocalMusicViewMode.All),
            excludedLocalMusicDirectoryIds = props.stringSet(KEY_EXCLUDED_LOCAL_MUSIC_DIRECTORY_IDS),
            localMusicMinDurationSeconds = props.getProperty(KEY_LOCAL_MUSIC_MIN_DURATION_SECONDS)
                ?.toIntOrNull()
                ?: DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
            searchScope = enumValue(props, KEY_SEARCH_SCOPE, SearchScope.All),
            selectedSearchProviderId = props.getProperty(KEY_SELECTED_SEARCH_PROVIDER_ID)?.takeIf { it.isNotBlank() },
            selectedSettingsProviderId = props.getProperty(KEY_SELECTED_SETTINGS_PROVIDER_ID)?.takeIf { it.isNotBlank() },
            providerLoginMode = enumValue(props, KEY_PROVIDER_LOGIN_MODE, ProviderLoginMode.WebView),
            providerCookieInputs = props.getProperty(KEY_PROVIDER_COOKIE_INPUTS)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSONObject(it).readProviderCookieInputs() }.getOrNull() }
                ?: emptyMap(),
            providerHeaderInputs = props.getProperty(KEY_PROVIDER_HEADER_INPUTS)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSONObject(it).readProviderHeaderInputs() }.getOrNull() }
                ?: emptyMap(),
            enabledProviderIds = props.stringSet(KEY_ENABLED_PROVIDER_IDS).ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS },
            providerOrderIds = props.stringList(KEY_PROVIDER_ORDER_IDS).ifEmpty { DEFAULT_PROVIDER_ORDER_IDS },
            audioCacheLimitMb = props.getProperty(KEY_AUDIO_CACHE_LIMIT_MB)?.toIntOrNull() ?: DEFAULT_AUDIO_CACHE_LIMIT_MB,
            imageCacheLimitMb = props.getProperty(KEY_IMAGE_CACHE_LIMIT_MB)?.toIntOrNull() ?: DEFAULT_IMAGE_CACHE_LIMIT_MB,
            wifiAudioQualityPolicy = enumValue(props, KEY_WIFI_AUDIO_QUALITY_POLICY, DEFAULT_WIFI_AUDIO_QUALITY_POLICY),
            cellularAudioQualityPolicy = enumValue(
                props,
                KEY_CELLULAR_AUDIO_QUALITY_POLICY,
                DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY,
            ),
            unavailablePlaybackPolicy = enumValue(
                props,
                KEY_UNAVAILABLE_PLAYBACK_POLICY,
                DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
            ),
            smartReplacementProviderIds = props.stringSet(KEY_SMART_REPLACEMENT_PROVIDER_IDS),
            smartReplacementMinScore = props.getProperty(KEY_SMART_REPLACEMENT_MIN_SCORE)
                ?.toDoubleOrNull()
                ?: DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
            smartReplacementUseReplacementMetadata = props.getProperty(
                KEY_SMART_REPLACEMENT_USE_REPLACEMENT_METADATA,
            ).toBoolean(),
            smartReplacementUseReplacementLyrics = props.getProperty(
                KEY_SMART_REPLACEMENT_USE_REPLACEMENT_LYRICS,
            ).toBoolean(),
            lyricFontSize = enumValue(props, KEY_LYRIC_FONT_SIZE, LyricFontSize.Small),
            themeMode = enumValue(props, KEY_THEME_MODE, ThemeMode.System),
            themeColorScheme = enumValue(props, KEY_THEME_COLOR_SCHEME, ThemeColorScheme.Dynamic),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(props: Properties, key: String, fallback: T): T {
        val raw = props.getProperty(key) ?: return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    private fun Properties.stringSet(key: String): Set<String> = stringList(key).toSet()

    private fun Properties.stringList(key: String): List<String> {
        val raw = getProperty(key).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.optString(index) }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private companion object {
        private const val KEY_HOME_SECTION = "home_section"
        private const val KEY_MINE_SECTION = "mine_section"
        private const val KEY_PLAYLIST_FILTER = "playlist_filter"
        private const val KEY_LOCAL_MUSIC_VIEW_MODE = "local_music_view_mode"
        private const val KEY_EXCLUDED_LOCAL_MUSIC_DIRECTORY_IDS = "excluded_local_music_directory_ids"
        private const val KEY_LOCAL_MUSIC_MIN_DURATION_SECONDS = "local_music_min_duration_seconds"
        private const val KEY_SEARCH_SCOPE = "search_scope"
        private const val KEY_SELECTED_SEARCH_PROVIDER_ID = "selected_search_provider_id"
        private const val KEY_SELECTED_SETTINGS_PROVIDER_ID = "selected_settings_provider_id"
        private const val KEY_PROVIDER_LOGIN_MODE = "provider_login_mode"
        private const val KEY_PROVIDER_COOKIE_INPUTS = "provider_cookie_inputs"
        private const val KEY_PROVIDER_HEADER_INPUTS = "provider_header_inputs"
        private const val KEY_ENABLED_PROVIDER_IDS = "enabled_provider_ids"
        private const val KEY_PROVIDER_ORDER_IDS = "provider_order_ids"
        private const val KEY_AUDIO_CACHE_LIMIT_MB = "audio_cache_limit_mb"
        private const val KEY_IMAGE_CACHE_LIMIT_MB = "image_cache_limit_mb"
        private const val KEY_WIFI_AUDIO_QUALITY_POLICY = "wifi_audio_quality_policy"
        private const val KEY_CELLULAR_AUDIO_QUALITY_POLICY = "cellular_audio_quality_policy"
        private const val KEY_UNAVAILABLE_PLAYBACK_POLICY = "unavailable_playback_policy"
        private const val KEY_SMART_REPLACEMENT_PROVIDER_IDS = "smart_replacement_provider_ids"
        private const val KEY_SMART_REPLACEMENT_MIN_SCORE = "smart_replacement_min_score"
        private const val KEY_SMART_REPLACEMENT_USE_REPLACEMENT_METADATA = "smart_replacement_use_replacement_metadata"
        private const val KEY_SMART_REPLACEMENT_USE_REPLACEMENT_LYRICS = "smart_replacement_use_replacement_lyrics"
        private const val KEY_LYRIC_FONT_SIZE = "lyric_font_size"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR_SCHEME = "theme_color_scheme"
    }
}

internal class DesktopPlaybackQueueStore(
    private val file: File = File(DesktopPaths.configDir, "playback-queue.txt"),
) : PlaybackQueueStore {
    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.IO) {
        file.takeIf { it.isFile }
            ?.let { runCatching { PlaybackQueueCodec.decode(it.readText()) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(PlaybackQueueCodec.encode(snapshot))
        }
    }
}

internal class DesktopResourceCacheRepository(
    private val cacheDir: File = DesktopPaths.cacheDir,
) : ResourceCacheRepository {
    private val mutableUsage = MutableStateFlow(CacheUsage())
    override val usage: StateFlow<CacheUsage> = mutableUsage.asStateFlow()

    override suspend fun refreshUsage() {
        mutableUsage.value = withContext(Dispatchers.IO) { CacheUsage(audioBytes = directorySize(cacheDir)) }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            mutableUsage.value = CacheUsage()
        }
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        refreshUsage()
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        return if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf(::directorySize)
    }
}

internal class DesktopDownloadRepository(
    private val providerRepository: ProviderMusicRepository,
    private val downloadDir: File = File(DesktopPaths.musicDir, "FuoEvolve").apply { mkdirs() },
) : DownloadRepository {
    private val records = linkedMapOf<String, String>()
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()

    override suspend fun load() {
        mutableStates.value = records.mapValues { DownloadState.Downloaded(it.value) }
    }

    override suspend fun download(track: MusicTrack) {
        withContext(Dispatchers.IO) {
            mutableStates.update { it + (track.id to DownloadState.Downloading(0f)) }
            val payload = providerRepository.resolve(track)
            val extension = payload.url.substringBefore('?').substringAfterLast('.', "mp3").take(5).ifBlank { "mp3" }
            val target = File(downloadDir, "${sanitize(payload.title)} - ${sanitize(payload.artists)}.$extension")
            val connection = URL(payload.url).openConnection()
            payload.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var written = 0L
            connection.getInputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total != null) {
                            mutableStates.update { current ->
                                current + (track.id to DownloadState.Downloading((written.toFloat() / total).coerceIn(0f, 1f)))
                            }
                        }
                    }
                }
            }
            val uri = target.toURI().toString()
            records[track.id] = uri
            mutableStates.update { it + (track.id to DownloadState.Downloaded(uri)) }
        }
    }

    override suspend fun deleteDownloaded(track: MusicTrack) {
        withContext(Dispatchers.IO) {
            records.remove(track.id)?.let { uri ->
                runCatching { File(java.net.URI(uri)).delete() }
            }
            mutableStates.update { it - track.id }
        }
    }

    private fun sanitize(value: String): String = value.ifBlank { "unknown" }
        .replace(Regex("""[\\/:*?"<>|]+"""), "_")
        .take(120)
}
