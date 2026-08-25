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
    private val owner: CoreProviderAuthFeatureOwner<ProviderInfo, ProviderAuthState>,
    scope: CoroutineScope,
) : ProviderAuthFeatureController {
    override val uiState: StateFlow<ProviderAuthUiState> = owner.state.map { it.toUiState() }
        .stateIn(scope, SharingStarted.Eagerly, owner.state.value.toUiState())
    override fun authStateFor(provider: ProviderInfo) = owner.authStateFor(provider)
    override fun isBusy(providerId: String) = owner.isBusy(providerId)
    override fun authError(providerId: String) = owner.authError(providerId)
    override fun cookieInput(providerId: String) = owner.cookieInput(providerId)
    override fun headerInput(providerId: String) = owner.headerInput(providerId).toApp()
    override fun oauthInput(providerId: String) = owner.oauthInput(providerId).toApp()
    override fun onCookiesChange(providerId: String, value: String) = owner.onCookiesChange(providerId, value)
    override fun onHeaderAuthorizationChange(providerId: String, value: String) = owner.onHeaderAuthorizationChange(providerId, value)
    override fun onHeaderCookieChange(providerId: String, value: String) = owner.onHeaderCookieChange(providerId, value)
    override fun onOAuthClientIdChange(providerId: String, value: String) = owner.onOAuthClientIdChange(providerId, value)
    override fun onOAuthClientSecretChange(providerId: String, value: String) = owner.onOAuthClientSecretChange(providerId, value)
    override fun loginWithCookies(providerId: String, cookiesJson: String) = owner.loginWithCookies(providerId, cookiesJson)
    override fun loginWithHeaders(providerId: String) = owner.loginWithHeaders(providerId)
    override fun loginYtmusicWithHeaderFile(headerFileJson: String) = owner.loginWithHeaderFile("ytmusic", headerFileJson)
    override fun startYtmusicTvOAuthLogin() = owner.startDeviceAuthorization()
    override fun markYtmusicOAuthBrowserOpened() = owner.markDeviceAuthorizationBrowserOpened()
    override fun copyYtmusicOAuthUserCode() = owner.copyDeviceAuthorizationUserCode()
    override fun cancelYtmusicTvOAuthLogin() = owner.cancelDeviceAuthorization()
    override fun loginYtmusicWithOAuthJson(oauthJson: String) = owner.loginWithOAuthJson(oauthJson)
    override fun importYtmusicOAuthRelatedJson(json: String) = owner.importOAuthRelatedJson(json)
    override fun logout(providerId: String) = owner.logout(providerId)
    override fun refreshAll(providers: List<ProviderInfo>, refreshUserInfo: Boolean) = owner.refreshAll(providers, refreshUserInfo)
    override fun dismissFeedback(feedback: String) = owner.dismissFeedback(feedback)
}

private class ProviderAuthSessionBinding(
    private val delegate: ProviderSessionRepository,
) : ProviderAuthSessionPort<ProviderInfo, ProviderAuthState> {
    override val state = delegate.state.mapAuthState()
    override suspend fun refresh(providerId: String, refreshUserInfo: Boolean) = delegate.refresh(providerId, refreshUserInfo)
    override suspend fun loginWithCookies(providerId: String, cookiesJson: String) = delegate.loginWithCookies(providerId, cookiesJson)
    override suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String) = delegate.loginWithHeaders(providerId, authorization, cookie)
    override suspend fun loginWithHeaderFile(providerId: String, headerFileJson: String) = delegate.loginWithHeaderFile(providerId, headerFileJson)
    override suspend fun loginWithOAuth(
        providerId: String, accessToken: String, refreshToken: String, expiresAtMillis: Long?, scope: String?, clientId: String, clientSecret: String,
    ) = delegate.loginWithOAuth(providerId, accessToken, refreshToken, expiresAtMillis, scope, clientId, clientSecret)
    override suspend fun loginWithOAuthJson(providerId: String, oauthJson: String, clientId: String, clientSecret: String) =
        delegate.loginWithOAuthJson(providerId, oauthJson, clientId, clientSecret)
    override suspend fun logout(providerId: String) = delegate.logout(providerId)
}

private class ProviderDeviceAuthorizationBinding(
    private val delegate: ProviderAuthRepository,
) : ProviderDeviceAuthorizationPort {
    override suspend fun begin(providerId: String, clientId: String, clientSecret: String): CoreProviderDeviceAuthorization =
        delegate.beginDeviceAuthorization(providerId, clientId, clientSecret).toCore()
    override suspend fun poll(providerId: String, deviceCode: String, clientId: String, clientSecret: String): CoreProviderDeviceAuthorizationPollResult =
        delegate.pollDeviceAuthorization(providerId, deviceCode, clientId, clientSecret).toCore()
}

private class ProviderDeviceCodeAssistantBinding(
    private val delegate: OAuthDeviceCodeAssistant,
) : ProviderDeviceCodeAssistantPort {
    override suspend fun open(url: String) = delegate.open(url)
    override suspend fun copy(value: String) = delegate.copy(value)
}

private object ProviderOAuthImportBinding : ProviderOAuthImportPort {
    override fun parse(json: String): ProviderOAuthImportResult = parseProviderOAuthImport(json)
}

private fun CoreProviderAuthFeatureState<ProviderInfo, ProviderAuthState>.toUiState() = ProviderAuthUiState(
    sessions = ProviderSessionState(
        providers = providers,
        authStates = authStates,
        operations = operations.mapValues { (_, value) -> ProviderSessionOperation.valueOf(value.name) },
        errors = errors,
    ),
    cookieInputs = cookieInputs,
    headerInputs = headerInputs.mapValues { it.value.toApp() },
    oauthInputs = oauthInputs.mapValues { it.value.toApp() },
    ytmusicOAuthFlow = deviceAuthorizationFlow?.toApp(),
    feedback = feedback,
)

private fun CoreProviderAuthHeaderInput.toApp() = ProviderHeaderInput(authorization, cookie)
private fun CoreProviderAuthOAuthInput.toApp() = ProviderOAuthInput(clientId, clientSecret)
private fun ProviderDeviceAuthorization.toCore() = CoreProviderDeviceAuthorization(providerId, deviceCode, userCode, verificationUrl, expiresInSeconds, intervalSeconds)
private fun ProviderDeviceAuthorizationPollResult.toCore(): CoreProviderDeviceAuthorizationPollResult = when (this) {
    is ProviderDeviceAuthorizationPollResult.Authorized -> CoreProviderDeviceAuthorizationPollResult.Authorized(CoreProviderOAuthToken(token.accessToken, token.refreshToken, token.scope, token.expiresAtMillis))
    ProviderDeviceAuthorizationPollResult.Pending -> CoreProviderDeviceAuthorizationPollResult.Pending
    ProviderDeviceAuthorizationPollResult.SlowDown -> CoreProviderDeviceAuthorizationPollResult.SlowDown
    is ProviderDeviceAuthorizationPollResult.Denied -> CoreProviderDeviceAuthorizationPollResult.Denied(message)
}
