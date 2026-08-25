package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.feeluown.mobile.feature.providerauth.ProviderAuthFeatureOwner as CoreProviderAuthFeatureOwner
import org.feeluown.mobile.feature.providerauth.ProviderAuthFeatureState as CoreProviderAuthFeatureState
import org.feeluown.mobile.feature.providerauth.ProviderAuthHeaderInput as CoreProviderAuthHeaderInput
import org.feeluown.mobile.feature.providerauth.ProviderAuthOAuthInput as CoreProviderAuthOAuthInput
import org.feeluown.mobile.feature.providerauth.ProviderAuthSessionPort
import org.feeluown.mobile.feature.providerauth.ProviderDeviceAuthorization as CoreProviderDeviceAuthorization
import org.feeluown.mobile.feature.providerauth.ProviderDeviceAuthorizationPollResult as CoreProviderDeviceAuthorizationPollResult
import org.feeluown.mobile.feature.providerauth.ProviderDeviceAuthorizationPort
import org.feeluown.mobile.feature.providerauth.ProviderDeviceCodeAssistantPort
import org.feeluown.mobile.feature.providerauth.ProviderOAuthImportPort
import org.feeluown.mobile.feature.providerauth.ProviderOAuthImportResult
import org.feeluown.mobile.feature.providerauth.ProviderOAuthToken as CoreProviderOAuthToken
import org.feeluown.mobile.feature.providerauth.createProviderAuthFeatureOwner

data class ProviderAuthUiState(
    val sessions: ProviderSessionState = ProviderSessionState(),
    val cookieInputs: Map<String, String> = emptyMap(),
    val headerInputs: Map<String, ProviderHeaderInput> = emptyMap(),
    val oauthInputs: Map<String, ProviderOAuthInput> = emptyMap(),
    val ytmusicOAuthFlow: YtMusicOAuthFlowUiState? = null,
    val feedback: String? = null,
)

interface ProviderAuthFeatureController {
    val uiState: StateFlow<ProviderAuthUiState>
    fun authStateFor(provider: ProviderInfo): ProviderAuthState
    fun isBusy(providerId: String): Boolean
    fun authError(providerId: String): String?
    fun cookieInput(providerId: String): String
    fun headerInput(providerId: String): ProviderHeaderInput
    fun oauthInput(providerId: String): ProviderOAuthInput
    fun onCookiesChange(providerId: String, value: String)
    fun onHeaderAuthorizationChange(providerId: String, value: String)
    fun onHeaderCookieChange(providerId: String, value: String)
    fun onOAuthClientIdChange(providerId: String, value: String)
    fun onOAuthClientSecretChange(providerId: String, value: String)
    fun loginWithCookies(providerId: String, cookiesJson: String)
    fun loginWithHeaders(providerId: String)
    fun loginYtmusicWithHeaderFile(headerFileJson: String)
    fun startYtmusicTvOAuthLogin()
    fun markYtmusicOAuthBrowserOpened()
    fun copyYtmusicOAuthUserCode()
    fun cancelYtmusicTvOAuthLogin()
    fun loginYtmusicWithOAuthJson(oauthJson: String)
    fun importYtmusicOAuthRelatedJson(json: String)
    fun logout(providerId: String)
    fun refreshAll(providers: List<ProviderInfo>, refreshUserInfo: Boolean = false)
    fun dismissFeedback(feedback: String)
}

fun createProviderAuthFeatureController(
    providerAuth: ProviderAuthRepository,
    sessionRepository: ProviderSessionRepository,
    oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant,
    scope: CoroutineScope,
    providerName: (String) -> String,
    onSessionChanged: () -> Unit = {},
): ProviderAuthFeatureController {
    val owner = createProviderAuthFeatureOwner(
        sessionPort = ProviderAuthSessionBinding(sessionRepository),
        deviceAuthorizationPort = ProviderDeviceAuthorizationBinding(providerAuth),
        deviceCodeAssistant = ProviderDeviceCodeAssistantBinding(oauthDeviceCodeAssistant),
        oauthImportPort = ProviderOAuthImportBinding,
        scope = scope,
        providerId = ProviderInfo::providerId,
        providerName = providerName,
        defaultAuth = { provider ->
            ProviderAuthState(
                providerId = provider.providerId,
                providerName = provider.providerName,
                isLoggedIn = false,
            )
        },
        authProviderName = ProviderAuthState::providerName,
        authIsLoggedIn = ProviderAuthState::isLoggedIn,
        authUserName = ProviderAuthState::userName,
        deviceOAuthProviderId = "ytmusic",
        onSessionChanged = onSessionChanged,
    )
    return BoundProviderAuthFeatureController(owner, scope)
}

