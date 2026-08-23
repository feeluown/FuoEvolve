package org.feeluown.mobile.provider.qqmusic

import org.feeluown.mobile.ProviderFailure
import org.feeluown.mobile.userMessage as applicationUserMessage

/**
 * Transitional package-local bridge for QQMusic sources that historically read
 * ProviderFailure.userMessage as a member property. Presentation text remains
 * owned by the shared application-layer mapper.
 */
internal val ProviderFailure.userMessage: String
    get() = this.applicationUserMessage
