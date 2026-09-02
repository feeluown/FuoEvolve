package org.feeluown.mobile.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Window as AwtWindow
import javax.swing.SwingUtilities
import org.feeluown.mobile.DesktopAppHost
import org.feeluown.mobile.DesktopExternalActivationSession
import org.feeluown.mobile.createDesktopPlaybackResumeStore
import org.feeluown.mobile.installDesktopDebugLogCapture
import org.feeluown.mobile.installDesktopListeningHistorySinkFactory
import org.feeluown.mobile.installDesktopLocalMusicRepositoryFactory
import org.feeluown.mobile.installDesktopPlaybackEngineFactory
import org.feeluown.mobile.installDesktopPlaybackSessionIntegrationFactory
import org.feeluown.mobile.installDesktopProviderCredentialStoreFactory
import org.feeluown.mobile.installFallbackOAuthDeviceCodeAssistant
import org.feeluown.mobile.persistence.listening.DesktopListeningHistoryDriverFactory
import org.feeluown.mobile.persistence.listening.SqlDelightListeningHistoryStore

fun main(args: Array<String>) {
    installDesktopDebugLogCapture()
    val activation = DesktopExternalActivationSession.open(args.toList()) ?: return
    registerDesktopFuoProtocolHandler()
    installDesktopListeningHistorySinkFactory { databasePath ->
        SqlDelightListeningHistoryStore(
            DesktopListeningHistoryDriverFactory(databasePath),
        )
    }
    installDesktopPlaybackEngineFactory {
        PersistentDesktopPlaybackEngine(
            delegate = DesktopMpvPlaybackEngine(),
            resumeStore = createDesktopPlaybackResumeStore(),
        )
    }
    installDesktopProviderCredentialStoreFactory { DesktopSecureProviderCredentialStore() }
    installDesktopLocalMusicRepositoryFactory { DesktopLocalMusicRepository() }
    installFallbackOAuthDeviceCodeAssistant(DesktopOAuthDeviceCodeAssistant())

    try {
        application {
            var windowVisible by remember { mutableStateOf(true) }
            var windowRef by remember { mutableStateOf<AwtWindow?>(null) }
            var trayController by remember { mutableStateOf<DesktopTrayController?>(null) }

            fun showWindow() {
                windowVisible = true
                SwingUtilities.invokeLater {
                    windowRef?.let { window ->
                        window.toFront()
                        window.requestFocus()
                    }
                }
            }

            LaunchedEffect(activation) {
                activation.inputs.collect { showWindow() }
            }

            DisposableEffect(Unit) {
                val controller = createDesktopTrayController(
                    onShow = ::showWindow,
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

                // Compose's desktop UriHandler is synchronous. On Linux the OS browser launcher may
                // block while xdg-open/desktop integration resolves the target application, so never
                // invoke it directly from the Compose UI dispatcher.
                val platformUriHandler = LocalUriHandler.current
                val nonBlockingUriHandler = remember(platformUriHandler) {
                    DesktopNonBlockingUriHandler(platformUriHandler)
                }
                DisposableEffect(nonBlockingUriHandler) {
                    onDispose(nonBlockingUriHandler::close)
                }
                CompositionLocalProvider(LocalUriHandler provides nonBlockingUriHandler) {
                    DesktopAppHost(externalInputs = activation.inputs)
                }
            }
        }
    } finally {
        activation.close()
    }
}
