package org.feeluown.mobile.desktop

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import org.feeluown.mobile.OAuthDeviceCodeAssistant

internal class DesktopOAuthDeviceCodeAssistant(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val clipboardWriter: (String) -> Unit = ::writeDesktopClipboard,
    private val notificationLauncher: (List<String>) -> Unit = ::launchDesktopNotification,
) : OAuthDeviceCodeAssistant {
    override fun copyUserCode(userCode: String) {
        runCatching { clipboardWriter(userCode) }
    }

    override fun showUserCodeNotification(userCode: String) {
        val command = desktopNotificationCommand(osName, userCode) ?: return
        runCatching { notificationLauncher(command) }
    }

    override fun clearUserCodeNotification() = Unit
}

private fun writeDesktopClipboard(value: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
}

private fun launchDesktopNotification(command: List<String>) {
    ProcessBuilder(command).start()
}

internal fun desktopNotificationCommand(osName: String, userCode: String): List<String>? {
    val normalized = osName.lowercase()
    val message = "YouTube Music 验证码：$userCode"
    return when {
        "win" in normalized -> {
            val safeMessage = message.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;")
            val script = """
                [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > ${'$'}null
                [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > ${'$'}null
                ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
                ${'$'}xml.LoadXml('<toast><visual><binding template="ToastGeneric"><text>FuoEvolve</text><text>$safeMessage</text></binding></visual></toast>')
                ${'$'}toast = [Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
                [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('FuoEvolve').Show(${'$'}toast)
            """.trimIndent()
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
        }
        "mac" in normalized -> {
            val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
            listOf("osascript", "-e", "display notification \"$escaped\" with title \"FuoEvolve\"")
        }
        "linux" in normalized || "nix" in normalized || "nux" in normalized ->
            listOf("notify-send", "FuoEvolve", message)
        else -> null
    }
}
