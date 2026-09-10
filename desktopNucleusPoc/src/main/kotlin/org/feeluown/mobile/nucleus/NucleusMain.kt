package org.feeluown.mobile.nucleus

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import kotlinx.coroutines.delay
import org.feeluown.mobile.DesktopAppHost
import org.feeluown.mobile.installDesktopAppLogger

private const val SMOKE_ENV = "FUOEVOLVE_NUCLEUS_POC_SMOKE"

fun main() {
    installDesktopAppLogger()
    val smokeMode = System.getenv(SMOKE_ENV) == "1"

    nucleusApplication(backend = NucleusBackend.Tao) {
        val requestExit = { exitApplication() }

        DecoratedWindow(
            onCloseRequest = requestExit,
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
            minimumSize = DpSize(900.dp, 600.dp),
            title = "FuoEvolve · Nucleus PoC",
        ) {
            if (smokeMode) {
                LaunchedEffect(Unit) {
                    // Reaching this effect means the Tao window and the existing FuoEvolve
                    // composition both started successfully. Give one frame a short grace period
                    // before exiting so CI validates the actual runtime path, not only compilation.
                    delay(1_500)
                    requestExit()
                }
            }

            DesktopAppHost()
        }
    }
}
