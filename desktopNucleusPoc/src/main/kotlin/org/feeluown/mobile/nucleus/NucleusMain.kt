package org.feeluown.mobile.nucleus

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.composenativetray.tray.api.Tray
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopAppHost
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.createDesktopPlaybackResumeStore
import org.feeluown.mobile.desktop.DesktopMpvPlaybackEngine
import org.feeluown.mobile.desktop.createDesktopNativeTextFileDialogProvider
import org.feeluown.mobile.desktop.createDesktopRuntimeListeningHistorySink
import org.feeluown.mobile.desktop.createDesktopRuntimeLocalMusicRepository
import org.feeluown.mobile.desktop.createDesktopSecureProviderCredentialStore
import org.feeluown.mobile.desktop.createPersistentDesktopPlaybackEngine
import org.feeluown.mobile.installDesktopAppLogger
import org.feeluown.mobile.installDesktopListeningHistorySinkFactory
import org.feeluown.mobile.installDesktopLocalMusicRepositoryFactory
import org.feeluown.mobile.installDesktopPlaybackEngineFactory
import org.feeluown.mobile.installDesktopPlaybackSessionIntegrationFactory
import org.feeluown.mobile.installDesktopProviderCredentialStoreFactory
import org.feeluown.mobile.installDesktopTextFileDialogProviderFactory
import org.feeluown.mobile.installFallbackOAuthDeviceCodeAssistant

private const val SMOKE_ENV = "FUOEVOLVE_NUCLEUS_POC_SMOKE"
private const val PLAYBACK_SMOKE_ENV = "FUOEVOLVE_NUCLEUS_PLAYBACK_SMOKE"
private const val LINUX_TRAY_PROBE_TIMEOUT_SECONDS = 1L

