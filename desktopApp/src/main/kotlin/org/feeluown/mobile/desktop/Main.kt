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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Window as AwtWindow
import javax.swing.SwingUtilities
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopAppHost
import org.feeluown.mobile.DesktopExternalActivationSession
import org.feeluown.mobile.createDesktopPlaybackResumeStore
import org.feeluown.mobile.installDesktopAppLogger
import org.feeluown.mobile.installDesktopListeningHistorySinkFactory
import org.feeluown.mobile.installDesktopLocalMusicRepositoryFactory
import org.feeluown.mobile.installDesktopPlatformVideoControllerFactory
import org.feeluown.mobile.installDesktopPlaybackEngineFactory
import org.feeluown.mobile.installDesktopPlaybackSessionIntegrationFactory
import org.feeluown.mobile.installDesktopProviderCredentialStoreFactory
import org.feeluown.mobile.installDesktopTextFileDialogProviderFactory
import org.feeluown.mobile.installFallbackOAuthDeviceCodeAssistant

fun main(args: Array<String>) {
    installDesktopAppLogger()
    val activation = DesktopExternalActivationSession.open(args.toList()) ?: return
    registerDesktopFuoProtocolHandler()
    installDesktopListeningHistorySinkFactory(::createDesktopRuntimeListeningHistorySink)
    installDesktopPlaybackEngineFactory {
        configureLibMpvNumericLocale()
        createPersistentDesktopPlaybackEngine(
            delegate = DesktopMpvPlaybackEngine { listener -> LibMpvBackend(listener) },
            resumeStore = createDesktopPlaybackResumeStore(),
        )
    }
    installDesktopPlatformVideoControllerFactory {
        configureLibMpvNumericLocale()
        DesktopMpvVideoController()
    }
    installDesktopProviderCredentialStoreFactory(::createDesktopSecureProviderCredentialStore)
    installDesktopLocalMusicRepositoryFactory(::createDesktopRuntimeLocalMusicRepository)
    installDesktopTextFileDialogProviderFactory(::createDesktopNativeTextFileDialogProvider)
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
                activation.focusRequests.collect { showWindow() }
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
                        DesktopCloseBehavior.KeepVisible -> AppLogger.w(
                            "DesktopWindow",
                            "Close-to-tray ignored because no usable tray integration is available",
                        )
                    }
                },
                visible = windowVisible,
                title = "FuoEvolve",
                icon = painterResource("ic_launcher.png"),
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
