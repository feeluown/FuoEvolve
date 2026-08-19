package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class ProviderAuthController(
    providerRepository: ProviderMusicRepository,
    private val sessionRepository: ProviderSessionRepository,
    private val oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant,
    private val scope: CoroutineScope,
    private val state: ProviderAuthControllerState,
    private val providerName: (String) -> String,
    private val persistSettings: () -> Unit,
    private val onSessionChanged: () -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val authRepository: ProviderAuthRepository = ProviderAuthRepositoryView(providerRepository)
    private var stateJob: Job? = null
    private var ytmusicOAuthJob: Job? = null

    fun start() {
        if (stateJob?.isActive == true) return
        stateJob = scope.launch {
            sessionRepository.state.collect { sessionState ->
                state.authStates = sessionState.authStates
                state.authOperations = sessionState.operations
                state.authErrors = sessionState.errors
            }
        }
    }

    fun authStateFor(provider: ProviderInfo): ProviderAuthState =
        state.authStates[provider.providerId] ?: ProviderAuthState(
            providerId = provider.providerId,
            providerName = provider.providerName,
            isLoggedIn = false,
        )

    fun isBusy(providerId: String): Boolean =
        providerId in state.authOperations ||
            (providerId == "ytmusic" && ytmusicOAuthJob?.isActive == true)

    fun authError(providerId: String): String? = state.authErrors[providerId]

    fun cookieInput(providerId: String): String = state.cookieInputs[providerId].orEmpty()

    fun headerInput(providerId: String): ProviderHeaderInput =
        state.headerInputs[providerId] ?: ProviderHeaderInput()

    fun oauthInput(providerId: String): ProviderOAuthInput =
        state.oauthInputs[providerId] ?: ProviderOAuthInput()

    fun onCookiesChange(providerId: String, value: String) {
        state.cookieInputs = state.cookieInputs + (providerId to value)
    }

    fun onHeaderAuthorizationChange(providerId: String, value: String) {
        state.headerInputs = state.headerInputs + (
            providerId to headerInput(providerId).copy(authorization = value)
        )
    }

    fun onHeaderCookieChange(providerId: String, value: String) {
        state.headerInputs = state.headerInputs + (
            providerId to headerInput(providerId).copy(cookie = value)
        )
    }

    fun onOAuthClientIdChange(providerId: String, value: String) {
        state.oauthInputs = state.oauthInputs + (
            providerId to oauthInput(providerId).copy(clientId = value)
        )
    }

    fun onOAuthClientSecretChange(providerId: String, value: String) {
        state.oauthInputs = state.oauthInputs + (
            providerId to oauthInput(providerId).copy(clientSecret = value)
        )
    }

    fun loginWithCookies(providerId: String, cookiesJson: String) {
        val cookies = cookiesJson.trim()
        val name = providerName(providerId)
        if (cookies.isEmpty()) {
            setMessage("请输入 $name cookies")
            return
        }
        scope.launch {
            setMessage("正在登录 $name")
            runCatching { sessionRepository.loginWithCookies(providerId, cookies) }
                .onSuccess {
                    state.cookieInputs = state.cookieInputs - providerId
                    persistSettings()
                    setMessage(
                        if (it.isLoggedIn) {
                            "${it.providerName} 已登录：${it.userName.orEmpty()}"
                        } else {
                            "${it.providerName} 未登录"
                        }
                    )
                    onSessionChanged()
                }
                .onFailure(onError)
        }
    }

    fun loginWithHeaders(providerId: String) {
        val input = headerInput(providerId)
        val authorization = input.authorization.trim()
        val cookie = input.cookie.trim()
        val name = providerName(providerId)
        if (authorization.isEmpty() || cookie.isEmpty()) {
            setMessage("请输入 $name Authorization 和 Cookie")
            return
        }
        scope.launch {
            setMessage("正在登录 $name")
            runCatching { sessionRepository.loginWithHeaders(providerId, authorization, cookie) }
                .onSuccess {
                    persistSettings()
                    setMessage(
                        if (it.isLoggedIn) {
                            "${it.providerName} 已登录：${it.userName.orEmpty()}"
                        } else {
                            "${it.providerName} 未登录"
                        }
                    )
                    onSessionChanged()
                }
                .onFailure(onError)
        }
    }

    fun loginYtmusicWithHeaderFile(headerFileJson: String) {
        if (headerFileJson.isBlank()) {
            setMessage("无法读取 ytmusic_header.json")
            return
        }
        val providerId = "ytmusic"
        val name = providerName(providerId)
        scope.launch {
            setMessage("正在登录 $name")
            runCatching { sessionRepository.loginWithHeaderFile(providerId, headerFileJson) }
                .onSuccess {
                    setMessage(
                        if (it.isLoggedIn) {
                            "${it.providerName} 已登录：${it.userName.orEmpty()}"
                        } else {
                            "${it.providerName} 未登录"
                        }
                    )
                    onSessionChanged()
                }
                .onFailure(onError)
        }
    }

    fun startYtmusicTvOAuthLogin() {
        val providerId = "ytmusic"
        val input = oauthInput(providerId)
        val clientId = input.clientId.trim()
        val clientSecret = input.clientSecret.trim()
        val name = providerName(providerId)
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            setMessage("请输入 $name 的 client_id 和 client_secret")
            return
        }
        cancelYtmusicTvOAuthLogin()
        ytmusicOAuthJob = scope.launch {
            setMessage("正在获取 Google 授权码")
            runCatching {
                val deviceAuth = authRepository.beginDeviceAuthorization(providerId, clientId, clientSecret)
                state.ytmusicOAuthFlow = YtMusicOAuthFlowUiState(
                    userCode = deviceAuth.userCode,
                    verificationUrl = deviceAuth.verificationUrl,
                    verificationUrlWithCode = deviceAuth.verificationUrlWithCode,
                    statusMessage = "请在浏览器中输入下方验证码完成授权",
                    browserOpened = false,
                )
                oauthDeviceCodeAssistant.showUserCodeNotification(deviceAuth.userCode)
                setMessage("验证码 ${deviceAuth.userCode}（已发送通知，可点击复制）")
                val token = withTimeout(deviceAuth.expiresInSeconds.coerceAtLeast(1) * 1_000L) {
                    var intervalSeconds = deviceAuth.intervalSeconds.coerceAtLeast(1)
                    while (true) {
                        delay(intervalSeconds * 1_000L)
                        when (
                            val result = authRepository.pollDeviceAuthorization(
                                providerId = providerId,
                                deviceCode = deviceAuth.deviceCode,
                                clientId = clientId,
                                clientSecret = clientSecret,
                            )
                        ) {
                            is ProviderDeviceAuthorizationPollResult.Authorized -> return@withTimeout result.token
                            ProviderDeviceAuthorizationPollResult.Pending -> {
                                state.ytmusicOAuthFlow = state.ytmusicOAuthFlow?.copy(statusMessage = "等待授权中…")
                            }
                            ProviderDeviceAuthorizationPollResult.SlowDown -> {
                                intervalSeconds += 5
                                state.ytmusicOAuthFlow = state.ytmusicOAuthFlow?.copy(statusMessage = "轮询过快，已放慢…")
                            }
                            is ProviderDeviceAuthorizationPollResult.Denied -> error(result.message)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
                sessionRepository.loginWithOAuth(
                    providerId = providerId,
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    expiresAtMillis = token.expiresAtMillis,
                    scope = token.scope,
                    clientId = clientId,
                    clientSecret = clientSecret,
                )
            }.onSuccess {
                clearYtmusicOAuthUi()
                setMessage(
                    if (it.isLoggedIn) {
                        "${it.providerName} 已通过 Google OAuth 登录"
                    } else {
                        "${it.providerName} 未登录"
                    }
                )
                onSessionChanged()
            }.onFailure { throwable ->
                when (throwable) {
                    is TimeoutCancellationException -> {
                        clearYtmusicOAuthUi()
                        setMessage("Google OAuth 授权超时，请重试")
                    }
                    is CancellationException -> {
                        clearYtmusicOAuthUi()
                        setMessage("已取消 Google OAuth 登录")
                    }
                    else -> {
                        clearYtmusicOAuthUi()
                        onError(throwable)
                    }
                }
            }
            ytmusicOAuthJob = null
        }
    }

    fun markYtmusicOAuthBrowserOpened() {
        state.ytmusicOAuthFlow = state.ytmusicOAuthFlow?.copy(
            browserOpened = true,
            statusMessage = "请在浏览器中输入验证码完成授权",
        )
    }

    fun copyYtmusicOAuthUserCode() {
        val userCode = state.ytmusicOAuthFlow?.userCode?.takeIf { it.isNotBlank() } ?: return
        oauthDeviceCodeAssistant.copyUserCode(userCode)
        setMessage("验证码已复制：$userCode")
    }

    fun cancelYtmusicTvOAuthLogin() {
        ytmusicOAuthJob?.cancel()
        ytmusicOAuthJob = null
        clearYtmusicOAuthUi()
    }

    private fun clearYtmusicOAuthUi() {
        state.ytmusicOAuthFlow = null
        oauthDeviceCodeAssistant.clearUserCodeNotification()
    }

    fun loginYtmusicWithOAuthJson(oauthJson: String) {
        val providerId = "ytmusic"
        val input = oauthInput(providerId)
        val clientId = input.clientId.trim()
        val clientSecret = input.clientSecret.trim()
        if (oauthJson.isBlank()) {
            setMessage("无法读取 oauth.json")
            return
        }
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            setMessage("导入 oauth.json 前请填写或导入 client_id 和 client_secret")
            return
        }
        val name = providerName(providerId)
        cancelYtmusicTvOAuthLogin()
        scope.launch {
            setMessage("正在登录 $name")
            runCatching {
                sessionRepository.loginWithOAuthJson(providerId, oauthJson, clientId, clientSecret)
            }.onSuccess {
                setMessage(
                    if (it.isLoggedIn) {
                        "${it.providerName} 已通过 oauth.json 登录"
                    } else {
                        "${it.providerName} 未登录"
                    }
                )
                onSessionChanged()
            }.onFailure(onError)
        }
    }

    fun importYtmusicOAuthRelatedJson(json: String) {
        val trimmed = json.trim()
        if (trimmed.isBlank()) {
            setMessage("无法读取 JSON 文件")
            return
        }
        val oauth = org.feeluown.mobile.provider.ytmusic.YtMusicOAuth
        when {
            oauth.looksLikeClientSecretJson(trimmed) -> {
                runCatching { oauth.parseClientSecretJson(trimmed) }
                    .onSuccess { credentials ->
                        state.oauthInputs = state.oauthInputs + (
                            "ytmusic" to ProviderOAuthInput(
                                clientId = credentials.clientId,
                                clientSecret = credentials.clientSecret,
                            )
                        )
                        setMessage("已导入 Google OAuth client_id / client_secret")
                    }
                    .onFailure(onError)
            }
            oauth.looksLikeOauthTokenJson(trimmed) -> loginYtmusicWithOAuthJson(trimmed)
            else -> setMessage("无法识别的 JSON：请导入 Google client_secret.json 或 ytmusicapi oauth.json")
        }
    }

    fun logout(providerId: String) {
        val name = providerName(providerId)
        scope.launch {
            setMessage("正在退出 $name")
            runCatching { sessionRepository.logout(providerId) }
                .onSuccess {
                    state.cookieInputs = state.cookieInputs - providerId
                    state.headerInputs = state.headerInputs - providerId
                    state.oauthInputs = state.oauthInputs - providerId
                    cancelYtmusicTvOAuthLogin()
                    persistSettings()
                    setMessage("${it.providerName} 已退出登录")
                    onSessionChanged()
                }
                .onFailure(onError)
        }
    }

    fun refreshAll(providers: List<ProviderInfo>, refreshUserInfo: Boolean = false) {
        scope.launch {
            refresh(providers, refreshUserInfo)
        }
    }

    suspend fun refresh(providers: List<ProviderInfo>, refreshUserInfo: Boolean = false) {
        providers.forEach { provider ->
            runCatching {
                sessionRepository.refresh(provider.providerId, refreshUserInfo = refreshUserInfo)
            }.onFailure(onError)
        }
    }

    fun isLoggedIn(providerId: String): Boolean =
        sessionRepository.state.value.authStates[providerId]?.isLoggedIn == true
}
