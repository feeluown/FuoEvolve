package org.feeluown.mobile

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import org.feeluown.mobile.provider.core.network.ProviderNetworkException

enum class ProviderFailureKind {
    LoginExpired,
    RegionRestricted,
    CopyrightUnavailable,
    UpstreamContractChanged,
    Network,
}

data class ProviderFailure(
    val kind: ProviderFailureKind,
    val providerId: String? = null,
    val technicalMessage: String? = null,
) {
    val userMessage: String
        get() = when (kind) {
            ProviderFailureKind.LoginExpired -> "登录状态已失效，请重新登录后重试"
            ProviderFailureKind.RegionRestricted -> "当前地区暂不支持此内容"
            ProviderFailureKind.CopyrightUnavailable -> "该内容因版权或资源限制不可用"
            ProviderFailureKind.UpstreamContractChanged -> "音源接口响应已变化，请更新应用或稍后重试"
            ProviderFailureKind.Network -> "网络请求失败，请检查网络后重试"
        }
}

class ProviderOperationException(
    val failure: ProviderFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.technicalMessage ?: failure.userMessage, cause)

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

internal fun providerBusinessException(
    providerId: String,
    code: Int?,
    message: String,
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
        else -> IllegalStateException(technicalMessage).providerFailureOrNull(providerId)
    }
    val failure = detected ?: ProviderFailure(
        kind = ProviderFailureKind.UpstreamContractChanged,
        providerId = providerId,
        technicalMessage = technicalMessage,
    )
    return ProviderOperationException(failure)
}

internal fun providerContractException(
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
        "account id is unavailable",
        "encrypted uin is unavailable",
        "profile is unavailable",
        "登录失效",
        "登录过期",
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
