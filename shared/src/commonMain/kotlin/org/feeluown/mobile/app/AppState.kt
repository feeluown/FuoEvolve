package org.feeluown.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.feeluown.mobile.playback.api.PlaybackSession

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable data object Home : AppRoute
    @Serializable data object Search : AppRoute
    @Serializable data object AudioRecognition : AppRoute
    @Serializable data object Feature : AppRoute
    @Serializable data class FeatureDetail(val feature: NavigationFeature) : AppRoute
    @Serializable data object Track : AppRoute
    @Serializable data class TrackDetail(val track: NavigationTrack) : AppRoute
    @Serializable data object Video : AppRoute
    @Serializable data class VideoDetail(val video: NavigationVideo) : AppRoute
    @Serializable data object Playlist : AppRoute
    @Serializable data class PlaylistDetail(val playlist: NavigationPlaylist, val category: String? = null) : AppRoute
    @Serializable data object LocalPlaylist : AppRoute
    @Serializable data object LocalMusicCollection : AppRoute
    @Serializable data object MediaItem : AppRoute
    @Serializable data class MediaItemDetail(val item: NavigationMediaItem) : AppRoute
    @Serializable data object Settings : AppRoute
    @Serializable data object DebugLogs : AppRoute
    @Serializable data object DownloadManager : AppRoute
}

private fun AppRoute.routeKind(): AppRoute = when (this) {
    is AppRoute.FeatureDetail -> AppRoute.Feature
    is AppRoute.TrackDetail -> AppRoute.Track
    is AppRoute.VideoDetail -> AppRoute.Video
    is AppRoute.PlaylistDetail -> AppRoute.Playlist
    is AppRoute.MediaItemDetail -> AppRoute.MediaItem
    else -> this
}

class AppNavigator {
    private val mutableBackStack = MutableStateFlow<List<AppRoute>>(listOf(AppRoute.Home))
    val backStack: StateFlow<List<AppRoute>> = mutableBackStack
    val currentRoute: AppRoute get() = mutableBackStack.value.last().routeKind()
    val currentEntry: AppRoute get() = mutableBackStack.value.last()

    fun contains(route: AppRoute): Boolean = mutableBackStack.value.any { it == route || it.routeKind() == route }
    fun containsWhere(predicate: (AppRoute) -> Boolean): Boolean = mutableBackStack.value.any(predicate)

    fun navigate(route: AppRoute) {
        if (route == AppRoute.Home) {
            mutableBackStack.value = listOf(AppRoute.Home)
        } else if (currentEntry != route) {
            mutableBackStack.value = mutableBackStack.value + route
        }
    }

    fun pop(route: AppRoute): Boolean {
        val stack = mutableBackStack.value
        val index = stack.indexOfLast { it == route || it.routeKind() == route }
        if (index <= 0) return false
        mutableBackStack.value = stack.take(index).ifEmpty { listOf(AppRoute.Home) }
        return true
    }

    fun popWhere(predicate: (AppRoute) -> Boolean): Boolean {
        val stack = mutableBackStack.value
        val index = stack.indexOfLast(predicate)
        if (index <= 0) return false
        mutableBackStack.value = stack.take(index).ifEmpty { listOf(AppRoute.Home) }
        return true
    }

    fun pop(): Boolean {
        val stack = mutableBackStack.value
        if (stack.size <= 1) return false
        mutableBackStack.value = stack.dropLast(1)
        return true
    }

    fun remove(routes: Set<AppRoute>) {
        val filtered = mutableBackStack.value.filterNot { entry ->
            entry != AppRoute.Home && (entry in routes || entry.routeKind() in routes)
        }
        mutableBackStack.value = filtered.ifEmpty { listOf(AppRoute.Home) }
    }

    fun removeWhere(predicate: (AppRoute) -> Boolean) {
        val filtered = mutableBackStack.value.filterNot { it != AppRoute.Home && predicate(it) }
        mutableBackStack.value = filtered.ifEmpty { listOf(AppRoute.Home) }
    }
}

