package org.feeluown.mobile

interface IosWebLoginOutput {
    /**
     * Opens the provider's web login page in an in-app web view.
     *
     * [loginUrl] is the selected login entry (e.g. the QQ-account or WeChat-account authorize URL of
     * the same provider), already resolved by the host.
     *
     * [onAuthorizationCode] receives the provider's OAuth authorization code when the login redirect
     * was intercepted, together with the redirect's `login_type` (1 = QQ account, 2 = WeChat
     * account, 0 when the redirect carried none). The web view cancels that navigation because the
     * code is single-use and the redirect page consumes it as soon as it runs. In that case
     * [completionHandler] is not called with cookies; it stays the cookie-login path for every
     * provider without a code redirect.
     */
    fun open(
        providerId: String,
        providerName: String,
        loginUrl: String,
        cookieKeyGroupsJson: String,
        onAuthorizationCode: (String, Int) -> Unit,
        completionHandler: (String?) -> Unit,
    )
    fun clear()
}
