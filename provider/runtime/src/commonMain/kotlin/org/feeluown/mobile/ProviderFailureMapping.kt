package org.feeluown.mobile

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import org.feeluown.mobile.provider.core.network.ProviderNetworkException

fun Throwable.providerFailureOrNull(providerId: String? = null): ProviderFailure? {
    causeSequence().filterIsInstance<ProviderOperationException>().firstOrNull()?.let { return it.failure }

    val failures = causeSequence().toList()
    val technicalMessage = failures.mapNotNull(Throwable::message).joinToString(" · ").takeIf(String::isNotBlank)
    val normalizedMessage = technicalMessage.orEmpty().lowercase()

    failureKindFromMessage(normalizedMessage)?.let { kind ->
        return ProviderFailure(kind, providerId, technicalMessage)
    }
    failures.filterIsInstance<ProviderNetworkException.Http>().firstOrNull()?.let { exception ->
        val kind = when (exception.statusCode) {
            401, 403 -> ProviderFailureKind.LoginExpired
            451 -> ProviderFailureKind.RegionRestricted
            404, 410, 422 -> ProviderFailureKind.UpstreamContractChanged
            else -> ProviderFailureKind.Network
        }
        return ProviderFailure(kind, providerId, technicalMessage)
    }
    if (failures.any { it is TimeoutCancellationException || it is ProviderNetworkException.Timeout || it is ProviderNetworkException.Transport }) {
        return ProviderFailure(ProviderFailureKind.Network, providerId, technicalMessage)
    }
    if (failures.any { it is SerializationException }) {
        return ProviderFailure(ProviderFailureKind.UpstreamContractChanged, providerId, technicalMessage)
    }
    return null
}

/**
 * @param loginExpiredCodes upstream business codes that mean "the session expired and the user has to
 *   sign in again". Providers pass their own vendor codes so a shared integer namespace is never assumed.
 */
fun providerBusinessException(
    providerId: String,
    code: Int?,
    message: String,
    loginExpiredCodes: Set<Int> = emptySet(),
): ProviderOperationException {
    val technicalMessage = buildString {
        append(providerId)
        append(" provider request failed")
        code?.let { append(" (code=$it)") }
        message.takeIf(String::isNotBlank)?.let {
            append(": ")
            append(it)
        }
    }
    val detected = when (code) {
        301, 302, 401, 403 -> ProviderFailure(
            ProviderFailureKind.LoginExpired,
            providerId,
            technicalMessage,
        )
        451 -> ProviderFailure(
            ProviderFailureKind.RegionRestricted,
            providerId,
            technicalMessage,
        )
        else -> if (code != null && code in loginExpiredCodes) {
            ProviderFailure(
                ProviderFailureKind.LoginExpired,
                providerId,
                technicalMessage,
            )
        } else {
            IllegalStateException(technicalMessage).providerFailureOrNull(providerId)
        }
    }
    // Unrecognized business codes keep the conservative contract-change bucket; providers that
    // express expiry through their own codes pass them via [loginExpiredCodes] so a dead session is
    // never reported as "the upstream response changed, please update the app".
    val failure = detected ?: ProviderFailure(
        kind = ProviderFailureKind.UpstreamContractChanged,
        providerId = providerId,
        technicalMessage = technicalMessage,
    )
    return ProviderOperationException(failure)
}

fun providerContractException(
    providerId: String,
    message: String,
    cause: Throwable? = null,
): ProviderOperationException = ProviderOperationException(
    ProviderFailure(
        kind = ProviderFailureKind.UpstreamContractChanged,
        providerId = providerId,
        technicalMessage = message,
    ),
    cause,
)

private fun Throwable.causeSequence(): Sequence<Throwable> = generateSequence(this) { it.cause }

private fun failureKindFromMessage(message: String): ProviderFailureKind? = when {
    message.containsAny(
        "login required",
        "not logged in",
        "authentication expired",
        "token expired",
        "access token expired",
        "cookie expired",
        "invalid cookie",
        "session expired",
        "account id is unavailable",
        "encrypted uin is unavailable",
        "profile is unavailable",
        "登录失效",
        "登录过期",
        "登录态失效",
        "未登录",
        "重新登录",
    ) -> ProviderFailureKind.LoginExpired

    message.containsAny(
        "not available in your country",
        "country is not supported",
        "geo restricted",
        "region restricted",
        "region restriction",
        "地区限制",
        "当前地区",
        "海外限制",
    ) -> ProviderFailureKind.RegionRestricted

    message.containsAny(
        "media not found",
        "copyright unavailable",
        "copyright restriction",
        "not playable due to copyright",
        "版权限制",
        "版权或资源限制",
        "无版权",
        "资源不可用",
    ) -> ProviderFailureKind.CopyrightUnavailable

    message.containsAny(
        "payload missing",
        "missing data",
        "response format",
        "unexpected response",
        "invalid response",
        "schema changed",
        "json decoding",
        "接口响应",
        "响应格式",
        "字段缺失",
    ) -> ProviderFailureKind.UpstreamContractChanged

    else -> null
}

private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
