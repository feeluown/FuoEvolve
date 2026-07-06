package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    IosApp()
}

@Composable
private fun IosApp() {
    val container = remember { IosAppContainer() }
    AppRoot(
        controller = container.controller,
        hasAudioPermission = container.hasAudioPermission,
        onRequestAudioPermission = container::requestAudioPermission,
        onOpenProviderWebLogin = container::openProviderWebLogin,
        onLogoutProvider = container::logoutProvider,
    )
}

private class IosAppContainer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val providerRepository = IosFuoCoreBridge()
    private val localRepository = IosLocalMusicRepository()
    private val downloadRepository = IosDownloadRepository(providerRepository)
    private val playbackEngine = IosNativeAudioEngine(scope)
    private val settingsStore = IosAppSettingsStore()
    private val playbackQueueStore = IosPlaybackQueueStore()
    private val resourceCacheRepository = IosResourceCacheRepository()

    val controller = FuoPlayerController(
        providerRepository = providerRepository,
        localRepository = localRepository,
        downloadRepository = downloadRepository,
        playbackEngine = playbackEngine,
        settingsStore = settingsStore,
        playbackQueueStore = playbackQueueStore,
        resourceCacheRepository = resourceCacheRepository,
        scope = scope,
    )

    val hasAudioPermission: Boolean
        get() = localRepository.hasPermission

    fun requestAudioPermission() {
        localRepository.requestPermission {
            controller.onLocalMusicPermissionChange(true)
            controller.refreshLocalMusic()
        }
    }

    fun openProviderWebLogin(provider: ProviderInfo) {
        // Web cookie extraction is owned by the Swift host in the Xcode target.
        // Until that host callback is wired, users can still paste Cookie/Header values in settings.
        controller.onProviderLoginModeChange(ProviderLoginMode.Cookie)
    }

    fun logoutProvider(provider: ProviderInfo) {
        controller.logoutProvider(provider.providerId)
    }
}
