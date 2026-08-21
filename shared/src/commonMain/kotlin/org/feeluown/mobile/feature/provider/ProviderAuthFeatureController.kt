package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
    providerRepository: ProviderMusicRepository,
    sessionRepository: ProviderSessionRepository,
    oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant,
    scope: CoroutineScope,
    providerName: (String) -> String,
    onSessionChanged: () -> Unit = {},
): ProviderAuthFeatureController = DefaultProviderAuthFeatureController(
    providerRepository,
    sessionRepository,
    oauthDeviceCodeAssistant,
    scope,
    providerName,
    onSessionChanged,
)

private class DefaultProviderAuthFeatureController(
    providerRepository: ProviderMusicRepository,
    private val sessionRepository: ProviderSessionRepository,
    private val oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant,
    private val scope: CoroutineScope,
    private val providerName: (String) -> String,
    private val onSessionChanged: () -> Unit,
) : ProviderAuthFeatureController {
    private val authRepository: ProviderAuthRepository = ProviderAuthRepositoryView(providerRepository)
    private val mutableUiState = MutableStateFlow(ProviderAuthUiState(sessions = sessionRepository.state.value))
    override val uiState: StateFlow<ProviderAuthUiState> = mutableUiState.asStateFlow()
    private var ytmusicOAuthJob: Job? = null

    init {
        scope.launch {
            sessionRepository.state.collect { sessions ->
                update { copy(sessions = sessions) }
            }
        }
    }

    override fun authStateFor(provider: ProviderInfo): ProviderAuthState =
        uiState.value.sessions.authStates[provider.providerId] ?: ProviderAuthState(
            providerId = provider.providerId,
            providerName = provider.providerName,
            isLoggedIn = false,
        )

    override fun isBusy(providerId: String): Boolean =
        providerId in uiState.value.sessions.operations ||
            (providerId == "ytmusic" && ytmusicOAuthJob?.isActive == true)

    override fun authError(providerId: String): String? = uiState.value.sessions.errors[providerId]
    override fun cookieInput(providerId: String): String = uiState.value.cookieInputs[providerId].orEmpty()
    override fun headerInput(providerId: String): ProviderHeaderInput =
        uiState.value.headerInputs[providerId] ?: ProviderHeaderInput()
    override fun oauthInput(providerId: String): ProviderOAuthInput =
        uiState.value.oauthInputs[providerId] ?: ProviderOAuthInput()

    override fun onCookiesChange(providerId: String, value: String) =
        update { copy(cookieInputs = cookieInputs + (providerId to value)) }

    override fun onHeaderAuthorizationChange(providerId: String, value: String) = update {
        copy(headerInputs = headerInputs + (providerId to headerInput(providerId).copy(authorization = value)))
    }

    override fun onHeaderCookieChange(providerId: String, value: String) = update {
        copy(headerInputs = headerInputs + (providerId to headerInput(providerId).copy(cookie = value)))
    }

    override fun onOAuthClientIdChange(providerId: String, value: String) = update {
        copy(oauthInputs = oauthInputs + (providerId to oauthInput(providerId).copy(clientId = value)))
    }

    override fun onOAuthClientSecretChange(providerId: String, value: String) = update {
        copy(oauthInputs = oauthInputs + (providerId to oauthInput(providerId).copy(clientSecret = value)))
    }

    override fun loginWithCookies(providerId: String, cookiesJson: String) {
        val cookies = cookiesJson.trim()
        if (cookies.isEmpty()) return feedback("请输入 ${providerName(providerId)} cookies")
        scope.launch {
            feedback("正在登录 ${providerName(providerId)}")
            runCatching { sessionRepository.loginWithCookies(providerId, cookies) }
                .onSuccess { auth ->
                    update { copy(cookieInputs = cookieInputs - providerId) }
                    feedback(if (auth.isLoggedIn) "${auth.providerName} 已登录：${auth.userName.orEmpty()}" else "${auth.providerName} 未登录")
                    onSessionChanged()
                }
                .onFailure(::failure)
        }
    }

    override fun loginWithHeaders(providerId: String) {
        val input = headerInput(providerId)
        if (input.authorization.isBlank() || input.cookie.isBlank()) {
            return feedback("请输入 ${providerName(providerId)} Authorization 和 Cookie")
        }
        scope.launch {
            feedback("正在登录 ${providerName(providerId)}")
            runCatching { sessionRepository.loginWithHeaders(providerId, input.authorization.trim(), input.cookie.trim()) }
                .onSuccess { auth ->
                    feedback(if (auth.isLoggedIn) "${auth.providerName} 已登录：${auth.userName.orEmpty()}" else "${auth.providerName} 未登录")
                    onSessionChanged()
                }
                .onFailure(::failure)
        }
    }

    override fun loginYtmusicWithHeaderFile(headerFileJson: String) {
        if (headerFileJson.isBlank()) return feedback("无法读取 ytmusic_header.json")
        scope.launch {
            feedback("正在登录 ${providerName("ytmusic")}")
            runCatching { sessionRepository.loginWithHeaderFile("ytmusic", headerFileJson) }
                .onSuccess { auth ->
                    feedback(if (auth.isLoggedIn) "${auth.providerName} 已登录：${auth.userName.orEmpty()}" else "${auth.providerName} 未登录")
                    onSessionChanged()
                }
                .onFailure(::failure)
        }
    }

    override fun startYtmusicTvOAuthLogin() {
        val providerId = "ytmusic"
        val input = oauthInput(providerId)
        val clientId = input.clientId.trim()
        val clientSecret = input.clientSecret.trim()
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            return feedback("请输入 ${providerName(providerId)} 的 client_id 和 client_secret")
        }
        cancelYtmusicTvOAuthLogin()
        ytmusicOAuthJob = scope.launch {
            feedback("正在获取 Google 授权码")
            runCatching {
                val deviceAuth = authRepository.beginDeviceAuthorization(providerId, clientId, clientSecret)
                update {
                    copy(
                        ytmusicOAuthFlow = YtMusicOAuthFlowUiState(
                            userCode = deviceAuth.userCode,
                            verificationUrl = deviceAuth.verificationUrl,
                            verificationUrlWithCode = deviceAuth.verificationUrlWithCode,
                            statusMessage = "请在浏览器中输入下方验证码完成授权",
                            browserOpened = false,
                        )
                    )
                }
                oauthDeviceCodeAssistant.showUserCodeNotification(deviceAuth.userCode)
                feedback("验证码 ${deviceAuth.userCode}（已发送通知，可点击复制）")
                val token = withTimeout(deviceAuth.expiresInSeconds.coerceAtLeast(1) * 1_000L) {
                    var intervalSeconds = deviceAuth.intervalSeconds.coerceAtLeast(1)
                    while (true) {
                        delay(intervalSeconds * 1_000L)
                        when (val result = authRepository.pollDeviceAuthorization(providerId, deviceAuth.deviceCode, clientId, clientSecret)) {
                            is ProviderDeviceAuthorizationPollResult.Authorized -> return@withTimeout result.token
                            ProviderDeviceAuthorizationPollResult.Pending -> updateOAuthStatus("等待授权中…")
                            ProviderDeviceAuthorizationPollResult.SlowDown -> {
                                intervalSeconds += 5
                                updateOAuthStatus("轮询过快，已放慢…")
                            }
                            is ProviderDeviceAuthorizationPollResult.Denied -> error(result.message)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                sessionRepository.loginWithOAuth(
                    providerId,
                    token.accessToken,
                    token.refreshToken,
                    token.expiresAtMillis,
                    token.scope,
                    clientId,
                    clientSecret,
                )
            }.onSuccess { auth ->
                clearOAuthUi()
                feedback(if (auth.isLoggedIn) "${auth.providerName} 已通过 Google OAuth 登录" else "${auth.providerName} 未登录")
                onSessionChanged()
            }.onFailure { throwable ->
                clearOAuthUi()
                when (throwable) {
                    is TimeoutCancellationException -> feedback("Google OAuth 授权超时，请重试")
                    is CancellationException -> feedback("已取消 Google OAuth 登录")
                    else -> failure(throwable)
                }
            }
            ytmusicOAuthJob = null
        }
    }

    override fun markYtmusicOAuthBrowserOpened() = update {
        copy(ytmusicOAuthFlow = ytmusicOAuthFlow?.copy(browserOpened = true, statusMessage = "请在浏览器中输入验证码完成授权"))
    }

    override fun copyYtmusicOAuthUserCode() {
        val code = uiState.value.ytmusicOAuthFlow?.userCode?.takeIf(String::isNotBlank) ?: return
        oauthDeviceCodeAssistant.copyUserCode(code)
        feedback("验证码已复制：$code")
    }

    override fun cancelYtmusicTvOAuthLogin() {
        ytmusicOAuthJob?.cancel()
        ytmusicOAuthJob = null
        clearOAuthUi()
    }

    override fun loginYtmusicWithOAuthJson(oauthJson: String) {
        val input = oauthInput("ytmusic")
        if (oauthJson.isBlank()) return feedback("无法读取 oauth.json")
        if (input.clientId.isBlank() || input.clientSecret.isBlank()) {
            return feedback("导入 oauth.json 前请填写或导入 client_id 和 client_secret")
        }
        cancelYtmusicTvOAuthLogin()
        scope.launch {
            feedback("正在登录 ${providerName("ytmusic")}")
            runCatching {
                sessionRepository.loginWithOAuthJson("ytmusic", oauthJson, input.clientId.trim(), input.clientSecret.trim())
            }.onSuccess { auth ->
                feedback(if (auth.isLoggedIn) "${auth.providerName} 已通过 oauth.json 登录" else "${auth.providerName} 未登录")
                onSessionChanged()
            }.onFailure(::failure)
        }
    }

    override fun importYtmusicOAuthRelatedJson(json: String) {
        val trimmed = json.trim()
        if (trimmed.isBlank()) return feedback("无法读取 JSON 文件")
        val oauth = org.feeluown.mobile.provider.ytmusic.YtMusicOAuth
        when {
            oauth.looksLikeClientSecretJson(trimmed) -> runCatching { oauth.parseClientSecretJson(trimmed) }
                .onSuccess { credentials ->
                    update {
                        copy(oauthInputs = oauthInputs + ("ytmusic" to ProviderOAuthInput(credentials.clientId, credentials.clientSecret)))
                    }
                    feedback("已导入 Google OAuth client_id / client_secret")
                }
                .onFailure(::failure)
            oauth.looksLikeOauthTokenJson(trimmed) -> loginYtmusicWithOAuthJson(trimmed)
            else -> feedback("无法识别的 JSON：请导入 Google client_secret.json 或 ytmusicapi oauth.json")
        }
    }

    override fun logout(providerId: String) {
        scope.launch {
            feedback("正在退出 ${providerName(providerId)}")
            runCatching { sessionRepository.logout(providerId) }
                .onSuccess { auth ->
                    update {
                        copy(
                            cookieInputs = cookieInputs - providerId,
                            headerInputs = headerInputs - providerId,
                            oauthInputs = oauthInputs - providerId,
                        )
                    }
                    cancelYtmusicTvOAuthLogin()
                    feedback("${auth.providerName} 已退出登录")
                    onSessionChanged()
                }
                .onFailure(::failure)
        }
    }

    override fun refreshAll(providers: List<ProviderInfo>, refreshUserInfo: Boolean) {
        scope.launch {
            providers.forEach { provider ->
                runCatching { sessionRepository.refresh(provider.providerId, refreshUserInfo) }.onFailure(::failure)
            }
        }
    }

    override fun dismissFeedback(feedback: String) {
        if (uiState.value.feedback == feedback) update { copy(feedback = null) }
    }

    private fun updateOAuthStatus(message: String) = update {
        copy(ytmusicOAuthFlow = ytmusicOAuthFlow?.copy(statusMessage = message))
    }

    private fun clearOAuthUi() {
        update { copy(ytmusicOAuthFlow = null) }
        oauthDeviceCodeAssistant.clearUserCodeNotification()
    }

    private fun feedback(message: String) = update { copy(feedback = message) }

    private fun failure(throwable: Throwable) {
        feedback(throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" })
    }

    private inline fun update(block: ProviderAuthUiState.() -> ProviderAuthUiState) {
        mutableUiState.value = mutableUiState.value.block()
    }
}
