package org.feeluown.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable data object Home : AppRoute
    @Serializable data object PlaybackHistory : AppRoute
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
    val isInitialized: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val themeColorScheme: ThemeColorScheme = ThemeColorScheme.Dynamic,
    val themePaletteStyle: ThemePaletteStyle = ThemePaletteStyle.Expressive,
    val themeColorSpec: ThemeColorSpec = ThemeColorSpec.Expressive_2025,
    val backStack: List<AppRoute> = listOf(AppRoute.Home),
)

private fun appUiState(settingsState: SettingsState, backStack: List<AppRoute>): AppUiState {
    val settings = settingsState.settings
    return AppUiState(
        isInitialized = settingsState.isLoaded,
        onboardingCompleted = settings.onboardingCompleted,
        themeMode = settings.themeMode,
        themeColorScheme = settings.themeColorScheme,
        themePaletteStyle = settings.themePaletteStyle,
        themeColorSpec = settings.themeColorSpec,
        backStack = backStack,
    )
}

class FuoAppViewModel(
    private val settingsRepository: AppSettingsRepository,
    private val navigator: AppNavigator,
    private val recognitionController: RecognitionFeatureController,
    private val backCoordinator: AppBackCoordinator,
) : ViewModel() {
    private val mutableAppFeedback = MutableStateFlow<String?>(null)
    val appFeedback: StateFlow<String?> = mutableAppFeedback

    val uiState: StateFlow<AppUiState> = combine(
        settingsRepository.state,
        navigator.backStack,
        ::appUiState,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = appUiState(settingsRepository.state.value, navigator.backStack.value),
    )

    val handlesTransientBack: StateFlow<Boolean> = backCoordinator.hasTransientBack.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = backCoordinator.hasTransientBackNow,
    )

    fun openRecognition() {
        recognitionController.dispatch(RecognitionAction.Reset)
        navigator.navigate(AppRoute.AudioRecognition)
    }

    fun openPlaybackHistory() {
        navigator.navigate(AppRoute.PlaybackHistory)
    }

    fun closeRecognition() {
        recognitionController.dispatch(RecognitionAction.Close)
        navigator.pop(AppRoute.AudioRecognition)
    }

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

    fun onAppBackgrounded() {
        recognitionController.dispatch(RecognitionAction.CancelIfInProgress)
    }

    fun onBack(): Boolean = backCoordinator.onBack()
}