data class AppUiState(
    val settings: SettingsState = SettingsState(),
    val providerSessions: ProviderSessionState = ProviderSessionState(),
    val backStack: List<AppRoute> = listOf(AppRoute.Home),
) {
    val isInitialized: Boolean get() = settings.isLoaded
    val onboardingCompleted: Boolean get() = settings.settings.onboardingCompleted
}

sealed interface AppIntent {
    data object NavigateBack : AppIntent
    data class UpdateSettings(val transform: (AppSettings) -> AppSettings) : AppIntent
    data class UpdateThemePaletteStyle(val value: ThemePaletteStyle) : AppIntent
    data class UpdateThemeColorSpec(val value: ThemeColorSpec) : AppIntent
}

class FuoAppViewModel(
    val playbackSession: PlaybackSession,
    val playbackNavigationPort: PlaybackNavigationPort,
    val playbackPresentationPort: PlaybackPresentationPort,
    val playbackQueueUiPort: PlaybackQueueUiPort,
    val playbackSleepTimerPort: PlaybackSleepTimerPort,
    val downloadActionPort: DownloadActionPort,
    val playlistActionPort: PlaylistActionPort,
    val providerTrackActionPort: ProviderTrackActionPort,
    val localMusicActionPort: LocalMusicActionPort,
    val replacementActionPort: ReplacementActionPort,
    val debugLogFeatureController: DebugLogFeatureController,
    val providerCatalogFeatureController: ProviderCatalogFeatureController,
    val providerAuthFeatureController: ProviderAuthFeatureController,
    val settingsFeatureController: SettingsFeatureController,
    val onboardingFeatureController: OnboardingFeatureController? = null,
    val providerDetailOwners: ProviderDetailOwners,
    val localMusicFeatureController: LocalMusicFeatureController,
    val localPlaylistFeatureController: LocalPlaylistFeatureController,
    val homeFeatureController: HomeFeatureController,
    val sharedResourceActionPort: SharedResourceActionPort,
    private val searchController: SearchFeatureController,
    private val recognitionController: RecognitionFeatureController,
    internal val searchAppPort: SearchAppPort,
    internal val recognitionAppPort: RecognitionAppPort,
    private val settingsRepository: AppSettingsRepository,
    providerSessionRepository: ProviderSessionRepository,
    private val navigator: AppNavigator,
) : ViewModel() {
    val searchUiState: StateFlow<SearchUiState> = searchController.uiState
    val recognitionUiState: StateFlow<RecognitionUiState> = recognitionController.uiState
    private val mutableAppFeedback = MutableStateFlow<String?>(null)
    val appFeedback: StateFlow<String?> = mutableAppFeedback

    val playbackUiPort = PlaybackUiGraph(
        navigation = playbackNavigationPort,
        presentation = playbackPresentationPort,
        queue = playbackQueueUiPort,
        sleepTimer = playbackSleepTimerPort,
        downloads = downloadActionPort,
        playlists = playlistActionPort,
        providerTrackActions = providerTrackActionPort,
        localMusicActions = localMusicActionPort,
        replacement = replacementActionPort,
    )

    val providerDetailUiGraph = ProviderDetailUiGraph(
        owners = providerDetailOwners,
        playbackQueue = playbackQueueUiPort,
        downloads = downloadActionPort,
        playlists = playlistActionPort,
        providerTrackActions = providerTrackActionPort,
    )

    val homeFeatureUiGraph = HomeFeatureUiGraph(
        home = homeFeatureController,
        providerCatalog = providerCatalogFeatureController,
        playbackQueue = playbackQueueUiPort,
        downloads = downloadActionPort,
        playlists = playlistActionPort,
        providerTrackActions = providerTrackActionPort,
        localPlaylist = localPlaylistFeatureController,
        localMusic = localMusicFeatureController,
    )

    val uiState: StateFlow<AppUiState> = combine(
        settingsRepository.state,
        providerSessionRepository.state,
        navigator.backStack,
    ) { settings, sessions, backStack -> AppUiState(settings, sessions, backStack) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppUiState(
                settings = settingsRepository.state.value,
                providerSessions = providerSessionRepository.state.value,
                backStack = navigator.backStack.value,
            ),
        )

    fun dispatchSearch(action: SearchAction) = searchController.dispatch(action)
    fun searchRecognizedSong(song: RecognizedSong) = searchController.searchRecognizedSong(song)
    fun dispatchRecognition(action: RecognitionAction) = recognitionController.dispatch(action)

    fun openRecognition() {
        recognitionController.dispatch(RecognitionAction.Reset)
        navigator.navigate(AppRoute.AudioRecognition)
    }

    fun closeRecognition() {
        recognitionController.dispatch(RecognitionAction.Close)
        navigator.pop(AppRoute.AudioRecognition)
    }

    fun openDebugLogs() {
        if (debugLogFeatureController.isAvailable) navigator.navigate(AppRoute.DebugLogs)
    }

    fun closeDebugLogs() { navigator.pop(AppRoute.DebugLogs) }
    fun openDownloadManager() { navigator.navigate(AppRoute.DownloadManager) }
    fun closeDownloadManager() { navigator.pop(AppRoute.DownloadManager) }

    fun showFeedback(message: String) {
        if (message.isNotBlank()) mutableAppFeedback.value = message
    }

    fun dismissFeedback(message: String) {
        if (mutableAppFeedback.value == message) mutableAppFeedback.value = null
    }

    fun onMicrophonePermissionChange(hasPermission: Boolean) {
        if (hasPermission && navigator.contains(AppRoute.AudioRecognition) && recognitionController.uiState.value == RecognitionUiState.Idle) {
            recognitionController.dispatch(RecognitionAction.Start)
        }
    }

    fun onAppBackgrounded() { recognitionController.dispatch(RecognitionAction.CancelIfInProgress) }

    fun dispatch(intent: AppIntent) {
        when (intent) {
            AppIntent.NavigateBack -> navigateBack()
            is AppIntent.UpdateSettings -> viewModelScope.launch { settingsRepository.update(intent.transform) }
            is AppIntent.UpdateThemePaletteStyle -> viewModelScope.launch { settingsRepository.updateThemePaletteStyle(intent.value) }
            is AppIntent.UpdateThemeColorSpec -> viewModelScope.launch { settingsRepository.updateThemeColorSpec(intent.value) }
        }
    }

    private fun navigateBack() {
        when {
            playlistActionPort.targetPickerState.value.track != null -> playlistActionPort.closePlaylistTargetPicker()
            providerTrackActionPort.artistTargetPickerState.value.track != null -> providerTrackActionPort.closeArtistTargetPicker()
            localMusicFeatureController.uiState.value.metadataEditorTrack != null -> localMusicFeatureController.closeMetadataEditor()
            playbackNavigationPort.isQueueOpen -> playbackNavigationPort.toggleQueue()
            playbackNavigationPort.isFullPlayerOpen -> playbackNavigationPort.closeFullPlayer()
            providerDetailOwners.video.uiState.value.isFullscreen -> providerDetailOwners.video.toggleFullscreen()
            else -> when (navigator.currentEntry) {
                AppRoute.Home -> Unit
                AppRoute.Search -> searchAppPort.closeSearch()
                AppRoute.AudioRecognition -> closeRecognition()
                AppRoute.Settings -> settingsFeatureController.close()
                AppRoute.DebugLogs -> closeDebugLogs()
                AppRoute.DownloadManager -> closeDownloadManager()
                AppRoute.LocalPlaylist -> localPlaylistFeatureController.close()
                AppRoute.LocalMusicCollection -> localMusicFeatureController.closeCollection()
                is AppRoute.FeatureDetail -> providerDetailOwners.feature.close()
                is AppRoute.PlaylistDetail -> providerDetailOwners.playlist.close()
                is AppRoute.TrackDetail -> providerDetailOwners.track.close()
                is AppRoute.VideoDetail -> providerDetailOwners.video.close()
                is AppRoute.MediaItemDetail -> providerDetailOwners.mediaItem.close()
                AppRoute.Feature,
                AppRoute.Playlist,
                AppRoute.Track,
                AppRoute.Video,
                AppRoute.MediaItem -> navigator.pop()
            }
        }
    }
}
