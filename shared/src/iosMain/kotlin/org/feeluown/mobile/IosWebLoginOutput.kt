package org.feeluown.mobile

interface IosWebLoginOutput {
    /**
     * Opens the provider's web login page in an in-app web view.
     *
     * [onAuthorizationCode] receives the provider's OAuth authorization code when the login redirect
     * was intercepted. The web view cancels that navigation because the code is single-use and the
     * redirect page consumes it as soon as it runs. In that case [completionHandler] is not called
     * with cookies; it stays the cookie-login path for every provider without a code redirect.
     */
    fun open(
        providerId: String,
        providerName: String,
        loginUrl: String,
        cookieKeyGroupsJson: String,
        onAuthorizationCode: (String) -> Unit,
        completionHandler: (String?) -> Unit,
    )
    fun clear()
}
