package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SettingsFeatureUiState(
    val settingsState: SettingsState = SettingsState(),
    val cacheUsage: CacheUsage = CacheUsage(),
    val downloadTasks: List<DownloadTask> = emptyList(),
    val localMusic: LocalMusicUiState = LocalMusicUiState(),
    val statusBarLyricsAvailable: Boolean = false,
    val debugLogViewerAvailable: Boolean = false,
    val isBusy: Boolean = false,
    val feedback: String? = null,
) {
    val settings: AppSettings get() = settingsState.settings
}

interface SettingsFeatureController {
    val uiState: StateFlow<SettingsFeatureUiState>
    fun close()
    fun update(transform: (AppSettings) -> AppSettings)
    fun setThemePaletteStyle(value: ThemePaletteStyle)
    fun setThemeColorSpec(value: ThemeColorSpec)
    fun setWifiAudioQualityPolicy(value: AudioQualityPolicy)
    fun setCellularAudioQualityPolicy(value: AudioQualityPolicy)
    fun setDownloadParallelism(value: Int)
    fun setAudioCacheLimitMb(value: Int)
    fun setImageCacheLimitMb(value: Int)
    fun refreshLocalMusicDirectories()
    fun setLocalMusicDirectoryEnabled(directoryId: String, enabled: Boolean)
    fun setLocalMusicMinDurationSeconds(value: Int)
    fun clearCache()
    fun refreshCacheUsage()
    fun openDownloadManager()
    fun openDebugLogs()
    fun setStatusBarLyricsAvailability(available: Boolean)
    fun setStatusBarLyricsEnabled(enabled: Boolean)
    fun dismissFeedback(feedback: String)
}

fun createSettingsFeatureController(
    settingsRepository: AppSettingsRepository,
    providerRepository: ProviderMusicRepository,
    downloadRepository: DownloadRepository,
    resourceCacheRepository: ResourceCacheRepository,
    localMusicController: LocalMusicFeatureController,
    debugLogViewerAvailable: Boolean,
    navigator: AppNavigator,
    scope: CoroutineScope,
): SettingsFeatureController = DefaultSettingsFeatureController(
    settingsRepository,
    providerRepository,
    downloadRepository,
    resourceCacheRepository,
    localMusicController,
    debugLogViewerAvailable,
    navigator,
    scope,
)

