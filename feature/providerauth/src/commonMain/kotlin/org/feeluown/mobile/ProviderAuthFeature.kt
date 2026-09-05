package org.feeluown.mobile.feature.providerauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class ProviderAuthHeaderInput(
    val authorization: String = "",
    val cookie: String = "",
)

data class ProviderAuthOAuthInput(
    val clientId: String = "",
    val clientSecret: String = "",
)

data class ProviderDeviceOAuthFlowState(
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlWithCode: String,
    val statusMessage: String = "请在浏览器中完成授权",
    val browserOpened: Boolean = false,
)

data class ProviderAuthFeatureState<Session>(
    val sessions: Session,
    val cookieInputs: Map<String, String> = emptyMap(),
    val headerInputs: Map<String, ProviderAuthHeaderInput> = emptyMap(),
    val oauthInputs: Map<String, ProviderAuthOAuthInput> = emptyMap(),
    val oauthFlow: ProviderDeviceOAuthFlowState? = null,
    val feedback: String? = null,
)

data class ProviderDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlWithCode: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

data class ProviderOAuthToken(
    val accessToken: String,
    val refreshToken: String,
    val scope: String? = null,
    val expiresAtMillis: Long? = null,
)

sealed interface ProviderDeviceAuthorizationPollResult {
    data class Authorized(val token: ProviderOAuthToken) : ProviderDeviceAuthorizationPollResult
    data object Pending : ProviderDeviceAuthorizationPollResult
    data object SlowDown : ProviderDeviceAuthorizationPollResult
    data class Denied(val message: String) : ProviderDeviceAuthorizationPollResult
}

sealed interface ProviderOAuthImportResult {
    data class Credentials(val clientId: String, val clientSecret: String) : ProviderOAuthImportResult
    data class OAuthJson(val json: String) : ProviderOAuthImportResult
    data object Unknown : ProviderOAuthImportResult
}

interface ProviderAuthSessionPort<Auth, Session> {
    val state: StateFlow<Session>
    fun authState(session: Session, providerId: String): Auth?
    fun isBusy(session: Session, providerId: String): Boolean
    fun error(session: Session, providerId: String): String?
    suspend fun refresh(providerId: String, refreshUserInfo: Boolean = false): Auth
    suspend fun loginWithCookies(providerId: String, cookiesJson: String): Auth
    suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): Auth
    suspend fun loginWithHeaderFile(providerId: String, headerFileJson: String): Auth
    suspend fun loginWithOAuth(
        providerId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): Auth
    suspend fun loginWithOAuthJson(
        providerId: String,
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): Auth
    suspend fun logout(providerId: String): Auth
}

