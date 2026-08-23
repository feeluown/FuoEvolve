package org.feeluown.mobile.provider.qqmusic

import org.feeluown.mobile.ProviderFailure
import org.feeluown.mobile.ProviderFailureKind

/**
 * Transitional package-local token bridge for QQ Music call sites that still feed the
 * runtime's legacy `errorMessage` compatibility constructors. These values are classifier
 * tokens only; user-facing/localized presentation remains owned by the application layer.
 */
internal val ProviderFailure.userMessage: String
    get() = when (kind) {
        ProviderFailureKind.LoginExpired -> "login required"
        ProviderFailureKind.RegionRestricted -> "region restricted"
        ProviderFailureKind.CopyrightUnavailable -> "copyright unavailable"
        ProviderFailureKind.ContentUnavailable -> "content unavailable"
        ProviderFailureKind.AccountUnavailable -> "account id is unavailable"
        ProviderFailureKind.UnsupportedOperation -> "not supported"
        ProviderFailureKind.UpstreamContractChanged -> "response format changed"
        ProviderFailureKind.Network -> "network request failed"
        ProviderFailureKind.Unknown -> "provider request failed"
    }
