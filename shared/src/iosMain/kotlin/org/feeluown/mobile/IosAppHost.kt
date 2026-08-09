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

internal fun handleIosLocalPlaylistImportResult(
    fileName: String?,
    content: String?,
    onImport: (String, String) -> Unit,
    onReadFailure: () -> Unit,
) {
    if (fileName == null && content == null) return
    val validFileName = fileName?.takeIf { it.isNotBlank() }
    val validContent = content?.takeIf { it.isNotBlank() }
    if (validFileName != null && validContent != null) {
        onImport(validFileName, validContent)
    } else {
        onReadFailure()
    }
}

fun MainViewController(
    audioOutput: IosAudioOutput,
    videoOutput: IosVideoOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    webLoginOutput: IosWebLoginOutput,
    shareOutput: IosShareOutput,
    localPlaylistFileOutput: IosLocalPlaylistFileOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
): UIViewController = ComposeUIViewController {
    IosApp(
        audioOutput,
        videoOutput,
        mediaLibraryOutput,
        downloadOutput,
        webLoginOutput,
        shareOutput,
        localPlaylistFileOutput,
        networkStatusOutput,
        audioRecognitionOutput,
        oauthDeviceCodeOutput,
    )
}

@Composable
private fun IosApp(
    audioOutput: IosAudioOutput,
    videoOutput: IosVideoOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    webLoginOutput: IosWebLoginOutput,
    shareOutput: IosShareOutput,
    localPlaylistFileOutput: IosLocalPlaylistFileOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
) {
    IosVideoOutputHolder.output = videoOutput
    val container = remember {
        IosAppContainer(
            audioOutput,
            mediaLibraryOutput,
            downloadOutput,
            webLoginOutput,
            networkStatusOutput,
            audioRecognitionOutput,
            oauthDeviceCodeOutput,
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
        onImportLocalPlaylistFile = {
            localPlaylistFileOutput.importFile { fileName, content ->
                handleIosLocalPlaylistImportResult(
                    fileName = fileName,
                    content = content,
                    onImport = container.controller::prepareLocalPlaylistImport,
                    onReadFailure = { container.controller.showMessage("无法读取本地歌单文件") },
                )
            }
        },
        onExportLocalPlaylistFile = localPlaylistFileOutput::exportFile,
        onShareLocalPlaylistFile = localPlaylistFileOutput::shareFile,
        onShareText = shareOutput::share,
    )
}

private class IosAppContainer(
    audioOutput: IosAudioOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    private val webLoginOutput: IosWebLoginOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    private val audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val providerRepository = createFuoProviderRepository(
        credentials = IosProviderCredentialStore(),
        persistentCache = IosProviderCacheStore(),
        isCellularConnection = networkStatusOutput::isCellularConnection,
    )
    private val localRepository = IosLocalMusicRepository(mediaLibraryOutput)
    private val localPlaylistRepository = IosLocalPlaylistRepository()
    private val downloadRepository = IosDownloadRepository(providerRepository, downloadOutput)
    private val settingsRepository = createIosAppSettingsRepository(scope)
    private val playbackEngine = IosNativeAudioEngine(scope, audioOutput, settingsRepository)
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
        localPlaylistRepository = localPlaylistRepository,
        downloadRepository = downloadRepository,
        playbackEngine = playbackEngine,
        settingsRepository = settingsRepository,
        providerSessionRepository = providerSessionRepository,
        navigator = navigator,
        playbackQueueStore = playbackQueueStore,
        resourceCacheRepository = resourceCacheRepository,
        audioRecognitionRepository = audioRecognitionRepository,
        oauthDeviceCodeAssistant = IosOAuthDeviceCodeAssistant(oauthDeviceCodeOutput),
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
            controller.onMicrophonePermissionChange(granted)
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
