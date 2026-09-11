package org.feeluown.mobile.nucleus

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.SingleInstanceRestoreEffect
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.composenativetray.tray.api.Tray
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.feeluown.mobile.DesktopAppHost
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.desktop.DesktopMpvPlaybackEngine
import org.feeluown.mobile.desktop.createDesktopSecureProviderCredentialStore
import org.feeluown.mobile.installDesktopAppLogger
import org.feeluown.mobile.installDesktopPlaybackEngineFactory
import org.feeluown.mobile.installDesktopPlaybackSessionIntegrationFactory
import org.feeluown.mobile.installDesktopProviderCredentialStoreFactory

private const val SMOKE_ENV = "FUOEVOLVE_NUCLEUS_POC_SMOKE"
private const val PLAYBACK_SMOKE_ENV = "FUOEVOLVE_NUCLEUS_PLAYBACK_SMOKE"

fun main(args: Array<String>) {
    configurePackagedNativeRuntime()
    installDesktopAppLogger()
    installDesktopProviderCredentialStoreFactory(::createDesktopSecureProviderCredentialStore)
    installDesktopPlaybackEngineFactory {
        DesktopMpvPlaybackEngine { listener -> JniMpvBackend(listener) }
    }

    val smokeMode = System.getenv(SMOKE_ENV) == "1"
    val playbackSmokeFile = System.getenv(PLAYBACK_SMOKE_ENV)
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val externalInputs = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    nucleusApplication(
        args = args,
        backend = NucleusBackend.Tao,
    ) {
        val uiScope = rememberCoroutineScope()
        var windowVisible by remember { mutableStateOf(true) }
        val requestExit = { exitApplication() }
        val showWindow = { windowVisible = true }

        onDeepLink { uri ->
            externalInputs.tryEmit(uri.toString())
            uiScope.launch { showWindow() }
        }

        SingleInstanceRestoreEffect {
            showWindow()
        }

        installDesktopPlaybackSessionIntegrationFactory { playbackSession ->
            NucleusSystemMediaSession(
                playbackSession = playbackSession,
                onRaise = { uiScope.launch { showWindow() } },
                onQuit = { uiScope.launch { requestExit() } },
                onOpenUri = { uri ->
                    externalInputs.tryEmit(uri)
                    uiScope.launch { showWindow() }
                },
            )
        }

        if (!smokeMode && playbackSmokeFile == null) {
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
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
            minimumSize = DpSize(900.dp, 600.dp),
            title = "FuoEvolve",
        ) {
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

            DesktopAppHost(externalInputs = externalInputs)
        }
    }
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
