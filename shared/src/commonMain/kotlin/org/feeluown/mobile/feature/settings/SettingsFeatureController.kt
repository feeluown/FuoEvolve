package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.feeluown.mobile.feature.settings.SettingsAudioQualityPort as CoreAudioQualityPort
import org.feeluown.mobile.feature.settings.SettingsCachePort as CoreCachePort
import org.feeluown.mobile.feature.settings.SettingsDownloadPort as CoreDownloadPort
import org.feeluown.mobile.feature.settings.SettingsFeatureOwner as CoreOwner
import org.feeluown.mobile.feature.settings.SettingsFeaturePreferences as CorePreferences
import org.feeluown.mobile.feature.settings.SettingsFeatureState as CoreState
import org.feeluown.mobile.feature.settings.SettingsLocalMusicPort as CoreLocalMusicPort
import org.feeluown.mobile.feature.settings.SettingsNavigationPort as CoreNavigationPort
import org.feeluown.mobile.feature.settings.SettingsPreferencesPort as CorePreferencesPort
import org.feeluown.mobile.feature.settings.createSettingsFeatureOwner

typealias SettingsFeaturePreferencesUiState = CorePreferences<
    ThemeMode,
    ThemeColorScheme,
    ThemePaletteStyle,
    ThemeColorSpec,
    AudioQualityPolicy,
    UnavailablePlaybackPolicy,
    LyricFontSize,
>

data class SettingsFeatureUiState(
    val settings: AppSettings = AppSettings(),
    val cacheUsage: CacheUsage = CacheUsage(),
    val downloadTasks: List<DownloadTask> = emptyList(),
    val localMusic: LocalMusicUiState = LocalMusicUiState(viewMode = LocalMusicViewMode.All),
    val statusBarLyricsAvailable: Boolean = false,
    val bydInstrumentLyricsAvailable: Boolean = false,
    val debugLogViewerAvailable: Boolean = false,
    val isBusy: Boolean = false,
    val feedback: String? = null,
)

interface SettingsFeatureController {
    val uiState: StateFlow<SettingsFeatureUiState>
    fun close()

    /** Narrow compatibility transform; AppSettings is not accepted as a write contract. */
    fun update(transform: (SettingsFeaturePreferencesUiState) -> SettingsFeaturePreferencesUiState)

    fun setThemeMode(value: ThemeMode)
    fun setThemeColorScheme(value: ThemeColorScheme)
    fun setThemePaletteStyle(value: ThemePaletteStyle)
    fun setThemeColorSpec(value: ThemeColorSpec)
    fun setWifiAudioQualityPolicy(value: AudioQualityPolicy)
    fun setCellularAudioQualityPolicy(value: AudioQualityPolicy)
    fun setUnavailablePlaybackPolicy(value: UnavailablePlaybackPolicy)
    fun setSmartReplacementMinScore(value: Double)
    fun setPauseOnOtherAppPlayback(value: Boolean)
    fun setLyricFontSize(value: LyricFontSize)
    fun setDynamicCoverColorEnabled(value: Boolean)
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
    fun setBydInstrumentLyricsEnabled(enabled: Boolean)
    fun dismissFeedback(feedback: String)
}

private typealias BoundCorePreferences = SettingsFeaturePreferencesUiState

private typealias BoundCoreState = CoreState<BoundCorePreferences, CacheUsage, DownloadTask, LocalMusicUiState>

private typealias BoundCoreOwner = CoreOwner<
    ThemeMode,
    ThemeColorScheme,
    ThemePaletteStyle,
    ThemeColorSpec,
    AudioQualityPolicy,
    UnavailablePlaybackPolicy,
    LyricFontSize,
    CacheUsage,
    DownloadTask,
    LocalMusicUiState,
>

