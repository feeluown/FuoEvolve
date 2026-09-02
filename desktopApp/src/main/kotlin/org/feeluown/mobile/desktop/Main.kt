package org.feeluown.mobile.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Window as AwtWindow
import javax.swing.SwingUtilities
import org.feeluown.mobile.DesktopAppHost
import org.feeluown.mobile.installDesktopLocalMusicRepositoryFactory
import org.feeluown.mobile.installDesktopPlaybackEngineFactory
import org.feeluown.mobile.installDesktopPlaybackSessionIntegrationFactory
import org.feeluown.mobile.installDesktopProviderCredentialStoreFactory
import org.feeluown.mobile.installFallbackOAuthDeviceCodeAssistant

fun main() {
    installDesktopPlaybackEngineFactory { DesktopMpvPlaybackEngine() }
    installDesktopProviderCredentialStoreFactory { DesktopSecureProviderCredentialStore() }
    installDesktopLocalMusicRepositoryFactory { DesktopLocalMusicRepository() }
    installFallbackOAuthDeviceCodeAssistant(DesktopOAuthDeviceCodeAssistant())
    application {
        var windowVisible by remember { mutableStateOf(true) }
        var windowRef by remember { mutableStateOf<AwtWindow?>(null) }
        var trayController by remember { mutableStateOf<DesktopTrayController?>(null) }

        DisposableEffect(Unit) {
            val controller = createDesktopTrayController(
                onShow = {
                    windowVisible = true
                    SwingUtilities.invokeLater {
                        windowRef?.let { window ->
                            window.toFront()
                            window.requestFocus()
                        }
                    }
                },
                onExit = ::exitApplication,
            )
            trayController = controller
            onDispose {
                trayController = null
                controller.close()
            }
        }

        Window(
            onCloseRequest = {
                when (desktopCloseBehavior(trayController?.isAvailable == true)) {
                    DesktopCloseBehavior.HideToTray -> windowVisible = false
                    DesktopCloseBehavior.KeepVisible -> System.err.println(
                        "FuoEvolve: close-to-tray ignored because no usable tray integration is available",
                    )
                }
            },
            visible = windowVisible,
            title = "FuoEvolve",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            SideEffect {
                windowRef = window
            }
            DisposableEffect(window) {
                onDispose {
                    if (windowRef === window) windowRef = null
                }
            }
            installDesktopPlaybackSessionIntegrationFactory { playbackSession ->
                createDesktopSystemMediaSessionForWindow(playbackSession, window)
            }
            DesktopAppHost()
        }
    }
}
