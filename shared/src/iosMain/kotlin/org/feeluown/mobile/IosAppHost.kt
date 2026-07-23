package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    audioRecognitionOutput: IosAudioRecognitionOutput,
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
        audioRecognitionOutput,
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
    audioRecognitionOutput: IosAudioRecognitionOutput,
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
            audioRecognitionOutput,
        )
    }
    AppRoot(
        appViewModel = container.appViewModel,
        hasAudioPermission = container.hasAudioPermission,
        onRequestAudioPermission = container::requestAudioPermission,
        hasMicrophonePermission = container.hasMicrophonePermission,
        onRequestMicrophonePermission = container::requestMicrophonePermission,
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
    private val audioRecognitionOutput: IosAudioRecognitionOutput,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val providerRepository = IosFuoCoreBridge(pythonRuntime, networkStatusOutput)
    private val localRepository = IosLocalMusicRepository(mediaLibraryOutput)
    private val downloadRepository = IosDownloadRepository(providerRepository, downloadOutput)
    private val playbackEngine = IosNativeAudioEngine(scope, audioOutput)
    private val settingsRepository = createIosAppSettingsRepository(scope)
    private val providerSessionRepository = DefaultProviderSessionRepository(providerRepository)
    private val navigator = AppNavigator()
    private val playbackQueueStore = IosPlaybackQueueStore()
    private val resourceCacheRepository = IosResourceCacheRepository()
    private val audioRecognitionRepository = IosAudioRecognitionRepository(audioRecognitionOutput)
    var hasMicrophonePermission by mutableStateOf(audioRecognitionOutput.hasPermission())
        private set

    val controller = FuoPlayerController(
        providerRepository = providerRepository,
        localRepository = localRepository,
        downloadRepository = downloadRepository,
        playbackEngine = playbackEngine,
        settingsRepository = settingsRepository,
        providerSessionRepository = providerSessionRepository,
        navigator = navigator,
        playbackQueueStore = playbackQueueStore,
        resourceCacheRepository = resourceCacheRepository,
        audioRecognitionRepository = audioRecognitionRepository,
        scope = scope,
    )

    val appViewModel = FuoAppViewModel(
        controller = controller,
        settingsRepository = settingsRepository,
        providerSessionRepository = providerSessionRepository,
        navigator = navigator,
    )

    val hasAudioPermission: Boolean
        get() = localRepository.hasPermission

    fun requestAudioPermission() {
        localRepository.requestPermission {
            controller.onLocalMusicPermissionChange(true)
            controller.refreshLocalMusic()
        }
    }

    fun requestMicrophonePermission() {
        audioRecognitionOutput.requestPermission { granted ->
            hasMicrophonePermission = granted
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
