package org.feeluown.mobile

import kotlin.concurrent.Volatile

/**
 * Platform helper for YouTube Music TV OAuth device-code UX:
 * clipboard copy and a system notification that can surface the user code.
 */
interface OAuthDeviceCodeAssistant {
    fun copyUserCode(userCode: String)
    fun showUserCodeNotification(userCode: String)
    fun clearUserCodeNotification()
}

@Volatile
private var fallbackOAuthDeviceCodeAssistant: OAuthDeviceCodeAssistant? = null

/**
 * Installs a process-wide fallback for hosts that use [NoOpOAuthDeviceCodeAssistant] at the
 * common composition edge. Android/iOS inject their concrete assistants directly; desktop installs
 * its JVM implementation before composing the shared app.
 */
fun installFallbackOAuthDeviceCodeAssistant(assistant: OAuthDeviceCodeAssistant) {
    fallbackOAuthDeviceCodeAssistant = assistant
}

object NoOpOAuthDeviceCodeAssistant : OAuthDeviceCodeAssistant {
    override fun copyUserCode(userCode: String) {
        fallbackOAuthDeviceCodeAssistant?.copyUserCode(userCode)
    }

    override fun showUserCodeNotification(userCode: String) {
        fallbackOAuthDeviceCodeAssistant?.showUserCodeNotification(userCode)
    }

    override fun clearUserCodeNotification() {
        fallbackOAuthDeviceCodeAssistant?.clearUserCodeNotification()
    }
}