fun createSettingsFeatureController(
    settingsRepository: AppSettingsRepository,
    providerRepository: ProviderMusicRepository,
    downloadRepository: DownloadRepository,
    resourceCacheRepository: ResourceCacheRepository,
    localMusicController: LocalMusicFeatureController,
    debugLogViewerAvailable: Boolean,
    navigator: AppNavigator,
    scope: CoroutineScope,
    bydInstrumentLyricsAvailable: Boolean = false,
): SettingsFeatureController {
    val owner = createSettingsFeatureOwner(
        preferences = BoundSettingsPreferencesPort(settingsRepository),
        audioQuality = CoreAudioQualityPort(providerRepository::updateAudioQualityPolicies),
        downloads = BoundSettingsDownloadPort(downloadRepository),
        cache = BoundSettingsCachePort(resourceCacheRepository),
        localMusic = BoundSettingsLocalMusicPort(localMusicController),
        navigation = BoundSettingsNavigationPort(navigator),
        debugLogViewerAvailable = debugLogViewerAvailable,
        scope = scope,
    )
    return BoundSettingsFeatureController(
        owner = owner,
        settingsRepository = settingsRepository,
        scope = scope,
        bydInstrumentLyricsAvailable = bydInstrumentLyricsAvailable,
    )
}

private class BoundSettingsFeatureController(
    private val owner: BoundCoreOwner,
    private val settingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
    private val bydInstrumentLyricsAvailable: Boolean,
) : SettingsFeatureController {
    override val uiState: StateFlow<SettingsFeatureUiState> = combine(
        owner.state,
        settingsRepository.state,
    ) { state, settingsState ->
        toUiState(state, settingsState.settings)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = toUiState(owner.state.value, settingsRepository.state.value.settings),
    )

    override fun close() = owner.close()

    override fun update(transform: (SettingsFeaturePreferencesUiState) -> SettingsFeaturePreferencesUiState) {
        val current = owner.state.value.preferences
        val next = transform(current)
        if (next.themeMode != current.themeMode) owner.setThemeMode(next.themeMode)
        if (next.themeColorScheme != current.themeColorScheme) owner.setThemeColorScheme(next.themeColorScheme)
        if (next.themePaletteStyle != current.themePaletteStyle) owner.setThemePaletteStyle(next.themePaletteStyle)
        if (next.themeColorSpec != current.themeColorSpec) owner.setThemeColorSpec(next.themeColorSpec)
        if (next.wifiAudioQualityPolicy != current.wifiAudioQualityPolicy) owner.setWifiAudioQualityPolicy(next.wifiAudioQualityPolicy)
        if (next.cellularAudioQualityPolicy != current.cellularAudioQualityPolicy) owner.setCellularAudioQualityPolicy(next.cellularAudioQualityPolicy)
        if (next.unavailablePlaybackPolicy != current.unavailablePlaybackPolicy) owner.setUnavailablePlaybackPolicy(next.unavailablePlaybackPolicy)
        if (next.smartReplacementMinScore != current.smartReplacementMinScore) owner.setSmartReplacementMinScore(next.smartReplacementMinScore)
        if (next.pauseOnOtherAppPlayback != current.pauseOnOtherAppPlayback) owner.setPauseOnOtherAppPlayback(next.pauseOnOtherAppPlayback)
        if (next.lyricFontSize != current.lyricFontSize) owner.setLyricFontSize(next.lyricFontSize)
        if (next.statusBarLyricsEnabled != current.statusBarLyricsEnabled) owner.setStatusBarLyricsEnabled(next.statusBarLyricsEnabled)
        if (next.dynamicCoverColorEnabled != current.dynamicCoverColorEnabled) owner.setDynamicCoverColorEnabled(next.dynamicCoverColorEnabled)
        if (next.downloadParallelism != current.downloadParallelism) owner.setDownloadParallelism(next.downloadParallelism)
        if (next.audioCacheLimitMb != current.audioCacheLimitMb) owner.setAudioCacheLimitMb(next.audioCacheLimitMb)
        if (next.imageCacheLimitMb != current.imageCacheLimitMb) owner.setImageCacheLimitMb(next.imageCacheLimitMb)
    }

    override fun setThemeMode(value: ThemeMode) = owner.setThemeMode(value)
    override fun setThemeColorScheme(value: ThemeColorScheme) = owner.setThemeColorScheme(value)
    override fun setThemePaletteStyle(value: ThemePaletteStyle) = owner.setThemePaletteStyle(value)
    override fun setThemeColorSpec(value: ThemeColorSpec) = owner.setThemeColorSpec(value)
    override fun setWifiAudioQualityPolicy(value: AudioQualityPolicy) = owner.setWifiAudioQualityPolicy(value)
    override fun setCellularAudioQualityPolicy(value: AudioQualityPolicy) = owner.setCellularAudioQualityPolicy(value)
    override fun setUnavailablePlaybackPolicy(value: UnavailablePlaybackPolicy) = owner.setUnavailablePlaybackPolicy(value)
    override fun setSmartReplacementMinScore(value: Double) = owner.setSmartReplacementMinScore(value)
    override fun setPauseOnOtherAppPlayback(value: Boolean) = owner.setPauseOnOtherAppPlayback(value)
    override fun setLyricFontSize(value: LyricFontSize) = owner.setLyricFontSize(value)
    override fun setDynamicCoverColorEnabled(value: Boolean) = owner.setDynamicCoverColorEnabled(value)
    override fun setDownloadParallelism(value: Int) = owner.setDownloadParallelism(value)
    override fun setAudioCacheLimitMb(value: Int) = owner.setAudioCacheLimitMb(value)
    override fun setImageCacheLimitMb(value: Int) = owner.setImageCacheLimitMb(value)
    override fun refreshLocalMusicDirectories() = owner.refreshLocalMusicDirectories()
    override fun setLocalMusicDirectoryEnabled(directoryId: String, enabled: Boolean) = owner.setLocalMusicDirectoryEnabled(directoryId, enabled)
    override fun setLocalMusicMinDurationSeconds(value: Int) = owner.setLocalMusicMinDurationSeconds(value)
    override fun clearCache() = owner.clearCache()
    override fun refreshCacheUsage() = owner.refreshCacheUsage()
    override fun openDownloadManager() = owner.openDownloadManager()
    override fun openDebugLogs() = owner.openDebugLogs()
    override fun setStatusBarLyricsAvailability(available: Boolean) = owner.setStatusBarLyricsAvailability(available)
    override fun setStatusBarLyricsEnabled(enabled: Boolean) = owner.setStatusBarLyricsEnabled(enabled)
    override fun setBydInstrumentLyricsEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.update { settings -> settings.copy(bydInstrumentLyricsEnabled = enabled) }
        }
    }
    override fun dismissFeedback(feedback: String) = owner.dismissFeedback(feedback)

    private fun toUiState(
        state: BoundCoreState,
        appSettings: AppSettings,
    ): SettingsFeatureUiState = SettingsFeatureUiState(
        settings = appSettings,
        cacheUsage = state.cacheUsage,
        downloadTasks = state.downloadTasks,
        localMusic = state.localMusic,
        statusBarLyricsAvailable = state.statusBarLyricsAvailable,
        bydInstrumentLyricsAvailable = bydInstrumentLyricsAvailable,
        debugLogViewerAvailable = state.debugLogViewerAvailable,
        isBusy = state.isBusy,
        feedback = state.feedback,
    )
}