private class DefaultSettingsFeatureController(
    private val settingsRepository: AppSettingsRepository,
    private val providerRepository: ProviderMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val resourceCacheRepository: ResourceCacheRepository,
    private val localMusicController: LocalMusicFeatureController,
    debugLogViewerAvailable: Boolean,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
) : SettingsFeatureController {
    private val mutableUiState = MutableStateFlow(
        SettingsFeatureUiState(
            settingsState = settingsRepository.state.value,
            cacheUsage = resourceCacheRepository.usage.value,
            downloadTasks = downloadRepository.tasks.value,
            localMusic = localMusicController.uiState.value,
            debugLogViewerAvailable = debugLogViewerAvailable,
        )
    )
    override val uiState: StateFlow<SettingsFeatureUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            combine(
                settingsRepository.state,
                resourceCacheRepository.usage,
                downloadRepository.tasks,
                localMusicController.uiState,
            ) { settings, cache, downloads, localMusic ->
                mutableUiState.value.copy(
                    settingsState = settings,
                    cacheUsage = cache,
                    downloadTasks = downloads,
                    localMusic = localMusic,
                )
            }.collect { mutableUiState.value = it }
        }
        scope.launch {
            runCatching {
                applySavedAudioQualityPolicies(
                    loadSettings = settingsRepository::awaitSettings,
                    applyPolicies = providerRepository::updateAudioQualityPolicies,
                )
            }.onFailure(::failed)
        }
        scope.launch {
            runCatching {
                applySavedCacheLimits(
                    loadSettings = settingsRepository::awaitSettings,
                    applyLimit = resourceCacheRepository::updateLimit,
                )
                resourceCacheRepository.refreshUsage()
            }.onFailure(::failed)
        }
    }

    override fun close() {
        navigator.pop(AppRoute.Settings)
    }

    override fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { settingsRepository.update(transform) }
    }

    override fun setThemePaletteStyle(value: ThemePaletteStyle) {
        scope.launch { settingsRepository.updateThemePaletteStyle(value) }
    }

    override fun setThemeColorSpec(value: ThemeColorSpec) {
        scope.launch { settingsRepository.updateThemeColorSpec(value) }
    }

    override fun setWifiAudioQualityPolicy(value: AudioQualityPolicy) {
        scope.launch {
            settingsRepository.update { it.copy(wifiAudioQualityPolicy = value) }
            val settings = settingsRepository.state.value.settings
            providerRepository.updateAudioQualityPolicies(value, settings.cellularAudioQualityPolicy)
        }
    }

    override fun setCellularAudioQualityPolicy(value: AudioQualityPolicy) {
        scope.launch {
            settingsRepository.update { it.copy(cellularAudioQualityPolicy = value) }
            val settings = settingsRepository.state.value.settings
            providerRepository.updateAudioQualityPolicies(settings.wifiAudioQualityPolicy, value)
        }
    }

    override fun setDownloadParallelism(value: Int) {
        val normalized = value.coerceIn(1, 5)
        scope.launch {
            settingsRepository.update { it.copy(downloadParallelism = normalized) }
            downloadRepository.updateParallelism(normalized)
        }
    }

    override fun setAudioCacheLimitMb(value: Int) {
        updateCacheLimits(audioMb = value.coerceAtLeast(0), imageMb = null)
    }

    override fun setImageCacheLimitMb(value: Int) {
        updateCacheLimits(audioMb = null, imageMb = value.coerceAtLeast(0))
    }

    override fun refreshLocalMusicDirectories() {
        localMusicController.refreshDirectories()
    }

    override fun setLocalMusicDirectoryEnabled(directoryId: String, enabled: Boolean) {
        localMusicController.onDirectoryEnabledChange(directoryId, enabled)
    }

    override fun setLocalMusicMinDurationSeconds(value: Int) {
        localMusicController.onMinDurationChange(value)
    }

    override fun clearCache() {
        scope.launch {
            busy("正在清理缓存")
            runCatching { resourceCacheRepository.clearAll() }
                .onSuccess {
                    resourceCacheRepository.refreshUsage()
                    done("缓存已清理")
                }
                .onFailure(::failed)
        }
    }

    override fun refreshCacheUsage() {
        scope.launch { runCatching { resourceCacheRepository.refreshUsage() }.onFailure(::failed) }
    }

    override fun openDownloadManager() {
        navigator.navigate(AppRoute.DownloadManager)
    }

    override fun openDebugLogs() {
        if (uiState.value.debugLogViewerAvailable) navigator.navigate(AppRoute.DebugLogs)
    }

    override fun setStatusBarLyricsAvailability(available: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(statusBarLyricsAvailable = available)
    }

    override fun setStatusBarLyricsEnabled(enabled: Boolean) {
        update { it.copy(statusBarLyricsEnabled = enabled) }
    }

    override fun dismissFeedback(feedback: String) {
        if (uiState.value.feedback == feedback) {
            mutableUiState.value = mutableUiState.value.copy(feedback = null)
        }
    }

    private fun updateCacheLimits(audioMb: Int?, imageMb: Int?) {
        scope.launch {
            val current = settingsRepository.state.value.settings
            val nextAudio = audioMb ?: current.audioCacheLimitMb
            val nextImage = imageMb ?: current.imageCacheLimitMb
            settingsRepository.update {
                it.copy(audioCacheLimitMb = nextAudio, imageCacheLimitMb = nextImage)
            }
            resourceCacheRepository.updateLimit(cacheLimitFor(nextAudio, nextImage))
            resourceCacheRepository.refreshUsage()
        }
    }

    private fun busy(message: String) {
        mutableUiState.value = mutableUiState.value.copy(isBusy = true, feedback = message)
    }

    private fun done(message: String) {
        mutableUiState.value = mutableUiState.value.copy(isBusy = false, feedback = message)
    }

    private fun failed(throwable: Throwable) {
        mutableUiState.value = mutableUiState.value.copy(
            isBusy = false,
            feedback = throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" },
        )
    }
}

internal suspend fun applySavedAudioQualityPolicies(
    loadSettings: suspend () -> AppSettings,
    applyPolicies: suspend (AudioQualityPolicy, AudioQualityPolicy) -> Unit,
) {
    val settings = loadSettings()
    applyPolicies(settings.wifiAudioQualityPolicy, settings.cellularAudioQualityPolicy)
}

internal suspend fun applySavedCacheLimits(
    loadSettings: suspend () -> AppSettings,
    applyLimit: suspend (CacheLimit) -> Unit,
) {
    val settings = loadSettings()
    applyLimit(cacheLimitFor(settings.audioCacheLimitMb, settings.imageCacheLimitMb))
}

internal fun cacheLimitFor(audioMb: Int, imageMb: Int): CacheLimit = CacheLimit(
    audioMaxBytes = audioMb.coerceAtLeast(0).toLong() * 1024L * 1024L,
    imageMaxBytes = imageMb.coerceAtLeast(0).toLong() * 1024L * 1024L,
)