interface ProviderDeviceAuthorizationPort {
    suspend fun begin(providerId: String, clientId: String, clientSecret: String): ProviderDeviceAuthorization
    suspend fun poll(
        providerId: String,
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult
}

interface ProviderDeviceCodeAssistantPort {
    fun showUserCodeNotification(userCode: String)
    fun copyUserCode(userCode: String)
    fun clearUserCodeNotification()
}

fun interface ProviderOAuthImportPort {
    fun parse(json: String): ProviderOAuthImportResult
}

interface ProviderAuthFeatureOwner<Provider, Auth, Session> {
    val state: StateFlow<ProviderAuthFeatureState<Session>>
    fun authStateFor(provider: Provider): Auth
    fun isBusy(providerId: String): Boolean
    fun authError(providerId: String): String?
    fun cookieInput(providerId: String): String
    fun headerInput(providerId: String): ProviderAuthHeaderInput
    fun oauthInput(providerId: String): ProviderAuthOAuthInput
    fun onCookiesChange(providerId: String, value: String)
    fun onHeaderAuthorizationChange(providerId: String, value: String)
    fun onHeaderCookieChange(providerId: String, value: String)
    fun onOAuthClientIdChange(providerId: String, value: String)
    fun onOAuthClientSecretChange(providerId: String, value: String)
    fun loginWithCookies(providerId: String, cookiesJson: String)
    fun loginWithHeaders(providerId: String)
    fun loginWithHeaderFile(providerId: String, headerFileJson: String)
    fun startDeviceOAuthLogin()
    fun markDeviceOAuthBrowserOpened()
    fun copyDeviceOAuthUserCode()
    fun cancelDeviceOAuthLogin()
    fun loginWithOAuthJson(providerId: String, oauthJson: String)
    fun importOAuthRelatedJson(providerId: String, json: String)
    fun logout(providerId: String)
    fun refreshAll(providers: List<Provider>, refreshUserInfo: Boolean = false)
    fun dismissFeedback(feedback: String)
}

fun <Provider, Auth, Session> createProviderAuthFeatureOwner(
    sessionPort: ProviderAuthSessionPort<Auth, Session>,
    deviceAuthorizationPort: ProviderDeviceAuthorizationPort,
    deviceCodeAssistant: ProviderDeviceCodeAssistantPort,
    oauthImportPort: ProviderOAuthImportPort,
    scope: CoroutineScope,
    providerId: (Provider) -> String,
    providerName: (String) -> String,
    defaultAuth: (Provider) -> Auth,
    authProviderName: (Auth) -> String,
    authIsLoggedIn: (Auth) -> Boolean,
    authUserName: (Auth) -> String?,
    deviceOAuthProviderId: String,
    onSessionChanged: () -> Unit = {},
): ProviderAuthFeatureOwner<Provider, Auth, Session> = DefaultProviderAuthFeatureOwner(
    sessionPort = sessionPort,
    deviceAuthorizationPort = deviceAuthorizationPort,
    deviceCodeAssistant = deviceCodeAssistant,
    oauthImportPort = oauthImportPort,
    scope = scope,
    providerId = providerId,
    providerName = providerName,
    defaultAuth = defaultAuth,
    authProviderName = authProviderName,
    authIsLoggedIn = authIsLoggedIn,
    authUserName = authUserName,
    deviceOAuthProviderId = deviceOAuthProviderId,
    onSessionChanged = onSessionChanged,
)

private class DefaultProviderAuthFeatureOwner<Provider, Auth, Session>(
    private val sessionPort: ProviderAuthSessionPort<Auth, Session>,
    private val deviceAuthorizationPort: ProviderDeviceAuthorizationPort,
    private val deviceCodeAssistant: ProviderDeviceCodeAssistantPort,
    private val oauthImportPort: ProviderOAuthImportPort,
    private val scope: CoroutineScope,
    private val providerId: (Provider) -> String,
    private val providerName: (String) -> String,
    private val defaultAuth: (Provider) -> Auth,
    private val authProviderName: (Auth) -> String,
    private val authIsLoggedIn: (Auth) -> Boolean,
    private val authUserName: (Auth) -> String?,
    private val deviceOAuthProviderId: String,
    private val onSessionChanged: () -> Unit,
) : ProviderAuthFeatureOwner<Provider, Auth, Session> {
    private val mutableState = MutableStateFlow(ProviderAuthFeatureState(sessions = sessionPort.state.value))
    override val state: StateFlow<ProviderAuthFeatureState<Session>> = mutableState.asStateFlow()
    private var deviceOAuthJob: Job? = null

    init {
        scope.launch {
            sessionPort.state.collect { sessions ->
                update { copy(sessions = sessions) }
            }
        }
    }

    override fun authStateFor(provider: Provider): Auth =
        sessionPort.authState(state.value.sessions, providerId(provider)) ?: defaultAuth(provider)

    override fun isBusy(providerId: String): Boolean =
        sessionPort.isBusy(state.value.sessions, providerId) ||
            (providerId == deviceOAuthProviderId && deviceOAuthJob?.isActive == true)

    override fun authError(providerId: String): String? = sessionPort.error(state.value.sessions, providerId)

    override fun cookieInput(providerId: String): String = state.value.cookieInputs[providerId].orEmpty()

    override fun headerInput(providerId: String): ProviderAuthHeaderInput =
        state.value.headerInputs[providerId] ?: ProviderAuthHeaderInput()

    override fun oauthInput(providerId: String): ProviderAuthOAuthInput =
        state.value.oauthInputs[providerId] ?: ProviderAuthOAuthInput()

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
            runCatching { sessionPort.loginWithCookies(providerId, cookies) }
                .onSuccess { auth ->
                    update { copy(cookieInputs = cookieInputs - providerId) }
                    feedback(loginFeedback(auth))
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
            runCatching {
                sessionPort.loginWithHeaders(providerId, input.authorization.trim(), input.cookie.trim())
            }.onSuccess { auth ->
                feedback(loginFeedback(auth))
                onSessionChanged()
            }.onFailure(::failure)
        }
    }