private class BoundSettingsPreferencesPort(
    private val repository: AppSettingsRepository,
) : CorePreferencesPort<
    ThemeMode,
    ThemeColorScheme,
    ThemePaletteStyle,
    ThemeColorSpec,
    AudioQualityPolicy,
    UnavailablePlaybackPolicy,
    LyricFontSize,
> {
    override val state: StateFlow<BoundCorePreferences> = repository.state.mapSettingsState { it.settings.toCorePreferences() }

    override suspend fun awaitPreferences(): BoundCorePreferences = repository.awaitSettings().toCorePreferences()
    override suspend fun setThemeMode(value: ThemeMode) = repository.update { it.copy(themeMode = value) }
    override suspend fun setThemeColorScheme(value: ThemeColorScheme) = repository.update { it.copy(themeColorScheme = value) }
    override suspend fun setThemePaletteStyle(value: ThemePaletteStyle) = repository.updateThemePaletteStyle(value)
    override suspend fun setThemeColorSpec(value: ThemeColorSpec) = repository.updateThemeColorSpec(value)
    override suspend fun setWifiAudioQualityPolicy(value: AudioQualityPolicy) = repository.update { it.copy(wifiAudioQualityPolicy = value) }
    override suspend fun setCellularAudioQualityPolicy(value: AudioQualityPolicy) = repository.update { it.copy(cellularAudioQualityPolicy = value) }
    override suspend fun setUnavailablePlaybackPolicy(value: UnavailablePlaybackPolicy) = repository.update { it.copy(unavailablePlaybackPolicy = value) }
    override suspend fun setSmartReplacementMinScore(value: Double) = repository.update { it.copy(smartReplacementMinScore = value) }
    override suspend fun setPauseOnOtherAppPlayback(value: Boolean) = repository.update { it.copy(pauseOnOtherAppPlayback = value) }
    override suspend fun setLyricFontSize(value: LyricFontSize) = repository.update { it.copy(lyricFontSize = value) }
    override suspend fun setStatusBarLyricsEnabled(value: Boolean) = repository.update { it.copy(statusBarLyricsEnabled = value) }
    override suspend fun setDynamicCoverColorEnabled(value: Boolean) = repository.update { it.copy(dynamicCoverColorEnabled = value) }
    override suspend fun setDownloadParallelism(value: Int) = repository.update { it.copy(downloadParallelism = value) }
    override suspend fun setCacheLimits(audioMb: Int, imageMb: Int) = repository.update {
        it.copy(audioCacheLimitMb = audioMb, imageCacheLimitMb = imageMb)
    }
}

