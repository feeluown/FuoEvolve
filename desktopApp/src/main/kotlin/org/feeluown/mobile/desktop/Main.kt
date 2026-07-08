package org.feeluown.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.feeluown.mobile.AppRoot
import org.feeluown.mobile.FuoPlayerController
import org.feeluown.mobile.NoOpDebugLogRepository
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderLoginMode
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

fun main() = application {
    val container = remember { DesktopAppContainer() }
    DisposableEffect(Unit) {
        onDispose { container.close() }
    }
    Window(
        onCloseRequest = {
            container.close()
            exitApplication()
        },
        title = "FuoEvolve",
    ) {
        DesktopApp(container)
    }
}

@Composable
private fun DesktopApp(container: DesktopAppContainer) {
    AppRoot(
        controller = container.controller,
        hasAudioPermission = true,
        appVersionInfo = "桌面开发版",
        onRequestAudioPermission = container::requestAudioPermission,
        onOpenProviderWebLogin = container::openProviderWebLogin,
        onLogoutProvider = container::logoutProvider,
        onShareText = ::copyShareText,
    )
}

private class DesktopAppContainer : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val providerRepository = DesktopFuoCoreBridge()
    private val localRepository = DesktopLocalMusicRepository()
    private val downloadRepository = DesktopDownloadRepository(providerRepository)
    private val playbackEngine = DesktopNativeAudioEngine(scope)
    private val settingsStore = DesktopAppSettingsStore()
    private val playbackQueueStore = DesktopPlaybackQueueStore()
    private val resourceCacheRepository = DesktopResourceCacheRepository()

    val controller = FuoPlayerController(
        providerRepository = providerRepository,
        localRepository = localRepository,
        downloadRepository = downloadRepository,
        playbackEngine = playbackEngine,
        settingsStore = settingsStore,
        playbackQueueStore = playbackQueueStore,
        resourceCacheRepository = resourceCacheRepository,
        debugLogRepository = NoOpDebugLogRepository,
        scope = scope,
    )

    fun requestAudioPermission() {
        controller.onLocalMusicPermissionChange(true)
        controller.refreshLocalMusic()
    }

    fun openProviderWebLogin(provider: ProviderInfo) {
        val url = provider.loginConfig?.loginUrl.orEmpty()
        if (url.isNotBlank()) {
            if (!openExternalBrowser(url)) {
                copyShareText(url)
            }
        }
        controller.openSettings(provider.providerId)
        controller.onProviderLoginModeChange(ProviderLoginMode.Cookie)
    }

    fun logoutProvider(provider: ProviderInfo) {
        controller.logoutProvider(provider.providerId)
    }

    override fun close() {
        providerRepository.close()
        playbackEngine.stop()
        scope.cancel()
    }
}

private fun copyShareText(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit()
            .systemClipboard
            .setContents(StringSelection(text), null)
    }
}

private fun openExternalBrowser(url: String): Boolean {
    val openedByDesktop = runCatching {
        if (!Desktop.isDesktopSupported()) return@runCatching false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching false
        desktop.browse(URI(url))
        true
    }.getOrDefault(false)
    if (openedByDesktop) return true

    return listOf(
        listOf("xdg-open", url),
        listOf("gio", "open", url),
    ).any { command ->
        runCatching {
            ProcessBuilder(command).start()
            true
        }.getOrDefault(false)
    }
}
