package org.feeluown.mobile.desktop

import java.awt.Window
import java.util.Locale
import org.feeluown.mobile.playback.api.PlaybackSession

internal fun createDesktopSystemMediaSessionForWindow(
    playbackSession: PlaybackSession,
    window: Window,
): AutoCloseable {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        os.contains("windows") -> runCatching { WindowsSmtcSession(playbackSession, window) }
            .getOrElse { error ->
                System.err.println("FuoEvolve: Windows SMTC unavailable: ${error.message.orEmpty()}")
                AutoCloseable { }
            }

        os.contains("mac") || os.contains("darwin") -> runCatching { MacNowPlayingSession(playbackSession) }
            .getOrElse { error ->
                System.err.println("FuoEvolve: macOS Now Playing unavailable: ${error.message.orEmpty()}")
                AutoCloseable { }
            }

        else -> createDesktopSystemMediaSession(playbackSession)
    }
}
