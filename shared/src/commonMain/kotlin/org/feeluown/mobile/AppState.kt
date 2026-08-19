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

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Search : AppRoute

    @Serializable
    data object AudioRecognition : AppRoute

    @Serializable
    data class Feature(val feature: NavigationFeature) : AppRoute

    @Serializable
    data class Track(val track: NavigationTrack) : AppRoute

    @Serializable
    data class Video(val video: NavigationVideo) : AppRoute

    @Serializable
    data class Playlist(
        val playlist: NavigationPlaylist,
        val category: String? = null,
    ) : AppRoute

    @Serializable
    data object LocalPlaylist : AppRoute

    @Serializable
    data object LocalMusicCollection : AppRoute

    @Serializable
    data class MediaItem(val item: NavigationMediaItem) : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object DebugLogs : AppRoute

    @Serializable
    data object DownloadManager : AppRoute
}

class AppNavigator {
    private val mutableBackStack = MutableStateFlow<List<AppRoute>>(listOf(AppRoute.Home))

    val backStack: StateFlow<List<AppRoute>> = mutableBackStack

    val currentRoute: AppRoute
        get() = mutableBackStack.value.last()

    fun contains(route: AppRoute): Boolean = route in mutableBackStack.value

    fun containsWhere(predicate: (AppRoute) -> Boolean): Boolean = mutableBackStack.value.any(predicate)

    fun navigate(route: AppRoute) {
        if (route == AppRoute.Home) {
            mutableBackStack.value = listOf(AppRoute.Home)
            return
        }
        if (currentRoute != route) {
            mutableBackStack.value = mutableBackStack.value + route
        }
    }

    fun pop(route: AppRoute): Boolean {
        val stack = mutableBackStack.value
        val index = stack.indexOfLast { it == route }
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
        val filtered = mutableBackStack.value.filterNot { it != AppRoute.Home && it in routes }
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
)

sealed interface AppIntent {
    data object NavigateBack : AppIntent
    data class UpdateSettings(val transform: (AppSettings) -> AppSettings) : AppIntent
    data class UpdateThemePaletteStyle(val value: ThemePaletteStyle) : AppIntent
    data class UpdateThemeColorSpec(val value: ThemeColorSpec) : AppIntent
}

/** 应用壳层 ViewModel：组合设置、登录会话和导航三个全局单一事实源。 */
class FuoAppViewModel(
    val controller: FuoPlayerController,
    private val settingsRepository: AppSettingsRepository,
    providerSessionRepository: ProviderSessionRepository,
    private val navigator: AppNavigator,
) : ViewModel() {
    val uiState: StateFlow<AppUiState> = combine(
        settingsRepository.state,
        providerSessionRepository.state,
        navigator.backStack,
    ) { settings, sessions, backStack ->
        AppUiState(settings, sessions, backStack)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppUiState(
            settings = settingsRepository.state.value,
            providerSessions = providerSessionRepository.state.value,
            backStack = navigator.backStack.value,
        ),
    )

    fun dispatch(intent: AppIntent) {
        when (intent) {
            AppIntent.NavigateBack -> controller.navigateBack()
            is AppIntent.UpdateSettings -> viewModelScope.launch {
                settingsRepository.update(intent.transform)
            }
            is AppIntent.UpdateThemePaletteStyle -> viewModelScope.launch {
                settingsRepository.updateThemePaletteStyle(intent.value)
            }
            is AppIntent.UpdateThemeColorSpec -> viewModelScope.launch {
                settingsRepository.updateThemeColorSpec(intent.value)
            }
        }
    }
}