private class BoundProviderAuthFeatureController(
    private val owner: CoreProviderAuthFeatureOwner<ProviderInfo, ProviderAuthState, ProviderSessionState>,
    scope: CoroutineScope,
) : ProviderAuthFeatureController {
    override val uiState: StateFlow<ProviderAuthUiState> = owner.state
        .map(CoreProviderAuthFeatureState<ProviderSessionState>::toProviderAuthUiState)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = owner.state.value.toProviderAuthUiState(),
        )

    override fun authStateFor(provider: ProviderInfo): ProviderAuthState = owner.authStateFor(provider)
    override fun isBusy(providerId: String): Boolean = owner.isBusy(providerId)
    override fun authError(providerId: String): String? = owner.authError(providerId)
    override fun cookieInput(providerId: String): String = owner.cookieInput(providerId)
    override fun headerInput(providerId: String): ProviderHeaderInput = owner.headerInput(providerId).toAppInput()
    override fun oauthInput(providerId: String): ProviderOAuthInput = owner.oauthInput(providerId).toAppInput()
    override fun onCookiesChange(providerId: String, value: String) = owner.onCookiesChange(providerId, value)
    override fun onHeaderAuthorizationChange(providerId: String, value: String) =
        owner.onHeaderAuthorizationChange(providerId, value)
    override fun onHeaderCookieChange(providerId: String, value: String) = owner.onHeaderCookieChange(providerId, value)
    override fun onOAuthClientIdChange(providerId: String, value: String) = owner.onOAuthClientIdChange(providerId, value)
    override fun onOAuthClientSecretChange(providerId: String, value: String) =
        owner.onOAuthClientSecretChange(providerId, value)
    override fun loginWithCookies(providerId: String, cookiesJson: String) = owner.loginWithCookies(providerId, cookiesJson)
    override fun loginWithHeaders(providerId: String) = owner.loginWithHeaders(providerId)
    override fun loginYtmusicWithHeaderFile(headerFileJson: String) = owner.loginWithHeaderFile("ytmusic", headerFileJson)
    override fun startYtmusicTvOAuthLogin() = owner.startDeviceOAuthLogin()
    override fun markYtmusicOAuthBrowserOpened() = owner.markDeviceOAuthBrowserOpened()
    override fun copyYtmusicOAuthUserCode() = owner.copyDeviceOAuthUserCode()
    override fun cancelYtmusicTvOAuthLogin() = owner.cancelDeviceOAuthLogin()
    override fun loginYtmusicWithOAuthJson(oauthJson: String) = owner.loginWithOAuthJson("ytmusic", oauthJson)
    override fun importYtmusicOAuthRelatedJson(json: String) = owner.importOAuthRelatedJson("ytmusic", json)
    override fun logout(providerId: String) = owner.logout(providerId)
    override fun refreshAll(providers: List<ProviderInfo>, refreshUserInfo: Boolean) = owner.refreshAll(providers, refreshUserInfo)
    override fun dismissFeedback(feedback: String) = owner.dismissFeedback(feedback)
}

