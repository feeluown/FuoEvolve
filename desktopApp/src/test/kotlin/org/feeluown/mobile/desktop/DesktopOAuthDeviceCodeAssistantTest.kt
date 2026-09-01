package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopOAuthDeviceCodeAssistantTest {
    @Test
    fun copyDelegatesToDesktopClipboardWriter() {
        var copied: String? = null
        val assistant = DesktopOAuthDeviceCodeAssistant(
            clipboardWriter = { copied = it },
            notificationLauncher = {},
        )

        assistant.copyUserCode("ABCD-EFGH")

        assertEquals("ABCD-EFGH", copied)
    }

    @Test
    fun notificationUsesNativeLinuxNotificationCommand() {
        var command: List<String>? = null
        val assistant = DesktopOAuthDeviceCodeAssistant(
            osName = "Linux",
            clipboardWriter = {},
            notificationLauncher = { command = it },
        )

        assistant.showUserCodeNotification("ABCD-EFGH")

        assertEquals("notify-send", command?.firstOrNull())
        assertEquals("YouTube Music 验证码：ABCD-EFGH", command?.lastOrNull())
    }

    @Test
    fun windowsNotificationCommandUsesPowerShellToast() {
        val command = desktopNotificationCommand("Windows 11", "ABCD-EFGH")

        assertNotNull(command)
        assertEquals("powershell.exe", command.first())
        assertEquals("-Command", command[3])
    }
}
