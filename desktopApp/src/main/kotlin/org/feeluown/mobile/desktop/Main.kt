package org.feeluown.mobile.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.feeluown.mobile.DesktopAppHost

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FuoEvolve",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        DesktopAppHost()
    }
}
