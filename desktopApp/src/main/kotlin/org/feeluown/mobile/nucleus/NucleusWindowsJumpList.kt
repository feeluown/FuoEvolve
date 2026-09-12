package org.feeluown.mobile.nucleus

import dev.nucleusframework.launcher.windows.JumpListItem
import dev.nucleusframework.launcher.windows.WindowsJumpListManager
import java.util.Locale
import org.feeluown.mobile.AppLogger

internal fun installWindowsPlaybackJumpList(
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean {
    if (!osName.lowercase(Locale.ROOT).contains("windows")) return false
    if (!WindowsJumpListManager.isAvailable) {
        AppLogger.w("WindowsJumpList", "Nucleus Windows launcher backend is unavailable")
        return false
    }
    val installed = WindowsJumpListManager.setJumpList(tasks = windowsPlaybackJumpListTasks())
    if (!installed) {
        AppLogger.w(
            "WindowsJumpList",
            "Failed to install playback tasks: ${WindowsJumpListManager.lastError.orEmpty()}",
        )
    }
    return installed
}

internal fun windowsPlaybackJumpListTasks(): List<JumpListItem> = listOf(
    JumpListItem(
        title = "播放/暂停",
        arguments = DESKTOP_MEDIA_PLAY_PAUSE_ARGUMENT,
        description = "播放或暂停当前曲目",
    ),
    JumpListItem(
        title = "上一首",
        arguments = DESKTOP_MEDIA_PREVIOUS_ARGUMENT,
        description = "切换到上一首曲目",
    ),
    JumpListItem(
        title = "下一首",
        arguments = DESKTOP_MEDIA_NEXT_ARGUMENT,
        description = "切换到下一首曲目",
    ),
)
