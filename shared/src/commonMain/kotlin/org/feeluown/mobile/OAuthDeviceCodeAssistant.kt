package org.feeluown.mobile

/**
 * Platform helper for YouTube Music TV OAuth device-code UX:
 * clipboard copy and a system notification that can copy the user code.
 */
interface OAuthDeviceCodeAssistant {
    fun copyUserCode(userCode: String)
    fun showUserCodeNotification(userCode: String)
    fun clearUserCodeNotification()
}

object NoOpOAuthDeviceCodeAssistant : OAuthDeviceCodeAssistant {
    override fun copyUserCode(userCode: String) = Unit
    override fun showUserCodeNotification(userCode: String) = Unit
    override fun clearUserCodeNotification() = Unit
}
