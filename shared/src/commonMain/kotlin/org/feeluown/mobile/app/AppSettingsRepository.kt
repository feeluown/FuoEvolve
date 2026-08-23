package org.feeluown.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface LegacyAppSettingsLoader {
    suspend fun load(): AppSettings
}

class PersistentAppSettingsRepository(
    private val store: SettingsSnapshotStore,
    private val legacyLoader: LegacyAppSettingsLoader?,
    private val scope: CoroutineScope,
) : AppSettingsRepository {
    private val updateMutex = Mutex()
    private val ready = CompletableDeferred<AppSettings>()
    private val mutableState = MutableStateFlow(SettingsState())

    override val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    init {
        scope.launchSettingsInitialization()
    }

    override suspend fun awaitSettings(): AppSettings = ready.await()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        updateInternal(preserveThemeTuning = true, transform = transform)
    }

    override suspend fun updateThemePaletteStyle(value: ThemePaletteStyle) {
        updateInternal(preserveThemeTuning = false) { current ->
            current.copy(themePaletteStyle = value)
        }
    }

    override suspend fun updateThemeColorSpec(value: ThemeColorSpec) {
        updateInternal(preserveThemeTuning = false) { current ->
            current.copy(themeColorSpec = value)
        }
    }

    private suspend fun updateInternal(
        preserveThemeTuning: Boolean,
        transform: (AppSettings) -> AppSettings,
    ) {
        ready.await()
        updateMutex.withLock {
            val current = mutableState.value.settings
            val transformed = transform(current)
            val updated = if (preserveThemeTuning) {
                transformed.copy(
                    themePaletteStyle = current.themePaletteStyle,
                    themeColorSpec = current.themeColorSpec,
                )
            } else {
                transformed
            }.withoutProviderCredentials()
            store.write(updated.toPersistedSettings())
            mutableState.value = SettingsState(isLoaded = true, settings = updated)
        }
    }

    private fun CoroutineScope.launchSettingsInitialization() = launch {
        runCatching { loadOrMigrate() }
            .onSuccess { settings ->
                mutableState.value = SettingsState(isLoaded = true, settings = settings)
                ready.complete(settings)
            }
            .onFailure { throwable ->
                val fallback = AppSettings()
                mutableState.value = SettingsState(
                    isLoaded = true,
                    settings = fallback,
                    errorMessage = throwable.message ?: throwable::class.simpleName,
                )
                ready.complete(fallback)
            }
    }

    private suspend fun loadOrMigrate(): AppSettings {
        return when (val result = store.read()) {
            is SettingsSnapshotReadResult.Loaded -> result.snapshot.toAppSettings().withoutProviderCredentials()
            SettingsSnapshotReadResult.Corrupted -> AppSettings().also { fallback ->
                store.write(fallback.toPersistedSettings())
            }
            SettingsSnapshotReadResult.Missing -> {
                val migrated = legacyLoader
                    ?.load()
                    ?.withoutProviderCredentials()
                    ?: AppSettings()
                store.write(migrated.toPersistedSettings())
                migrated
            }
        }
    }
}

private fun AppSettings.withoutProviderCredentials(): AppSettings = copy(
    providerCookieInputs = emptyMap(),
    providerHeaderInputs = emptyMap(),
)

internal fun AppSettings.toPersistedSettings(): PersistedSettingsV1 = PersistedSettingsV1(
    onboardingCompleted = onboardingCompleted,
    homeSection = homeSection.name,
    mineSection = mineSection.name,
    playlistFilter = playlistFilter.name,
    localMusicViewMode = localMusicViewMode.name,
    excludedLocalMusicDirectoryIds = excludedLocalMusicDirectoryIds,
    localMusicMinDurationSeconds = localMusicMinDurationSeconds,
    searchScope = searchScope.name,
    selectedSearchProviderId = selectedSearchProviderId,
    selectedSettingsProviderId = selectedSettingsProviderId,
    providerLoginMode = providerLoginMode.name,
    enabledProviderIds = enabledProviderIds,
    providerOrderIds = providerOrderIds,
    searchProviderIds = searchProviderIds,
    recommendProviderIds = recommendProviderIds,
    exploreProviderIds = exploreProviderIds,
    mineProviderIds = mineProviderIds,
    audioCacheLimitMb = audioCacheLimitMb,
    imageCacheLimitMb = imageCacheLimitMb,
    downloadParallelism = downloadParallelism,
    wifiAudioQualityPolicy = wifiAudioQualityPolicy.name,
    cellularAudioQualityPolicy = cellularAudioQualityPolicy.name,
    unavailablePlaybackPolicy = unavailablePlaybackPolicy.name,
    smartReplacementProviderIds = smartReplacementProviderIds,
    smartReplacementMinScore = smartReplacementMinScore,
    smartReplacementSelections = smartReplacementSelections.mapValues { (_, selection) -> selection.toPersisted() },
    pauseOnOtherAppPlayback = pauseOnOtherAppPlayback,
    lyricFontSize = lyricFontSize.name,
    statusBarLyricsEnabled = statusBarLyricsEnabled,
    bydInstrumentLyricsEnabled = bydInstrumentLyricsEnabled,
    themeMode = themeMode.name,
    themeColorScheme = themeColorScheme.name,
    themePaletteStyle = themePaletteStyle.name,
    themeColorSpec = themeColorSpec.name,
    dynamicCoverColorEnabled = dynamicCoverColorEnabled,
    playlistPlaybackStatsVersion = playlistPlaybackStatsVersion,
    playlistPlaybackStats = playlistPlaybackStats.mapValues { (_, stat) ->
        PersistedPlaylistPlaybackStat(stat.playCount, stat.lastPlayedAtMillis)
    },
)

