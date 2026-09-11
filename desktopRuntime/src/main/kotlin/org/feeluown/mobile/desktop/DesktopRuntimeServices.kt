package org.feeluown.mobile.desktop

import java.nio.file.Path
import org.feeluown.mobile.DesktopTextFileDialogProvider
import org.feeluown.mobile.ListeningHistorySink
import org.feeluown.mobile.LocalMusicRepository
import org.feeluown.mobile.PlaybackEngine
import org.feeluown.mobile.PlaybackResumeStore
import org.feeluown.mobile.persistence.listening.DesktopListeningHistoryDriverFactory
import org.feeluown.mobile.persistence.listening.SqlDelightListeningHistoryStore

/** Runtime services shared by the legacy JVM host and the Nucleus/Tao host. */
fun createDesktopRuntimeLocalMusicRepository(): LocalMusicRepository = DesktopLocalMusicRepository()

fun createPersistentDesktopPlaybackEngine(
    delegate: PlaybackEngine,
    resumeStore: PlaybackResumeStore,
): PlaybackEngine = PersistentDesktopPlaybackEngine(delegate, resumeStore)

fun createDesktopRuntimeListeningHistorySink(databasePath: Path): ListeningHistorySink =
    SqlDelightListeningHistoryStore(DesktopListeningHistoryDriverFactory(databasePath))

fun createDesktopNativeTextFileDialogProvider(
    requireNativeLinuxPortal: Boolean = false,
): DesktopTextFileDialogProvider = FileKitDesktopTextFileDialogProvider(
    requireNativeLinuxPortal = requireNativeLinuxPortal,
)