@Suppress("DEPRECATION")
fun main(args: Array<String>) {
    configurePackagedNativeRuntime()
    installDesktopAppLogger()

    val activation = NucleusExternalActivation.open(args) ?: return
    val oauthDeviceCodeAssistant = NucleusOAuthDeviceCodeAssistant()
    installFallbackOAuthDeviceCodeAssistant(oauthDeviceCodeAssistant)
    installDesktopProviderCredentialStoreFactory(::createDesktopSecureProviderCredentialStore)
    installDesktopListeningHistorySinkFactory(::createDesktopRuntimeListeningHistorySink)
    installDesktopLocalMusicRepositoryFactory(::createDesktopRuntimeLocalMusicRepository)
    installDesktopTextFileDialogProviderFactory {
        createDesktopNativeTextFileDialogProvider(requireNativeLinuxPortal = true)
    }
    installDesktopPlaybackEngineFactory {
        createPersistentDesktopPlaybackEngine(
            delegate = DesktopMpvPlaybackEngine { listener -> JniMpvBackend(listener) },
            resumeStore = createDesktopPlaybackResumeStore(),
        )
    }

    val smokeMode = System.getenv(SMOKE_ENV) == "1"
    val playbackSmokeFile = System.getenv(PLAYBACK_SMOKE_ENV)
        ?.takeIf(String::isNotBlank)
        ?.let(::File)

    nucleusApplication(
        args = args,
        backend = NucleusBackend.Tao,
        // Keep Nucleus' public lock/watcher implementation, but own the restore payload so ordinary
        // file-association paths can be forwarded alongside URI deep links.
        enableSingleInstance = false,
    ) {
        val uiScope = rememberCoroutineScope()
        var windowVisible by remember { mutableStateOf(true) }
        var activationRequest by remember { mutableStateOf(0L) }
        val trayAvailable = remember(smokeMode, playbackSmokeFile) {
            !smokeMode && playbackSmokeFile == null && nucleusTrayCanRestoreWindow().also { available ->
                if (!available) {
                    AppLogger.w(
                        "DesktopTray",
                        "Nucleus tray is unavailable; closing the main window will keep it visible",
                    )
                }
            }
        }
        val requestExit = { exitApplication() }
        val showWindow = {
            windowVisible = true
            activationRequest += 1L
        }

        onDeepLink { uri ->
            activation.emitInput(uri.toString())
            uiScope.launch { showWindow() }
        }

        LaunchedEffect(activation) {
            activation.focusRequests.collect { showWindow() }
        }

        installDesktopPlaybackSessionIntegrationFactory { playbackSession ->
            NucleusSystemMediaSession(
                playbackSession = playbackSession,
                onRaise = { uiScope.launch { showWindow() } },
                onQuit = { uiScope.launch { requestExit() } },
                onOpenUri = { uri ->
                    activation.emitInput(uri)
                    uiScope.launch { showWindow() }
                },
            )
        }

        if (trayAvailable) {
            Tray(
                icon = painterResource("ic_launcher.png"),
                tooltip = "FuoEvolve",
                primaryAction = { uiScope.launch { showWindow() } },
            ) {
                Item(label = "显示 FuoEvolve") {
                    uiScope.launch { showWindow() }
                }
                Divider()
                Item(label = "退出") {
                    uiScope.launch { requestExit() }
                }
            }
        }

        DecoratedWindow(
            onCloseRequest = {
                if (trayAvailable) {
                    windowVisible = false
                }
            },
            visible = windowVisible,
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
            minimumSize = DpSize(900.dp, 600.dp),
            title = "FuoEvolve",
        ) {
            val clipboardManager = LocalClipboardManager.current
            DisposableEffect(oauthDeviceCodeAssistant, clipboardManager) {
                val clipboardWriter: (String) -> Unit = { value ->
                    uiScope.launch {
                        runCatching {
                            clipboardManager.setText(AnnotatedString(value))
                        }
                    }
                }
                oauthDeviceCodeAssistant.bindClipboardWriter(clipboardWriter)
                onDispose {
                    oauthDeviceCodeAssistant.unbindClipboardWriter(clipboardWriter)
                    oauthDeviceCodeAssistant.clearUserCodeNotification()
                }
            }

            LaunchedEffect(activationRequest) {
                if (activationRequest > 0L) {
                    nucleusWindow.show()
                    nucleusWindow.setMinimized(false)
                    nucleusWindow.toFront()
                    nucleusWindow.requestFocus()
                }
            }

            if (playbackSmokeFile != null) {
                LaunchedEffect(playbackSmokeFile) {
                    check(playbackSmokeFile.isFile) {
                        "Native playback smoke file does not exist: ${playbackSmokeFile.absolutePath}"
                    }
                    val engine = DesktopMpvPlaybackEngine { listener -> JniMpvBackend(listener) }
                    try {
                        val track = MusicTrack(
                            id = "nucleus-native-playback-smoke",
                            title = "Native playback smoke",
                            artists = "FuoEvolve CI",
                            album = "",
                            source = "local",
                            sourceType = TrackSourceType.LocalMediaStore,
                            durationMs = 5_000L,
                            localUri = playbackSmokeFile.absolutePath,
                        )
                        engine.play(
                            track,
                            PlaybackPayload(
                                url = playbackSmokeFile.absolutePath,
                                title = track.title,
                                artists = track.artists,
                                album = track.album,
                                source = track.source,
                                durationMs = track.durationMs,
                            ),
                        )
                        val playing = withTimeout(20_000L) {
                            engine.state.first { state ->
                                state.status == PlayerStatus.Playing && state.positionMs >= 300L
                            }
                        }
                        check(playing.positionMs >= 300L) {
                            "Native libmpv playback did not advance: ${playing.positionMs} ms"
                        }
                    } finally {
                        engine.close()
                    }
                    requestExit()
                }
            } else if (smokeMode) {
                LaunchedEffect(Unit) {
                    // Reaching this effect means Tao and the existing FuoEvolve composition both
                    // started successfully. Give one frame a short grace period before exiting.
                    delay(1_500)
                    requestExit()
                }
            }

            DesktopAppHost(externalInputs = activation.inputs)
        }
    }
}

internal fun nucleusTrayCanRestoreWindow(
    osName: String = System.getProperty("os.name").orEmpty(),
    linuxStatusNotifierProbe: () -> Boolean = ::linuxStatusNotifierWatcherAvailable,
): Boolean {
    val normalized = osName.lowercase(Locale.ROOT)
    return when {
        normalized.contains("windows") -> true
        normalized.contains("mac") || normalized.contains("darwin") -> true
        normalized.contains("linux") -> linuxStatusNotifierProbe()
        else -> false
    }
}

private fun linuxStatusNotifierWatcherAvailable(): Boolean {
    if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return false
    return runCatching {
        val process = ProcessBuilder(
            "busctl",
            "--user",
            "--no-pager",
            "status",
            "org.kde.StatusNotifierWatcher",
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(LINUX_TRAY_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)
}

private fun configurePackagedNativeRuntime() {
    if (!System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) return
    if (!System.getProperty("fuoevolve.libsecret.dir").isNullOrBlank()) return
    if (!System.getenv("FUOEVOLVE_LIBSECRET_DIR").isNullOrBlank()) return

    val resourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?: return
    val bundledLibSecret = resourcesDir.resolve("native/libsecret")
    if (bundledLibSecret.isDirectory) {
        System.setProperty("fuoevolve.libsecret.dir", bundledLibSecret.absolutePath)
    }
}