internal fun PersistedSettingsV1.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        onboardingCompleted = onboardingCompleted ?: defaults.onboardingCompleted,
        homeSection = homeSection.enumOr(defaults.homeSection),
        mineSection = mineSection.enumOr(defaults.mineSection),
        playlistFilter = playlistFilter.enumOr(defaults.playlistFilter),
        localMusicViewMode = localMusicViewMode.enumOr(defaults.localMusicViewMode),
        excludedLocalMusicDirectoryIds = excludedLocalMusicDirectoryIds ?: defaults.excludedLocalMusicDirectoryIds,
        localMusicMinDurationSeconds = localMusicMinDurationSeconds ?: defaults.localMusicMinDurationSeconds,
        searchScope = searchScope.enumOr(defaults.searchScope),
        selectedSearchProviderId = selectedSearchProviderId,
        selectedSettingsProviderId = selectedSettingsProviderId,
        providerLoginMode = providerLoginMode.enumOr(defaults.providerLoginMode),
        providerCookieInputs = emptyMap(),
        providerHeaderInputs = emptyMap(),
        enabledProviderIds = enabledProviderIds ?: defaults.enabledProviderIds,
        providerOrderIds = providerOrderIds ?: defaults.providerOrderIds,
        searchProviderIds = searchProviderIds ?: defaults.searchProviderIds,
        recommendProviderIds = recommendProviderIds ?: defaults.recommendProviderIds,
        exploreProviderIds = exploreProviderIds ?: defaults.exploreProviderIds,
        mineProviderIds = mineProviderIds ?: defaults.mineProviderIds,
        audioCacheLimitMb = audioCacheLimitMb ?: defaults.audioCacheLimitMb,
        imageCacheLimitMb = imageCacheLimitMb ?: defaults.imageCacheLimitMb,
        downloadParallelism = downloadParallelism ?: defaults.downloadParallelism,
        wifiAudioQualityPolicy = wifiAudioQualityPolicy.enumOr(defaults.wifiAudioQualityPolicy),
        cellularAudioQualityPolicy = cellularAudioQualityPolicy.enumOr(defaults.cellularAudioQualityPolicy),
        unavailablePlaybackPolicy = unavailablePlaybackPolicy.enumOr(defaults.unavailablePlaybackPolicy),
        smartReplacementProviderIds = smartReplacementProviderIds ?: defaults.smartReplacementProviderIds,
        smartReplacementMinScore = smartReplacementMinScore ?: defaults.smartReplacementMinScore,
        smartReplacementSelections = smartReplacementSelections
            ?.mapValues { (_, selection) -> selection.toDomain() }
            ?: defaults.smartReplacementSelections,
        pauseOnOtherAppPlayback = pauseOnOtherAppPlayback ?: defaults.pauseOnOtherAppPlayback,
        lyricFontSize = lyricFontSize.enumOr(defaults.lyricFontSize),
        statusBarLyricsEnabled = statusBarLyricsEnabled ?: defaults.statusBarLyricsEnabled,
        bydInstrumentLyricsEnabled = bydInstrumentLyricsEnabled ?: defaults.bydInstrumentLyricsEnabled,
        themeMode = themeMode.enumOr(defaults.themeMode),
        themeColorScheme = themeColorScheme.enumOr(defaults.themeColorScheme),
        themePaletteStyle = themePaletteStyle.enumOr(defaults.themePaletteStyle),
        themeColorSpec = themeColorSpec.enumOr(defaults.themeColorSpec),
        dynamicCoverColorEnabled = dynamicCoverColorEnabled ?: defaults.dynamicCoverColorEnabled,
        playlistPlaybackStatsVersion = playlistPlaybackStatsVersion ?: defaults.playlistPlaybackStatsVersion,
        playlistPlaybackStats = playlistPlaybackStats
            ?.mapValues { (_, stat) -> PlaylistPlaybackStat(stat.playCount, stat.lastPlayedAtMillis) }
            ?: defaults.playlistPlaybackStats,
    )
}

private fun SmartReplacementSelection.toPersisted() = PersistedSmartReplacementSelection(
    replacementId = replacementId,
    replacementTitle = replacementTitle,
    replacementArtists = replacementArtists,
    replacementAlbum = replacementAlbum,
    replacementSource = replacementSource,
    replacementProviderName = replacementProviderName,
    replacementCoverUrl = replacementCoverUrl,
    replacementDurationMs = replacementDurationMs,
    replacementScore = replacementScore,
)

private fun PersistedSmartReplacementSelection.toDomain() = SmartReplacementSelection(
    replacementId = replacementId,
    replacementTitle = replacementTitle,
    replacementArtists = replacementArtists,
    replacementAlbum = replacementAlbum,
    replacementSource = replacementSource,
    replacementProviderName = replacementProviderName,
    replacementCoverUrl = replacementCoverUrl,
    replacementDurationMs = replacementDurationMs,
    replacementScore = replacementScore,
)

private inline fun <reified T : Enum<T>> String?.enumOr(default: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
