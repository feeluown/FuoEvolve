package org.feeluown.mobile

interface IosWebLoginOutput {
    fun open(
        providerId: String,
        providerName: String,
        loginUrl: String,
        cookieKeyGroupsJson: String,
        completionHandler: (String?) -> Unit,
    )
    fun clear()
}
