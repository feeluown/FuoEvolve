package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class LyricFontSize(
    val label: String,
) {
    Small("小"),
    Medium("中"),
    Large("大"),
}

@Serializable
enum class ThemeMode(
    val label: String,
) {
    System("跟随系统"),
    Light("亮色"),
    Dark("暗色"),
}

@Serializable
enum class ThemeColorScheme(
    val label: String,
) {
    Dynamic("系统动态"),
    ExpressiveDefault("Expressive 默认"),
    FuoGreen("青绿"),
    OceanBlue("海蓝"),
    Violet("紫罗兰"),
    Rose("蔷薇"),
    Amber("琥珀"),
}

@Serializable
enum class ThemePaletteStyle(
    val label: String,
) {
    TonalSpot("Tonal Spot"),
    Neutral("Neutral"),
    Vibrant("Vibrant"),
    Expressive("Expressive"),
    Rainbow("Rainbow"),
    FruitSalad("FruitSalad"),
    Monochrome("Monochrome"),
    Fidelity("Fidelity"),
    Content("Content"),
}

@Serializable
enum class ThemeColorSpec(
    val label: String,
) {
    Material3_2021("Material 3 (2021)"),
    Expressive_2025("Expressive (2025)"),
}

@Serializable
data class AppSettings(
    val onboardingCompleted: Boolean = false,
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
    val searchProviderIds: Set<String> = emptySet(),
    val recommendProviderIds: Set<String> = emptySet(),
    val exploreProviderIds: Set<String> = emptySet(),
    val mineProviderIds: Set<String> = emptySet(),
    val audioCacheLimitMb: Int = DEFAULT_AUDIO_CACHE_LIMIT_MB,
    val imageCacheLimitMb: Int = DEFAULT_IMAGE_CACHE_LIMIT_MB,
    val downloadParallelism: Int = DEFAULT_DOWNLOAD_PARALLELISM,
    val wifiAudioQualityPolicy: AudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY,
    val cellularAudioQualityPolicy: AudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY,
    val unavailablePlaybackPolicy: UnavailablePlaybackPolicy = DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
    val smartReplacementProviderIds: Set<String> = emptySet(),
    val smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    val smartReplacementSelections: Map<String, SmartReplacementSelection> = emptyMap(),
    val pauseOnOtherAppPlayback: Boolean = DEFAULT_PAUSE_ON_OTHER_APP_PLAYBACK,
    val lyricFontSize: LyricFontSize = LyricFontSize.Small,
    val statusBarLyricsEnabled: Boolean = false,
    val bydInstrumentLyricsEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val themeColorScheme: ThemeColorScheme = ThemeColorScheme.Dynamic,
    val themePaletteStyle: ThemePaletteStyle = ThemePaletteStyle.Expressive,
    val themeColorSpec: ThemeColorSpec = ThemeColorSpec.Expressive_2025,
    val dynamicCoverColorEnabled: Boolean = false,
    val playlistPlaybackStatsVersion: Int = 0,
    val playlistPlaybackStats: Map<String, PlaylistPlaybackStat> = emptyMap(),
)

@Serializable
data class PlaylistPlaybackStat(
    val playCount: Long = 0,
    val lastPlayedAtMillis: Long = 0,
)

data class YtMusicOAuthFlowUiState(
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlWithCode: String,
    val statusMessage: String = "请在浏览器中完成授权",
    val browserOpened: Boolean = false,
)

const val DEFAULT_AUDIO_CACHE_LIMIT_MB = 512
const val DEFAULT_IMAGE_CACHE_LIMIT_MB = 128
const val DEFAULT_DOWNLOAD_PARALLELISM = 2
val DEFAULT_ENABLED_PROVIDER_IDS = setOf("netease")
val DEFAULT_PROVIDER_ORDER_IDS = listOf("netease", "qqmusic", "bilibili", "ytmusic")

data class SettingsState(
    val isLoaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val errorMessage: String? = null,
)

interface AppSettingsRepository {
    val state: StateFlow<SettingsState>
    suspend fun awaitSettings(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun updateThemePaletteStyle(value: ThemePaletteStyle) {
        update { settings -> settings.copy(themePaletteStyle = value) }
    }
    suspend fun updateThemeColorSpec(value: ThemeColorSpec) {
        update { settings -> settings.copy(themeColorSpec = value) }
    }
}

class InMemoryAppSettingsRepository(
    initialSettings: AppSettings = AppSettings(),
) : AppSettingsRepository {
    private val mutableState = MutableStateFlow(SettingsState(isLoaded = true, settings = initialSettings))
    override val state: StateFlow<SettingsState> = mutableState

    override suspend fun awaitSettings(): AppSettings = mutableState.value.settings

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = mutableState.value.settings
        val transformed = transform(current)
        mutableState.value = SettingsState(
            isLoaded = true,
            settings = transformed.copy(
                themePaletteStyle = current.themePaletteStyle,
                themeColorSpec = current.themeColorSpec,
            ),
        )
    }

    override suspend fun updateThemePaletteStyle(value: ThemePaletteStyle) {
        val current = mutableState.value.settings
        mutableState.value = SettingsState(
            isLoaded = true,
            settings = current.copy(themePaletteStyle = value),
        )
    }

    override suspend fun updateThemeColorSpec(value: ThemeColorSpec) {
        val current = mutableState.value.settings
        mutableState.value = SettingsState(
            isLoaded = true,
            settings = current.copy(themeColorSpec = value),
        )
    }
}
