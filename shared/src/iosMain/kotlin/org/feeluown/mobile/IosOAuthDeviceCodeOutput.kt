package org.feeluown.mobile

interface IosOAuthDeviceCodeOutput {
    fun copyUserCode(userCode: String)
    fun showUserCodeNotification(userCode: String)
    fun clearUserCodeNotification()
}

class IosOAuthDeviceCodeAssistant(
    private val output: IosOAuthDeviceCodeOutput,
) : OAuthDeviceCodeAssistant {
    override fun copyUserCode(userCode: String) = output.copyUserCode(userCode)
    override fun showUserCodeNotification(userCode: String) = output.showUserCodeNotification(userCode)
    override fun clearUserCodeNotification() = output.clearUserCodeNotification()
}