private class ProviderAuthSessionBinding(
    private val delegate: ProviderSessionRepository,
) : ProviderAuthSessionPort<ProviderAuthState, ProviderSessionState> {
    override val state: StateFlow<ProviderSessionState> = delegate.state
    override fun authState(session: ProviderSessionState, providerId: String): ProviderAuthState? = session.authStates[providerId]
    override fun isBusy(session: ProviderSessionState, providerId: String): Boolean = providerId in session.operations
    override fun error(session: ProviderSessionState, providerId: String): String? = session.errors[providerId]
    override suspend fun refresh(providerId: String, refreshUserInfo: Boolean): ProviderAuthState =
        delegate.refresh(providerId, refreshUserInfo)
    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
        delegate.loginWithCookies(providerId, cookiesJson)
    override suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState =
        delegate.loginWithHeaders(providerId, authorization, cookie)
    override suspend fun loginWithHeaderFile(providerId: String, headerFileJson: String): ProviderAuthState =
        delegate.loginWithHeaderFile(providerId, headerFileJson)
    override suspend fun loginWithOAuth(
        providerId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = delegate.loginWithOAuth(
        providerId,
        accessToken,
        refreshToken,
        expiresAtMillis,
        scope,
        clientId,
        clientSecret,
    )
    override suspend fun loginWithOAuthJson(
        providerId: String,
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = delegate.loginWithOAuthJson(providerId, oauthJson, clientId, clientSecret)
    override suspend fun logout(providerId: String): ProviderAuthState = delegate.logout(providerId)
}

private class ProviderDeviceAuthorizationBinding(
    private val delegate: ProviderAuthRepository,
) : ProviderDeviceAuthorizationPort {
    override suspend fun begin(
        providerId: String,
        clientId: String,
        clientSecret: String,
    ): CoreProviderDeviceAuthorization = delegate.beginDeviceAuthorization(providerId, clientId, clientSecret).let { authorization ->
        CoreProviderDeviceAuthorization(
            deviceCode = authorization.deviceCode,
            userCode = authorization.userCode,
            verificationUrl = authorization.verificationUrl,
            verificationUrlWithCode = authorization.verificationUrlWithCode,
            expiresInSeconds = authorization.expiresInSeconds,
            intervalSeconds = authorization.intervalSeconds,
        )
    }

    override suspend fun poll(
        providerId: String,
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): CoreProviderDeviceAuthorizationPollResult = when (
        val result = delegate.pollDeviceAuthorization(providerId, deviceCode, clientId, clientSecret)
    ) {
        is ProviderDeviceAuthorizationPollResult.Authorized -> CoreProviderDeviceAuthorizationPollResult.Authorized(
            CoreProviderOAuthToken(
                accessToken = result.token.accessToken,
                refreshToken = result.token.refreshToken,
                scope = result.token.scope,
                expiresAtMillis = result.token.expiresAtMillis,
            ),
        )
        ProviderDeviceAuthorizationPollResult.Pending -> CoreProviderDeviceAuthorizationPollResult.Pending
        ProviderDeviceAuthorizationPollResult.SlowDown -> CoreProviderDeviceAuthorizationPollResult.SlowDown
        is ProviderDeviceAuthorizationPollResult.Denied -> CoreProviderDeviceAuthorizationPollResult.Denied(result.message)
    }
}

private class ProviderDeviceCodeAssistantBinding(
    private val delegate: OAuthDeviceCodeAssistant,
) : ProviderDeviceCodeAssistantPort {
    override fun showUserCodeNotification(userCode: String) = delegate.showUserCodeNotification(userCode)
    override fun copyUserCode(userCode: String) = delegate.copyUserCode(userCode)
    override fun clearUserCodeNotification() = delegate.clearUserCodeNotification()
}

private object ProviderOAuthImportBinding : ProviderOAuthImportPort {
    override fun parse(json: String): ProviderOAuthImportResult {
        val oauth = org.feeluown.mobile.provider.ytmusic.YtMusicOAuth
        return when {
            oauth.looksLikeClientSecretJson(json) -> runCatching { oauth.parseClientSecretJson(json) }
                .fold(
                    onSuccess = { credentials ->
                        ProviderOAuthImportResult.Credentials(credentials.clientId, credentials.clientSecret)
                    },
                    onFailure = { ProviderOAuthImportResult.Unknown },
                )
            oauth.looksLikeOauthTokenJson(json) -> ProviderOAuthImportResult.OAuthJson(json)
            else -> ProviderOAuthImportResult.Unknown
        }
    }
}

private fun CoreProviderAuthFeatureState<ProviderSessionState>.toProviderAuthUiState(): ProviderAuthUiState =
    ProviderAuthUiState(
        sessions = sessions,
        cookieInputs = cookieInputs,
        headerInputs = headerInputs.mapValues { (_, input) -> input.toAppInput() },
        oauthInputs = oauthInputs.mapValues { (_, input) -> input.toAppInput() },
        ytmusicOAuthFlow = oauthFlow?.let { flow ->
            YtMusicOAuthFlowUiState(
                userCode = flow.userCode,
                verificationUrl = flow.verificationUrl,
                verificationUrlWithCode = flow.verificationUrlWithCode,
                statusMessage = flow.statusMessage,
                browserOpened = flow.browserOpened,
            )
        },
        feedback = feedback,
    )

private fun CoreProviderAuthHeaderInput.toAppInput(): ProviderHeaderInput = ProviderHeaderInput(
    authorization = authorization,
    cookie = cookie,
)

private fun CoreProviderAuthOAuthInput.toAppInput(): ProviderOAuthInput = ProviderOAuthInput(
    clientId = clientId,
    clientSecret = clientSecret,
)