    override fun loginWithHeaderFile(providerId: String, headerFileJson: String) {
        if (headerFileJson.isBlank()) return feedback("无法读取 header JSON")
        scope.launch {
            feedback("正在登录 ${providerName(providerId)}")
            runCatching { sessionPort.loginWithHeaderFile(providerId, headerFileJson) }
                .onSuccess { auth ->
                    feedback(loginFeedback(auth))
                    onSessionChanged()
                }
                .onFailure(::failure)
        }
    }

    override fun startDeviceOAuthLogin() {
        val input = oauthInput(deviceOAuthProviderId)
        val clientId = input.clientId.trim()
        val clientSecret = input.clientSecret.trim()
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            return feedback("请输入 ${providerName(deviceOAuthProviderId)} 的 client_id 和 client_secret")
        }
        cancelDeviceOAuthLogin()
        deviceOAuthJob = scope.launch {
            feedback("正在获取授权码")
            runCatching {
                val deviceAuth = deviceAuthorizationPort.begin(deviceOAuthProviderId, clientId, clientSecret)
                update {
                    copy(
                        oauthFlow = ProviderDeviceOAuthFlowState(
                            userCode = deviceAuth.userCode,
                            verificationUrl = deviceAuth.verificationUrl,
                            verificationUrlWithCode = deviceAuth.verificationUrlWithCode,
                            statusMessage = "请在浏览器中输入下方验证码完成授权",
                        ),
                    )
                }
                deviceCodeAssistant.showUserCodeNotification(deviceAuth.userCode)
                feedback("验证码 ${deviceAuth.userCode}（已发送通知，可点击复制）")
                val token = withTimeout(deviceAuth.expiresInSeconds.coerceAtLeast(1) * 1_000L) {
                    var intervalSeconds = deviceAuth.intervalSeconds.coerceAtLeast(1)
                    while (true) {
                        delay(intervalSeconds * 1_000L)
                        when (
                            val result = deviceAuthorizationPort.poll(
                                deviceOAuthProviderId,
                                deviceAuth.deviceCode,
                                clientId,
                                clientSecret,
                            )
                        ) {
                            is ProviderDeviceAuthorizationPollResult.Authorized -> return@withTimeout result.token
                            ProviderDeviceAuthorizationPollResult.Pending -> updateOAuthStatus("等待授权中…")
                            ProviderDeviceAuthorizationPollResult.SlowDown -> {
                                intervalSeconds += 5
                                updateOAuthStatus("轮询过快，已放慢…")
                            }
                            is ProviderDeviceAuthorizationPollResult.Denied -> error(result.message)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
                sessionPort.loginWithOAuth(
                    providerId = deviceOAuthProviderId,
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    expiresAtMillis = token.expiresAtMillis,
                    scope = token.scope,
                    clientId = clientId,
                    clientSecret = clientSecret,
                )
            }.onSuccess { auth ->
                clearOAuthUi()
                feedback(
                    if (authIsLoggedIn(auth)) {
                        "${authProviderName(auth)} 已通过 OAuth 登录"
                    } else {
                        "${authProviderName(auth)} 未登录"
                    },
                )
                onSessionChanged()
            }.onFailure { throwable ->
                clearOAuthUi()
                when (throwable) {
                    is TimeoutCancellationException -> feedback("OAuth 授权超时，请重试")
                    is CancellationException -> feedback("已取消 OAuth 登录")
                    else -> failure(throwable)
                }
            }
            deviceOAuthJob = null
        }
    }

    override fun markDeviceOAuthBrowserOpened() = update {
        copy(oauthFlow = oauthFlow?.copy(browserOpened = true, statusMessage = "请在浏览器中输入验证码完成授权"))
    }

    override fun copyDeviceOAuthUserCode() {
        val code = state.value.oauthFlow?.userCode?.takeIf(String::isNotBlank) ?: return
        deviceCodeAssistant.copyUserCode(code)
        feedback("验证码已复制：$code")
    }

    override fun cancelDeviceOAuthLogin() {
        deviceOAuthJob?.cancel()
        deviceOAuthJob = null
        clearOAuthUi()
    }

    override fun loginWithOAuthJson(providerId: String, oauthJson: String) {
        val input = oauthInput(providerId)
        if (oauthJson.isBlank()) return feedback("无法读取 oauth.json")
        if (input.clientId.isBlank() || input.clientSecret.isBlank()) {
            return feedback("导入 oauth.json 前请填写或导入 client_id 和 client_secret")
        }
        cancelDeviceOAuthLogin()
        scope.launch {
            feedback("正在登录 ${providerName(providerId)}")
            runCatching {
                sessionPort.loginWithOAuthJson(
                    providerId,
                    oauthJson,
                    input.clientId.trim(),
                    input.clientSecret.trim(),
                )
            }.onSuccess { auth ->
                feedback(
                    if (authIsLoggedIn(auth)) {
                        "${authProviderName(auth)} 已通过 oauth.json 登录"
                    } else {
                        "${authProviderName(auth)} 未登录"
                    },
                )
                onSessionChanged()
            }.onFailure(::failure)
        }
    }

    override fun importOAuthRelatedJson(providerId: String, json: String) {
        val trimmed = json.trim()
        if (trimmed.isBlank()) return feedback("无法读取 JSON 文件")
        when (val result = oauthImportPort.parse(trimmed)) {
            is ProviderOAuthImportResult.Credentials -> {
                update {
                    copy(
                        oauthInputs = oauthInputs + (
                            providerId to ProviderAuthOAuthInput(result.clientId, result.clientSecret)
                        ),
                    )
                }
                feedback("已导入 OAuth client_id / client_secret")
            }
            is ProviderOAuthImportResult.OAuthJson -> loginWithOAuthJson(providerId, result.json)
            ProviderOAuthImportResult.Unknown -> feedback("无法识别的 OAuth JSON")
        }
    }

    override fun logout(providerId: String) {
        scope.launch {
            feedback("正在退出 ${providerName(providerId)}")
            runCatching { sessionPort.logout(providerId) }
                .onSuccess { auth ->
                    update {
                        copy(
                            cookieInputs = cookieInputs - providerId,
                            headerInputs = headerInputs - providerId,
                            oauthInputs = oauthInputs - providerId,
                        )
                    }
                    if (providerId == deviceOAuthProviderId) cancelDeviceOAuthLogin()
                    feedback("${authProviderName(auth)} 已退出登录")
                    onSessionChanged()
                }
                .onFailure(::failure)
        }
    }

    override fun refreshAll(providers: List<Provider>, refreshUserInfo: Boolean) {
        scope.launch {
            providers.forEach { provider ->
                runCatching { sessionPort.refresh(providerId(provider), refreshUserInfo) }.onFailure(::failure)
            }
        }
    }

    override fun dismissFeedback(feedback: String) {
        if (state.value.feedback == feedback) update { copy(feedback = null) }
    }

    private fun loginFeedback(auth: Auth): String =
        if (authIsLoggedIn(auth)) {
            "${authProviderName(auth)} 已登录：${authUserName(auth).orEmpty()}"
        } else {
            "${authProviderName(auth)} 未登录"
        }

    private fun updateOAuthStatus(message: String) = update {
        copy(oauthFlow = oauthFlow?.copy(statusMessage = message))
    }

    private fun clearOAuthUi() {
        update { copy(oauthFlow = null) }
        deviceCodeAssistant.clearUserCodeNotification()
    }

    private fun feedback(message: String) = update { copy(feedback = message) }

    private fun failure(throwable: Throwable) {
        feedback(throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" })
    }

    private inline fun update(block: ProviderAuthFeatureState<Session>.() -> ProviderAuthFeatureState<Session>) {
        mutableState.update { current -> current.block() }
    }
}