private class BoundSettingsDownloadPort(
    private val repository: DownloadRepository,
) : CoreDownloadPort<DownloadTask> {
    override val tasks: StateFlow<List<DownloadTask>> = repository.tasks
    override suspend fun updateParallelism(value: Int) = repository.updateParallelism(value)
}

private class BoundSettingsCachePort(
    private val repository: ResourceCacheRepository,
) : CoreCachePort<CacheUsage> {
    override val usage: StateFlow<CacheUsage> = repository.usage
    override suspend fun updateLimit(audioMaxBytes: Long, imageMaxBytes: Long) {
        repository.updateLimit(CacheLimit(audioMaxBytes = audioMaxBytes, imageMaxBytes = imageMaxBytes))
    }
    override suspend fun clearAll() = repository.clearAll()
    override suspend fun refreshUsage() = repository.refreshUsage()
}

private class BoundSettingsLocalMusicPort(
    private val controller: LocalMusicFeatureController,
) : CoreLocalMusicPort<LocalMusicUiState> {
    override val state: StateFlow<LocalMusicUiState> = controller.uiState
    override fun refreshDirectories() = controller.refreshDirectories()
    override fun setDirectoryEnabled(directoryId: String, enabled: Boolean) = controller.onDirectoryEnabledChange(directoryId, enabled)
    override fun setMinDurationSeconds(value: Int) = controller.onMinDurationChange(value)
}

private class BoundSettingsNavigationPort(
    private val navigator: AppNavigator,
) : CoreNavigationPort {
    override fun close() {
        navigator.pop(AppRoute.Settings)
    }
    override fun openDownloadManager() {
        navigator.navigate(AppRoute.DownloadManager)
    }
    override fun openDebugLogs() {
        navigator.navigate(AppRoute.DebugLogs)
    }
}

private fun AppSettings.toCorePreferences(): BoundCorePreferences = CorePreferences(
    themeMode = themeMode,
    themeColorScheme = themeColorScheme,
    themePaletteStyle = themePaletteStyle,
    themeColorSpec = themeColorSpec,
    wifiAudioQualityPolicy = wifiAudioQualityPolicy,
    cellularAudioQualityPolicy = cellularAudioQualityPolicy,
    unavailablePlaybackPolicy = unavailablePlaybackPolicy,
    smartReplacementMinScore = smartReplacementMinScore,
    pauseOnOtherAppPlayback = pauseOnOtherAppPlayback,
    lyricFontSize = lyricFontSize,
    statusBarLyricsEnabled = statusBarLyricsEnabled,
    dynamicCoverColorEnabled = dynamicCoverColorEnabled,
    downloadParallelism = downloadParallelism,
    audioCacheLimitMb = audioCacheLimitMb,
    imageCacheLimitMb = imageCacheLimitMb,
)

private class SettingsMappedStateFlow<Source, Target>(
    private val source: StateFlow<Source>,
    private val transform: (Source) -> Target,
) : StateFlow<Target> {
    override val value: Target
        get() = transform(source.value)
    override val replayCache: List<Target>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Target>): Nothing = source.collect(
        object : FlowCollector<Source> {
            override suspend fun emit(value: Source) {
                collector.emit(transform(value))
            }
        },
    )
}

private fun <Source, Target> StateFlow<Source>.mapSettingsState(transform: (Source) -> Target): StateFlow<Target> =
    SettingsMappedStateFlow(this, transform)

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
