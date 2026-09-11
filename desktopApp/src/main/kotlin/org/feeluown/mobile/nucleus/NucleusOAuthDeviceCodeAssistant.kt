package org.feeluown.mobile.nucleus

import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.NotificationCenter
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

private class DeferredNucleusNotificationHandle : NucleusNotificationHandle {
    private val lock = Any()
    private var dismissed = false
    private var delegate: NucleusNotificationHandle? = null

    fun attach(handle: NucleusNotificationHandle?) {
        if (handle == null) return
        val dismissImmediately = synchronized(lock) {
            if (dismissed) {
                true
            } else {
                delegate?.dismissSafely()
                delegate = handle
                false
            }
        }
        if (dismissImmediately) handle.dismissSafely()
    }

    override fun dismiss() {
        val handle = synchronized(lock) {
            dismissed = true
            delegate.also { delegate = null }
        }
        handle?.dismissSafely()
    }
}

private fun NucleusNotificationHandle.dismissSafely() {
    runCatching(::dismiss)
}

private fun sendNativeOAuthDeviceCodeNotification(
    userCode: String,
    copyUserCode: () -> Unit,
): NucleusNotificationHandle? {
    if (isMacOs()) {
        if (!NotificationCenter.isAvailable) return null
        val pending = DeferredNucleusNotificationHandle()
        NotificationCenter.requestAuthorization(setOf(AuthorizationOption.ALERT)) { granted, _ ->
            if (granted) {
                pending.attach(sendCommonOAuthDeviceCodeNotification(userCode, copyUserCode))
            }
        }
        return pending
    }
    return sendCommonOAuthDeviceCodeNotification(userCode, copyUserCode)
}

private fun sendCommonOAuthDeviceCodeNotification(
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

private fun isMacOs(): Boolean {
    val osName = System.getProperty("os.name").orEmpty()
    return osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true)
}
