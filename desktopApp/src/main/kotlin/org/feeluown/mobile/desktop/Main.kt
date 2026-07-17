package org.feeluown.mobile.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlin.math.roundToInt

fun main() = application {
    val container = remember { DesktopAppContainer() }
    val initialWindowBounds = remember { defaultDesktopWindowBounds() }
    val windowState = rememberWindowState(
        size = initialWindowBounds.size,
        position = initialWindowBounds.position,
    )
    var isMainWindowVisible by remember { mutableStateOf(true) }
    var isExitConfirmationVisible by remember { mutableStateOf(false) }
    val traySupported = isTraySupported
    val trayState = rememberTrayState()
    DisposableEffect(Unit) {
        onDispose { container.close() }
    }

    if (traySupported) {
        Tray(
            icon = painterResource("fuo-evolve.png"),
            state = trayState,
            tooltip = "FuoEvolve",
            onAction = { isMainWindowVisible = true },
            menu = {
                Item("显示主窗口", onClick = { isMainWindowVisible = true })
                Item("退出应用", onClick = { isExitConfirmationVisible = true })
            },
        )
    }

    Window(
        onCloseRequest = {
            if (traySupported) {
                isMainWindowVisible = false
            } else {
                isExitConfirmationVisible = true
            }
        },
        visible = isMainWindowVisible,
        state = windowState,
        title = "FuoEvolve",
    ) {
        DesktopApp(container)
    }

    if (isExitConfirmationVisible) {
        DialogWindow(
            onCloseRequest = { isExitConfirmationVisible = false },
            title = "确认退出",
            resizable = false,
        ) {
            AlertDialog(
                onDismissRequest = { isExitConfirmationVisible = false },
                title = { Text("退出 FuoEvolve？") },
                text = { Text("退出后将停止当前播放和后台任务。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            container.close()
                            exitApplication()
                        },
                    ) {
                        Text("退出")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { isExitConfirmationVisible = false }) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

private fun defaultDesktopWindowBounds(): DesktopWindowBounds {
    val displayBounds = runCatching {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val pointerLocation = MouseInfo.getPointerInfo()?.location
        val device = environment.screenDevices.firstOrNull { device ->
            pointerLocation != null && device.defaultConfiguration.bounds.contains(pointerLocation)
        } ?: environment.defaultScreenDevice
        device.defaultConfiguration.bounds
    }.getOrElse { java.awt.Rectangle(Dimension(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT)) }
    val width = (displayBounds.width * STARTUP_WINDOW_SCALE)
        .roundToInt()
        .coerceIn(MIN_WINDOW_WIDTH, MAX_WINDOW_WIDTH)
    val height = (displayBounds.height * STARTUP_WINDOW_SCALE)
        .roundToInt()
        .coerceIn(MIN_WINDOW_HEIGHT, MAX_WINDOW_HEIGHT)
    val x = displayBounds.x + (displayBounds.width - width) / 2
    val y = displayBounds.y + (displayBounds.height - height) / 2
    return DesktopWindowBounds(
        size = DpSize(width.dp, height.dp),
        position = WindowPosition.Absolute(x.dp, y.dp),
    )
}

private data class DesktopWindowBounds(
    val size: DpSize,
    val position: WindowPosition,
)

private const val STARTUP_WINDOW_SCALE = 0.8
private const val MIN_WINDOW_WIDTH = 960
private const val MIN_WINDOW_HEIGHT = 640
private const val MAX_WINDOW_WIDTH = 1440
private const val MAX_WINDOW_HEIGHT = 900
private const val DEFAULT_WINDOW_WIDTH = 1280
private const val DEFAULT_WINDOW_HEIGHT = 800

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
