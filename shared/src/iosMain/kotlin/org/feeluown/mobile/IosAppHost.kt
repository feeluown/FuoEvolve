package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIViewController

fun MainViewController(
    pythonRuntime: IosPythonRuntime,
    audioOutput: IosAudioOutput,
    videoOutput: IosVideoOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    webLoginOutput: IosWebLoginOutput,
    shareOutput: IosShareOutput,
    networkStatusOutput: IosNetworkStatusOutput,
): UIViewController = ComposeUIViewController {
    IosApp(
        pythonRuntime,
        audioOutput,
        videoOutput,
        mediaLibraryOutput,
        downloadOutput,
        webLoginOutput,
        shareOutput,
        networkStatusOutput,
    )
}

@Composable
private fun IosApp(
    pythonRuntime: IosPythonRuntime,
    audioOutput: IosAudioOutput,
    videoOutput: IosVideoOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    webLoginOutput: IosWebLoginOutput,
    shareOutput: IosShareOutput,
    networkStatusOutput: IosNetworkStatusOutput,
) {
    IosVideoOutputHolder.output = videoOutput
    val container = remember {
        IosAppContainer(
            pythonRuntime,
            audioOutput,
            mediaLibraryOutput,
            downloadOutput,
            webLoginOutput,
            networkStatusOutput,
        )
    }
    AppRoot(
        controller = container.controller,
        hasAudioPermission = container.hasAudioPermission,
        onRequestAudioPermission = container::requestAudioPermission,
        onOpenProviderWebLogin = container::openProviderWebLogin,
        onLogoutProvider = container::logoutProvider,
        onShareText = shareOutput::share,
    )
}

private class IosAppContainer(
    pythonRuntime: IosPythonRuntime,
    audioOutput: IosAudioOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    private val webLoginOutput: IosWebLoginOutput,
    networkStatusOutput: IosNetworkStatusOutput,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val providerRepository = IosFuoCoreBridge(pythonRuntime, networkStatusOutput)
    private val localRepository = IosLocalMusicRepository(mediaLibraryOutput)
    private val downloadRepository = IosDownloadRepository(providerRepository, downloadOutput)
    private val playbackEngine = IosNativeAudioEngine(scope, audioOutput, providerRepository)
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
        val loginConfig = provider.loginConfig ?: return
        val groupsJson = loginConfig.cookieKeyGroups.joinToString(prefix = "[", postfix = "]") { group ->
            group.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        }
        webLoginOutput.open(
            provider.providerId,
            provider.providerName,
            loginConfig.loginUrl,
            groupsJson,
        ) { cookiesJson ->
            if (!cookiesJson.isNullOrBlank()) {
                controller.loginProviderWithCookies(provider.providerId, cookiesJson)
            }
        }
    }

    fun logoutProvider(provider: ProviderInfo) {
        webLoginOutput.clear()
        controller.logoutProvider(provider.providerId)
    }
}
