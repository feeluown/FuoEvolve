package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProviderSessionOperation {
    Refresh,
    Login,
    Logout,
}

data class ProviderSessionState(
    val authStates: Map<String, ProviderAuthState> = emptyMap(),
    val operations: Map<String, ProviderSessionOperation> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
)

interface ProviderSessionRepository {
    val state: StateFlow<ProviderSessionState>

    suspend fun updateProviders(providers: List<ProviderInfo>)
    suspend fun refresh(providerId: String, refreshUserInfo: Boolean = false): ProviderAuthState
    suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState
    suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState
    suspend fun loginWithYtmusicHeaderFile(headerFileJson: String): ProviderAuthState
    suspend fun logout(providerId: String): ProviderAuthState
}

/**
 * 音源登录态的唯一写入入口。
 *
 * 所有刷新、登录和退出操作都通过同一把互斥锁提交，避免慢刷新覆盖刚完成的登录结果，
 * 也避免登录与退出并发时界面最终显示旧状态。
 */
class DefaultProviderSessionRepository(
    private val providerRepository: ProviderMusicRepository,
) : ProviderSessionRepository {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(ProviderSessionState())

    override val state: StateFlow<ProviderSessionState> = mutableState.asStateFlow()

    override suspend fun updateProviders(providers: List<ProviderInfo>) {
        operationMutex.withLock {
            val providerIds = providers.mapTo(mutableSetOf()) { it.providerId }
            val current = mutableState.value
            mutableState.value = current.copy(
                authStates = providers.associate { provider ->
                    provider.providerId to (current.authStates[provider.providerId] ?: ProviderAuthState(
                        providerId = provider.providerId,
                        providerName = provider.providerName,
                        isLoggedIn = false,
                    ))
                },
                operations = current.operations.filterKeys(providerIds::contains),
                errors = current.errors.filterKeys(providerIds::contains),
            )
        }
    }

    override suspend fun refresh(providerId: String, refreshUserInfo: Boolean): ProviderAuthState =
        mutate(providerId, ProviderSessionOperation.Refresh) {
            if (refreshUserInfo) {
                providerRepository.refreshAuthState(providerId)
            } else {
                providerRepository.authState(providerId)
            }
        }

    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
        mutate(providerId, ProviderSessionOperation.Login) {
            providerRepository.loginWithCookies(providerId, cookiesJson)
        }

    override suspend fun loginWithHeaders(
        providerId: String,
        authorization: String,
        cookie: String,
    ): ProviderAuthState = mutate(providerId, ProviderSessionOperation.Login) {
        providerRepository.loginWithHeaders(providerId, authorization, cookie)
    }

    override suspend fun loginWithYtmusicHeaderFile(headerFileJson: String): ProviderAuthState =
        mutate("ytmusic", ProviderSessionOperation.Login) {
            providerRepository.loginWithYtmusicHeaderFile(headerFileJson)
        }

    override suspend fun logout(providerId: String): ProviderAuthState =
        mutate(providerId, ProviderSessionOperation.Logout) {
            providerRepository.logout(providerId)
        }

    private suspend fun mutate(
        providerId: String,
        operation: ProviderSessionOperation,
        block: suspend () -> ProviderAuthState,
    ): ProviderAuthState = operationMutex.withLock {
        val before = mutableState.value
        mutableState.value = before.copy(
            operations = before.operations + (providerId to operation),
            errors = before.errors - providerId,
        )
        try {
            val authState = block()
            val current = mutableState.value
            mutableState.value = current.copy(
                authStates = current.authStates + (providerId to authState),
                operations = current.operations - providerId,
                errors = current.errors - providerId,
            )
            authState
        } catch (throwable: Throwable) {
            val current = mutableState.value
            mutableState.value = current.copy(
                operations = current.operations - providerId,
                errors = current.errors + (
                    providerId to (throwable.message ?: throwable::class.simpleName.orEmpty())
                ),
            )
            throw throwable
        }
    }
}
