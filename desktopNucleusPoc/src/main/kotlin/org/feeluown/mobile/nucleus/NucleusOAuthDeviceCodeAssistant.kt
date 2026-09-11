package org.feeluown.mobile.nucleus

import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification
import java.util.concurrent.atomic.AtomicReference
import org.feeluown.mobile.OAuthDeviceCodeAssistant

internal fun interface NucleusNotificationHandle {
    fun dismiss()
}

/**
 * Nucleus/Tao implementation of the desktop OAuth device-code shell UX.
 *
 * Notifications are delivered by Nucleus' native per-platform backends. Clipboard access is bound
 * from the active DecoratedWindow composition so Tao can supply its native clipboard implementation
 * (including the GTK/Wayland bridge on Linux) without pulling AWT Toolkit into this runtime path.
 */
internal class NucleusOAuthDeviceCodeAssistant(
    private val notificationSender: (String, () -> Unit) -> NucleusNotificationHandle? =
        ::sendNativeOAuthDeviceCodeNotification,
) : OAuthDeviceCodeAssistant {
    private val clipboardWriter = AtomicReference<((String) -> Unit)?>(null)
    private val activeNotification = AtomicReference<NucleusNotificationHandle?>(null)

    fun bindClipboardWriter(writer: (String) -> Unit) {
        clipboardWriter.set(writer)
    }

    fun unbindClipboardWriter(writer: (String) -> Unit) {
        clipboardWriter.compareAndSet(writer, null)
    }

    override fun copyUserCode(userCode: String) {
        clipboardWriter.get()?.let { writer ->
            runCatching { writer(userCode) }
        }
    }

    override fun showUserCodeNotification(userCode: String) {
        activeNotification.getAndSet(null)?.dismissSafely()
        val handle = runCatching {
            notificationSender(userCode) { copyUserCode(userCode) }
        }.getOrNull()
        activeNotification.set(handle)
    }

    override fun clearUserCodeNotification() {
        activeNotification.getAndSet(null)?.dismissSafely()
    }
}

private fun NucleusNotificationHandle.dismissSafely() {
    runCatching(::dismiss)
}

private fun sendNativeOAuthDeviceCodeNotification(
    userCode: String,
    copyUserCode: () -> Unit,
): NucleusNotificationHandle? {
    if (!NotificationManager.isAvailable()) return null

    return when (
        val result = notification(
            title = "FuoEvolve",
            message = "YouTube Music 验证码：$userCode",
            onActivated = copyUserCode,
        ) {
            button("复制验证码", copyUserCode)
        }.send()
    ) {
        is NotificationResult.Success -> NucleusNotificationHandle { result.handle.dismiss() }
        is NotificationResult.Failure -> null
    }
}
